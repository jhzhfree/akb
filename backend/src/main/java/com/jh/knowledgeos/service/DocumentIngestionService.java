package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.EventTaskContext;
import com.jh.knowledgeos.common.InMemoryMultipartFile;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.KnowledgeDocument;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.mapper.EvidenceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service that parses uploads outside transactions and persists authoritative document state in short transactions.
 */
@Service
public class DocumentIngestionService {

    private static final double DEFAULT_AUTHORITY_SCORE = 0.80D;

    private static final int DB_BATCH_SIZE = 100;

    /**
     * 抽取排队的最长等待时间：等不到槽位就让这次摄入带着明确原因失败，而不是一直挂着，
     * 从外面看跟“后端卡死”没有区别。
     */
    private static final int EXTRACTION_QUEUE_TIMEOUT_SECONDS = 300;

    /**
     * 抽取阶段的并发闸门。
     *
     * <p>抽取是这条链路上最重的一步，也是唯一会争抢模型资源的一步：解析、持久化、索引
     * 都可以并发，只有模型推理会被排队。批量导入按 3 并发同时打抽取时，本地模型
     * （{@code qwen2.5:7b}）会被打满，表现为某些文档拿到一份“什么都没有”的结果——
     * 统计里显示为 {@code received=0}，也就是上游一个候选都没给。</p>
     *
     * <p><b>实测：{@code received=0} 的真正原因不是并发</b>，而是传给抽取服务的 units 取错了
     * 对象（用了不带 evidence_code 的解析原始块，模型调用失败时兜底路径产不出任何知识）。
     * 串行导入同样会随机出现空产出，且失败的文档每次不同。闸门修好前曾把并发降到 1 观察：
     * 排队耗时随之涨到 70–112 秒/篇，空产出却照旧——说明这个代价没有换来任何收益。因此
     * 默认恢复 3；闸门本身保留，排队耗时继续上报，一旦模型层真成为瓶颈，改配置即可。</p>
     *
     * <p>只把这一步串行化，其余阶段仍并发，因此总耗时增加有限。</p>
     *
     * <p><b>这只约束本实例。</b>多实例部署时每个实例各有一道闸门，总量仍是“实例数 × 上限”；
     * 真正的部署级限制要在共享的模型调用层做，这里解决的是单机批量导入把模型打满这一
     * 最常见的情形。</p>
     */
    private final Semaphore extractionSlot;

    private final KnowledgeMapper mapper;

    private final DocumentMapper documentMapper;

    private final EvidenceMapper evidenceMapper;


    private final AiClient aiClient;

    private final KnowledgeExtractionService extractionService;

    private final ObjectMapper objectMapper;

    private final IdGenerator idGenerator;

    private final TransactionRunner transactionRunner;

    private final IndexOutboxService outboxService;

    private final IngestionObservabilityService observabilityService;

    private final SourceFileStorageService sourceFileStorage;

    private final PlatformEventPublisher publisher;

    private final ChunkPolicyService chunkPolicyService;

    private final KbScenarioService kbScenarioService;

    private final TaskCenterService taskCenterService;

    public DocumentIngestionService(KnowledgeMapper mapper, AiClient aiClient, KnowledgeExtractionService extractionService, ObjectMapper
        objectMapper, IdGenerator idGenerator, TransactionRunner transactionRunner, IndexOutboxService outboxService, IngestionObservabilityService
        observabilityService, SourceFileStorageService sourceFileStorage, PlatformEventPublisher publisher, ChunkPolicyService chunkPolicyService,
        KbScenarioService kbScenarioService, @Lazy TaskCenterService taskCenterService, DocumentMapper documentMapper, EvidenceMapper evidenceMapper,
        @Value("${knowledge.extraction.concurrency:3}") int extractionConcurrency) {
        this.evidenceMapper = evidenceMapper;
        this.documentMapper = documentMapper;
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.extractionService = extractionService;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
        this.observabilityService = observabilityService;
        this.sourceFileStorage = sourceFileStorage;
        this.publisher = publisher;
        this.extractionSlot = new Semaphore(Math.max(1, extractionConcurrency));
        this.chunkPolicyService = chunkPolicyService;
        this.kbScenarioService = kbScenarioService;
        this.taskCenterService = taskCenterService;
    }

    /**
     * Parses an uploaded source outside the DB transaction, persists its authoritative
     * source/document/chunk/evidence state, enqueues indexing, then triggers extraction.
     */
    public Map<String, Object> upload(Long knowledgeBaseId, MultipartFile file, Double authorityScore, String permissionTags) throws Exception {
        String name = file == null ? "" : String.valueOf(file.getOriginalFilename());
        // Ingestion is the longest job on the platform and the one operators are asked about
        // most, so it is registered in the task centre before a single byte is parsed: a crash
        // mid-way leaves a failed task instead of a silent hole.
        return tracked(knowledgeBaseId, "文档接入：" + name, null, () -> ingest(knowledgeBaseId, file, authorityScore, permissionTags, null));
    }

    /**
     * Runs work as a tracked task: the ledger is updated whatever the outcome.
     */
    private Map<String, Object> tracked(Long knowledgeBaseId, String taskName, String targetCode, Callable<Map<String, Object>> work) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("documentId", targetCode);
        Map<String, Object> task = taskCenterService.enqueue(knowledgeBaseId, TaskCenterService.TYPE_INGESTION, taskName, "DOCUMENT", targetCode,
            TaskCenterService.TRIGGER_MANUAL, payload);
        String taskCode = String.valueOf(task.get("taskCode"));
        taskCenterService.start(taskCode, "PARSE");
        try {
            Map<String, Object> result = work.call();
            taskCenterService.succeed(taskCode, result);
            return result;
        } catch (Exception e) {
            taskCenterService.fail(taskCode, e.getMessage());
            throw e;
        }
    }

    /**
     * Re-runs a document that failed or whose index went dirty, starting from the bytes kept in
     * source storage. The previous chunk revision is retired instead of being overwritten, because
     * Evidence rows already point at those chunks and silently rewriting them would break citation
     * traceability.
     */
    public Map<String, Object> retry(Long documentId) throws Exception {
        Map<String, Object> context = documentMapper.findDocumentRetryContext(documentId);
        if (context == null) {
            throw new IllegalArgumentException("文档不存在：" + documentId);
        }
        String status = String.valueOf(context.get("status"));
        if (!"FAILED".equals(status) && !"DIRTY".equals(status)) {
            throw new IllegalStateException("仅 FAILED / DIRTY 文档可重试，当前状态：" + status);
        }
        Path stored = sourceFileStorage.resolve(String.valueOf(context.get("storagePath")));
        if (stored == null || !Files.isReadable(stored)) {
            int retries = toInt(context.get("retryCount")) + 1;
            documentMapper.updateDocumentState(documentId, "FAILED", "RETRY", "原始文件缺失，无法重跑", retries);
            throw new IllegalStateException("原始文件缺失，无法重跑该文档");
        }
        final InMemoryMultipartFile file = InMemoryMultipartFile.of(stored, String.valueOf(context.get("sourceName")),
            mimeFromExtension(String.valueOf(context.get("sourceName"))));
        final Long knowledgeBaseId = toLong(context.get("knowledgeBaseId"));
        return tracked(knowledgeBaseId, "文档重跑：" + documentId, String.valueOf(documentId), () -> ingest(knowledgeBaseId, file,
            toDouble(context.get("authorityScore")), String.valueOf(context.get("permissionTags")), context));
    }

    /**
     * Entry point for sources that already arrive as text — a connector item. The parsed model is
     * produced by {@link ConnectorItemNormalizer} instead of {@code /ai/parse}; from here on the
     * pipeline is the one an upload runs: same source, document, chunk, evidence and extraction
     * rows, same outbox events, same retrieval channels.
     */
    public Map<String, Object> ingestNormalized(Long knowledgeBaseId, String name, String sourceUri, String sourceType, String mimeType,
        Double authorityScore, String permissionTags, Map<String, Object> parsed) {
        String sha256 = parsed == null ? null : String.valueOf(parsed.get("sha256"));
        IngestionMaterial material = new IngestionMaterial(name, sourceUri, sourceType, mimeType, sha256, null, 0L);
        try {
            return runIngestion(knowledgeBaseId, material, parsed, authorityScore, permissionTags, null);
        } catch (Exception ex) {
            throw new IllegalArgumentException("连接器条目入库失败：" + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> ingest(Long knowledgeBaseId, MultipartFile file, Double authorityScore, String permissionTags,
        Map<String, Object> retryContext) throws Exception {
        Map<String, Object> parsed = aiClient.parse(file, chunkPolicy(knowledgeBaseId));
        IngestionMaterial material = new IngestionMaterial(file.getOriginalFilename(), "upload://" + file.getOriginalFilename(),
            String.valueOf(parsed.get("extension")), file.getContentType(), String.valueOf(parsed.get("sha256")), file.getBytes(), file.getSize());
        return runIngestion(knowledgeBaseId, material, parsed, authorityScore, permissionTags, retryContext);
    }

    @SuppressWarnings("unchecked")
    /**
     * Chunking for this upload: an edited chunk policy wins, otherwise the scenario template's.
     *
     * <p>A library created as 运维知识库 cuts a runbook into heading-sized parent/child units
     * without anybody touching the slider; the moment somebody does touch it, the explicit
     * choice wins, because a stored answer is worth more than a template guess.</p>
     */
    private Map<String, Object> chunkPolicy(Long knowledgeBaseId) {
        return chunkPolicyService.customized(knowledgeBaseId) ? chunkPolicyService.policyFor(knowledgeBaseId)
            : kbScenarioService.chunkPolicy(knowledgeBaseId);
    }

    private Map<String, Object> runIngestion(Long knowledgeBaseId, IngestionMaterial material, Map<String, Object> parsed, Double authorityScore,
        String permissionTags, Map<String, Object> retryContext) throws Exception {
        Long retryDocumentId = retryContext == null ? null : toLong(retryContext.get("id"));
        int retryCount = retryContext == null ? 0 : toInt(retryContext.get("retryCount")) + 1;
        com.jh.knowledgeos.domain.IngestionRun run = observabilityService.start(knowledgeBaseId, material.name);
        // Mutable holders: a failure has to record the stage it died in, and the document id only
        // exists once the persist transaction ran, but the catch block needs both.
        final String[] stageHolder = new String[] { "PARSE" };
        final Long[] documentIdHolder = new Long[] { retryDocumentId };
        try {
            publisher.emitUser(PlatformEventTypes.INGESTION_DOCUMENT_RECEIVED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "INGESTION_RUN",
                run.getRunCode(), "文档接收开始解析：" + material.name, receivedDetail(run, material, authorityScore), taskContext(run.getRunCode(),
                retryDocumentId, null, retryCount));
            if (retryDocumentId != null) {
                documentMapper.updateDocumentState(retryDocumentId, "RETRYING", "PARSE", null, retryCount);
            }
            observabilityService.event(run.getId(), "PARSE_STARTED", "PARSE", "RUNNING", "开始解析文档", null);
            List<Map<String, Object>> parsedChunks = parsed.get("chunks") instanceof List ? (List<Map<String, Object>>) parsed.get("chunks") : new
                ArrayList<Map<String, Object>>();
            // The parser returns one canonical model for every source format; chunks are the
            // normalized units of that model, already free of layout-only content.
            Map<String, Object> standard = parsed.get("standard") instanceof Map ? (Map<String, Object>) parsed.get("standard") : new
                LinkedHashMap<String, Object>();
            observabilityService.event(run.getId(), "PARSE_COMPLETED", "PARSE", "SUCCESS", "文档解析完成，共 " + parsedChunks.size() + " 个标准单元", null);
            observabilityService.event(run.getId(), "PERSIST_STARTED", "PERSIST", "RUNNING", "写入权威知识数据", null);
            // Retried on deadlock: a batch runs several documents through this write set at once,
            // and every INSERT here is rolled back cleanly by the database, so redoing the whole
            // transaction is a no-op rather than a duplicate. The expensive AI calls sit outside
            // it precisely so that a retry never pays for them twice.
            IngestionState state = transactionRunner.retryingOnDeadlock(() -> {
                try {
                    KnowledgeSource source;
                    KnowledgeDocument document;
                    if (retryDocumentId == null) {
                        source = createSource(knowledgeBaseId, material, authorityScore, permissionTags);
                        document = createDocument(source, material);
                    } else {
                        // A retry keeps the original source and document identity: the chunks are
                        // what gets a new revision, so every Evidence row stays resolvable.
                        source = sourceFromRetryContext(retryContext);
                        document = documentFromRetryContext(retryContext);
                        documentMapper.updateDocumentState(document.getId(), "PROCESSING", "CHUNK", null, retryCount);
                        documentMapper.supersedeDocumentChunks(document.getId());
                    }
                    documentIdHolder[0] = document.getId();
                    int revision = retryDocumentId == null ? 1 : documentMapper.nextChunkRevision(document.getId());
                    List<Map<String, Object>> indexChunks = persistChunks(knowledgeBaseId, source, document, parsedChunks, revision);
                    documentMapper.updateDocumentParsed(document.getId(), String.valueOf(parsed.get("text")), indexChunks.size(), "PARSED");
                    // 索引事件此刻只是入队，还没投递成功。记成 PENDING 而不是留空：
                    // 投递失败后这批文档能被筛出来，而不是被当成“已经可检索”。
                    documentMapper.updateIndexStatus(document.getId(), "PENDING");
                    Map<String, Object> indexEvent = outboxService.enqueue(knowledgeBaseId, "DOCUMENT", "KB:" + knowledgeBaseId + ":DOCUMENT:" +
                        document.getId(), "DOCUMENT_CHUNKS_UPSERT", AiApiPaths.INDEX_CHUNKS, buildIndexReference(knowledgeBaseId, document),
                        "DOCUMENT_CHUNKS:" + document.getId() + ":" + revision);
                    // The normalized standard document is stored as its own retrieval view: it is
                    // the material card generation reads from, so it is persisted in the same
                    // transaction that makes the document durable.
                    Map<String, Object> standardEvent = outboxService.enqueue(knowledgeBaseId, "DOCUMENT", "KB:" + knowledgeBaseId + ":STANDARD:" +
                        document.getId(), "DOCUMENT_STANDARD_UPSERT", AiApiPaths.INDEX_STANDARD_DOCUMENT, buildStandardPayload(knowledgeBaseId,
                        source, document, standard, parsedChunks), "DOCUMENT_STANDARD:" + document.getId() + ":" + revision);
                    return new IngestionState(source, document, indexChunks, indexEvent, standardEvent, revision);
                } catch (Exception ex) {
                    throw new IllegalStateException("Persist parsed document failed", ex);
                }
            });
            stageHolder[0] = "CHUNK";
            publisher.emitUser(PlatformEventTypes.CHUNK_CHANGED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "DOCUMENT",
                String.valueOf(state.document.getId()), "文档切片完成：" + state.indexChunks.size() + " 个 Chunk（revision " + state.revision + "）",
                chunkDetail(state), taskContext(run.getRunCode(), state.document.getId(), String.valueOf(state.revision), retryCount));
            observabilityService.event(run.getId(), "PERSIST_COMPLETED", "PERSIST", "SUCCESS", "Source / Document / ContentUnit / Evidence 已保存", null);
            observabilityService.event(run.getId(), "INDEX_EVENT_CREATED", "INDEX", "SUCCESS", "已创建 Transactional Outbox 索引事件", state.indexEvent);
            observabilityService.event(run.getId(), "STANDARD_EVENT_CREATED", "STANDARD", "SUCCESS", "标准化数据已入队写入 Elasticsearch", state.standardEvent);
            observabilityService.event(run.getId(), "EXTRACTION_STARTED", "EXTRACT", "RUNNING", "开始抽取 Knowledge Card / Claim / Entity / Relation", null);
            stageHolder[0] = "EXTRACT";
            documentMapper.updateDocumentState(state.document.getId(), "PROCESSING", "EXTRACT", null, retryCount);
            /**
             * units 必须传入库后的标准单元（indexChunks），不能传解析器的原始块（parsedChunks）：
             * 只有前者带 evidence_code / content_unit_id。
             *
             * <p>抽取服务在模型调用失败时会走兜底路径，兜底是逐 unit 产出知识的，而它认的是
             * evidence_code——拿不到就一条也产不出来，表现为这篇文档候选数为 0。这曾是"批量
             * 导入静默丢知识"的直接原因。</p>
             *
             * <p>模型返回的 evidence_ids 同样出自这个字段：传原始块时它恒为空，证据就只能回落
             * 到整篇文档，引用被系统性夸大。</p>
             */
            Map<String, Object> extractionPayload = buildExtractionPayload(knowledgeBaseId, state.source, state.document,
                parsed, state.indexChunks, state.indexChunks);
            Map<String, Object> knowledgeResult = extractWithSlot(extractionPayload);
            int cardCount = resultCount(knowledgeResult, "cards");
            int claimCount = resultCount(knowledgeResult, "claims");
            /**
             * 批量导入是并发的，并发抽取偶尔会拿到一份“什么都没有”的结果，而这段流程原本
             * 无论拿到什么都写 SUCCESS——文档会被标成 READY、run 也会被标成 SUCCEEDED，
             * 于是这篇文档的知识就这么没了，界面上却看不出任何异常（验证时 4 篇里丢了 2 篇，
             * 且每次丢的不是同一篇）。这里补两步：
             * 1) 空结果重试一次：抽取是幂等的（幂等键由数据库唯一约束保证），重试不会产出重复知识；
             * 2) 仍然为空就记一条 WARN 事件，让“抽不出来”这件事在事件流里看得见，
             *    而不是混在成功的 ingestion 里。
             *
             * <p><b>判空看的是“这一次留下了几条”，不是“返回了几条候选”</b>：重跑一篇已经
             * 抽取过的文档时，新增为 0、全部命中已有知识（复用并补证据）是完全正常的成功，
             * 按返回条数判空会把它误当成失败反复重抽。</p>
             */
            if (persistedCount(knowledgeResult) == 0) {
                knowledgeResult = extractWithSlot(extractionPayload);
                cardCount = resultCount(knowledgeResult, "cards");
                claimCount = resultCount(knowledgeResult, "claims");
            }
            if (persistedCount(knowledgeResult) == 0) {
                observabilityService.event(run.getId(), "EXTRACTION_EMPTY", "EXTRACT", "WARN",
                    "抽取未产出任何卡片或断言（已重试一次），该文档已解析但无知识落地",
                    extractionEventDetail(state.document.getId(), knowledgeResult));
            }
            // 抽取的成败单独记一列。解析成功但抽取为空，正是最需要被筛出来重跑的一类文档；
            // 混在文档 status 里就只能靠“重新导入一遍试试”来发现。
            documentMapper.updateExtractionStatus(state.document.getId(),
                persistedCount(knowledgeResult) > 0 ? "EXTRACTED" : "EMPTY");
            observabilityService.event(run.getId(), "EXTRACTION_COMPLETED", "EXTRACT", "SUCCESS", "候选知识抽取完成",
                extractionEventDetail(state.document.getId(), knowledgeResult));
            observabilityService.complete(run.getId(), state.indexChunks.size(), cardCount, claimCount);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("runId", run.getId());
            result.put("runCode", run.getRunCode());
            result.put("sourceId", state.source.getId());
            result.put("documentId", state.document.getId());
            result.put("filename", material.name);
            result.put("chunkCount", state.indexChunks.size());
            result.put("chunkRevision", state.revision);
            result.put("status", "READY");
            result.put("index", state.indexEvent);
            result.put("standardIndex", state.standardEvent);
            result.put("standard", standardSummary(standard));
            result.put("knowledge", knowledgeResult);
            // READY is claimed only after extraction succeeded: the document is not usable
            // knowledge while its cards and claims are still missing.
            stageHolder[0] = "READY";
            documentMapper.updateDocumentState(state.document.getId(), "READY", "READY", null, retryCount);
            publisher.emitUser(PlatformEventTypes.INGESTION_COMPLETED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "INGESTION_RUN",
                run.getRunCode(), "文档摄入完成：" + material.name, completedDetail(run, material, state, cardCount, claimCount, knowledgeResult),
                taskContext(run.getRunCode(), state.document.getId(), String.valueOf(state.revision), retryCount));
            return result;
        } catch (Exception ex) {
            observabilityService.fail(run.getId(), "FAILED", ex);
            Long failedDocument = documentIdHolder[0];
            if (failedDocument != null) {
                documentMapper.updateDocumentState(failedDocument, "FAILED", stageHolder[0], abbreviate(ex.getMessage(), 900), retryCount);
            }
            Map<String, Object> detail = receivedDetail(run, material, authorityScore);
            detail.put("stage", stageHolder[0]);
            detail.put("documentId", failedDocument);
            detail.put("retryCount", retryCount);
            detail.put("error", ex.getMessage());
            publisher.emitUser(PlatformEventTypes.INGESTION_FAILED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "INGESTION_RUN", run == null ?
                null : run.getRunCode(), "文档摄入失败：" + material.name, detail, taskContext(run == null ? null : run.getRunCode(),
                failedDocument, null, retryCount));
            throw ex;
        }
    }

    /**
     * Task identity every ingestion event carries, so a subscriber can join events to one run.
     */
    private EventTaskContext taskContext(String taskId, Long objectId, String objectVersion, int retryCount) {
        return EventTaskContext.task(taskId).object(objectId == null ? null : String.valueOf(objectId), objectVersion).retryCount(retryCount).build();
    }

    private Map<String, Object> chunkDetail(IngestionState state) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("documentId", state.document.getId());
        detail.put("chunkCount", state.indexChunks.size());
        detail.put("revision", state.revision);
        return detail;
    }

    /**
     * Shared ingestion detail so the received / completed / failed events read the same way.
     */
    private Map<String, Object> receivedDetail(com.jh.knowledgeos.domain.IngestionRun run, IngestionMaterial material, Double authorityScore) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("runCode", run == null ? null : run.getRunCode());
        detail.put("filename", material.name);
        detail.put("size", material.size);
        detail.put("contentType", material.mimeType);
        detail.put("sourceUri", material.sourceUri);
        detail.put("authorityScore", authorityScore);
        return detail;
    }

    private Map<String, Object> completedDetail(com.jh.knowledgeos.domain.IngestionRun run, IngestionMaterial material, IngestionState state,
        int cardCount, int claimCount, Map<String, Object> knowledgeResult) {
        Map<String, Object> detail = receivedDetail(run, material, null);
        detail.put("sourceId", state.source.getId());
        detail.put("documentId", state.document.getId());
        detail.put("chunkCount", state.indexChunks.size());
        detail.put("cardCount", cardCount);
        detail.put("claimCount", claimCount);
        detail.put("conflictCount", resultCount(knowledgeResult, "conflicts"));
        return detail;
    }

    private int resultCount(Map<String, Object> result, String key) {
        Object value = result.get(key);
        if (value instanceof List) {
            return ((List<?>) value).size();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        Object count = result.get(key + "Count");
        return count instanceof Number ? ((Number) count).intValue() : 0;
    }

    /**
     * 只重跑抽取，不重新解析文件。
     *
     * <p>解析很贵：要重读文件、重新切片、重建证据。而"抽不出来"往往只是模型那一次没给
     * 东西，与解析结果无关。这时候重新解析一遍，除了把已经建立的引用关系全部作废，没有
     * 任何收益——引用指向的证据编码会换一批，旧的引用就成了死链。</p>
     *
     * <p>因此这里复用已入库的标准单元与其证据编码：重跑产出的知识仍然锚在原来的原文位置。
     * 知识内容变了，已有索引不再反映当前内容，所以索引状态置为 STALE，由调用方决定何时
     * 重建——重跑抽取本身不碰索引。</p>
     */
    public Map<String, Object> reextract(Long documentId) {
        Map<String, Object> context = documentMapper.findReextractContext(documentId);
        if (context == null) {
            throw new IllegalArgumentException("文档不存在：id=" + documentId);
        }
        List<Map<String, Object>> units = documentMapper.findUnitsWithEvidence(documentId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("documentId", documentId);
        result.put("unitCount", units == null ? 0 : units.size());
        if (units == null || units.isEmpty()) {
            result.put("skipped", Boolean.TRUE);
            result.put("reason", "该文档还没有已入库的标准单元，需要先完整导入一次");
            return result;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", context.get("knowledgeBaseId"));
        payload.put("sourceId", context.get("sourceId"));
        payload.put("documentId", documentId);
        payload.put("text", context.get("rawText"));
        payload.put("chunks", units);
        payload.put("units", units);
        payload.put("permissionTags", context.get("permissionTags"));
        Map<String, Object> knowledge = extractWithSlot(payload);
        int persisted = persistedCount(knowledge);
        String extractionStatus = persisted > 0 ? "EXTRACTED" : "EMPTY";
        documentMapper.updateExtractionStatus(documentId, extractionStatus);
        documentMapper.updateIndexStatus(documentId, "STALE");
        result.put("cards", resultCount(knowledge, "cards"));
        result.put("claims", resultCount(knowledge, "claims"));
        result.put("persisted", persisted);
        result.put("extractionStatus", extractionStatus);
        result.put("indexStatus", "STALE");
        return result;
    }

    /**
     * 带并发闸门的抽取调用：先等模型槽位，再调模型，并把纯排队耗时带回事件里。
     *
     * <p>排队时间是判断“模型是不是瓶颈”的直接证据：批次里每篇都在排队，说明并发配高了；
     * 排队恒为 0 却仍有空结果，瓶颈就在别处——这两种情况的处置方向完全不同，没有这个数
     * 只能靠猜。</p>
     */
    private Map<String, Object> extractWithSlot(Map<String, Object> payload) {
        long arrivedAt = System.currentTimeMillis();
        boolean acquired = false;
        try {
            acquired = extractionSlot.tryAcquire(EXTRACTION_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抽取排队被中断", ex);
        }
        long queuedMs = System.currentTimeMillis() - arrivedAt;
        if (!acquired) {
            throw new IllegalStateException("抽取排队超时：" + EXTRACTION_QUEUE_TIMEOUT_SECONDS + "s 内未等到模型槽位");
        }
        try {
            Map<String, Object> result = extractionService.extract(payload);
            if (result != null) {
                result.put("extractionQueueMs", queuedMs);
            }
            return result;
        } finally {
            extractionSlot.release();
        }
    }

    /**
     * 这一次抽取实际留下的知识条数：新增 + 复用。
     *
     * <p>复用也算留下东西——它命中了已有知识并把这一次的证据补了上去，本身就是有价值的
     * 结果。只有两类加起来为 0，才说明这次运行真的什么都没留下，需要考虑重试。</p>
     */
    @SuppressWarnings("unchecked")
    private int persistedCount(Map<String, Object> result) {
        Object stats = result.get("extractionStats");
        if (!(stats instanceof Map)) {
            return resultCount(result, "cards") + resultCount(result, "claims");
        }
        Map<String, Object> map = (Map<String, Object>) stats;
        return statValue(map.get("card"), "created") + statValue(map.get("card"), "reused")
            + statValue(map.get("claim"), "created") + statValue(map.get("claim"), "reused");
    }

    @SuppressWarnings("unchecked")
    private int statValue(Object stats, String key) {
        if (!(stats instanceof Map)) {
            return 0;
        }
        Object value = ((Map<String, Object>) stats).get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /**
     * What the EXTRACTION_COMPLETED event gets to keep about an extraction.
     *
     * <p>The extractor returns the whole reasoned output of a document — every card body, every
     * claim, the entities and relations. Handing that to the event copies the document into
     * observability storage: past a modest file it overflows the detail column, and in strict mode
     * MySQL answers with {@code Data truncation}, which would fail a document that had in fact
     * extracted everything correctly. The objects are durable in their own tables; an event only
     * needs to say how many there were and which ones.</p>
     */
    private Map<String, Object> extractionEventDetail(Long documentId, Map<String, Object> knowledge) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("documentId", documentId);
        detail.put("cardCount", sizeOf(knowledge.get("cards")));
        detail.put("claimCount", sizeOf(knowledge.get("claims")));
        // 处置明细：候选多少、真正写入多少、复用了多少、被质量闸门拒了多少。
        // 台账上的 cardCount/claimCount 是写入数；没有这份明细，一次“写入 0 条”无法区分
        // 是上游没给候选、还是候选全被拒、还是全部命中已有知识——而这三种处理完全不同。
        detail.put("cardStats", statBlock(knowledge, "card"));
        detail.put("claimStats", statBlock(knowledge, "claim"));
        detail.put("entityCount", sizeOf(knowledge.get("entities")));
        // 排队耗时：判断模型是不是这条链路的瓶颈。
        Object queued = knowledge.get("extractionQueueMs");
        if (queued != null) {
            detail.put("extractionQueueMs", queued);
        }
        detail.put("relationCount", sizeOf(knowledge.get("relations")));
        detail.put("conflictCount", sizeOf(knowledge.get("conflicts")));
        detail.put("cardCodes", codesOf(knowledge.get("cards"), "knowledge_code", 20));
        detail.put("claimCodes", codesOf(knowledge.get("claims"), "claim_code", 20));
        Object resolution = knowledge.get("entityResolution");
        if (resolution instanceof Map) {
            Map<?, ?> resolved = (Map<?, ?>) resolution;
            detail.put("entityMerged", resolved.get("merged"));
            detail.put("entityAliases", resolved.get("aliases"));
            detail.put("entityResolutionDegraded", resolved.get("degraded"));
        }
        return detail;
    }

    /**
     * 抽取结果里某一类对象的处置统计；拿不到就返回 null，而不是返回一串 0——
     * “没有统计”和“统计出来都是 0”是两件事，后者会让人以为上游一个候选都没给。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> statBlock(Map<String, Object> knowledge, String kind) {
        Object stats = knowledge.get("extractionStats");
        if (!(stats instanceof Map)) {
            return null;
        }
        Object block = ((Map<String, Object>) stats).get(kind);
        return block instanceof Map ? (Map<String, Object>) block : null;
    }

    private int sizeOf(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    private List<String> codesOf(Object items, String key, int limit) {
        List<String> codes = new ArrayList<String>();
        if (!(items instanceof List)) {
            return codes;
        }
        for (Object item : (List<?>) items) {
            if (codes.size() >= limit) {
                break;
            }
            if (item instanceof Map) {
                Object code = ((Map<?, ?>) item).get(key);
                if (code != null) {
                    codes.add(String.valueOf(code));
                }
            }
        }
        return codes;
    }

    /**
     * One source row per ingestion, whatever the bytes came from: a file keeps its original in
     * storage, a connector item only keeps the URL it can be opened at.
     */
    private KnowledgeSource createSource(Long knowledgeBaseId, IngestionMaterial material, Double authorityScore, String permissionTags)
        throws Exception {
        KnowledgeSource source = new KnowledgeSource();
        source.setSourceCode(idGenerator.next("SRC_"));
        source.setKnowledgeBaseId(knowledgeBaseId);
        source.setName(material.name);
        source.setSourceType(material.sourceType);
        source.setSourceUri(material.sourceUri);
        if (material.bytes != null) {
            // The original bytes are kept so the console can preview what was imported; a storage
            // problem only costs preview, so it never aborts an otherwise valid ingestion.
            source.setStoragePath(sourceFileStorage.store(knowledgeBaseId, source.getSourceCode(), material.name, material.bytes));
        }
        source.setAuthorityScore(authorityScore == null ? DEFAULT_AUTHORITY_SCORE : authorityScore);
        source.setPermissionTags(normalizePermissionTags(permissionTags));
        source.setStatus("ACTIVE");
        documentMapper.insertSource(source);
        documentMapper.insertSourceVersion(source.getId(), 1, material.sha256, source.getSourceUri());
        return source;
    }

    private KnowledgeDocument createDocument(KnowledgeSource source, IngestionMaterial material) {
        Long sourceVersionId = documentMapper.findSourceVersionId(source.getId(), 1);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setSourceVersionId(sourceVersionId);
        document.setDocumentCode(idGenerator.next("DOC_"));
        document.setTitle(material.name);
        document.setMimeType(material.mimeType);
        document.setParseStatus("PARSING");
        document.setStatus("PROCESSING");
        document.setCurrentStage("PARSE");
        document.setRetryCount(0);
        documentMapper.insertDocument(document);
        return document;
    }

    /**
     * Rebuilds the source aggregate for a retry: the bytes are re-read, the identity is not.
     */
    private KnowledgeSource sourceFromRetryContext(Map<String, Object> context) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(toLong(context.get("sourceId")));
        source.setKnowledgeBaseId(toLong(context.get("knowledgeBaseId")));
        source.setName(String.valueOf(context.get("sourceName")));
        source.setSourceType(String.valueOf(context.get("sourceType")));
        source.setAuthorityScore(toDouble(context.get("authorityScore")));
        Object tags = context.get("permissionTags");
        source.setPermissionTags(normalizePermissionTags(tags == null || "null".equals(tags) ? null : String.valueOf(tags)));
        return source;
    }

    private KnowledgeDocument documentFromRetryContext(Map<String, Object> context) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(toLong(context.get("id")));
        document.setDocumentCode(String.valueOf(context.get("documentCode")));
        document.setTitle(String.valueOf(context.get("title")));
        return document;
    }

    private List<Map<String, Object>> persistChunks(Long knowledgeBaseId, KnowledgeSource source, KnowledgeDocument document, List<Map<String,
        Object>> parsedChunks, int revision) throws Exception {
        List<Map<String, Object>> indexChunks = new ArrayList<Map<String, Object>>(parsedChunks.size());
        // Parent-child linking walks the heading hierarchy, so the stack has to survive across
        // batches: a top-level section in batch two still belongs to a heading in batch one.
        Map<String, Long> unitIdByHeading = new LinkedHashMap<String, Long>();
        // Parser unit ids (P1, U2, ...) map to database ids, so a parent-child split can
        // link a child to the section the parser emitted immediately before it.
        Map<String, Long> idByParserUnitId = new LinkedHashMap<String, Long>();
        for (int start = 0; start < parsedChunks.size(); start += DB_BATCH_SIZE) {
            int end = Math.min(start + DB_BATCH_SIZE, parsedChunks.size());
            List<Map<String, Object>> batch = parsedChunks.subList(start, end);
            persistChunkBatch(knowledgeBaseId, source, document, batch, indexChunks, revision, unitIdByHeading, idByParserUnitId);
        }
        return indexChunks;
    }

    private void persistChunkBatch(Long knowledgeBaseId, KnowledgeSource source, KnowledgeDocument document, List<Map<String, Object>> batch,
        List<Map<String, Object>> output, int revision, Map<String, Long> unitIdByHeading, Map<String, Long> idByParserUnitId) throws Exception {
        List<Map<String, Object>> contentRows = new ArrayList<Map<String, Object>>(batch.size());
        List<String> unitCodes = new ArrayList<String>(batch.size());
        List<Map<String, Object>> copiedChunks = new ArrayList<Map<String, Object>>(batch.size());
        for (Map<String, Object> parsedChunk : batch) {
            Map<String, Object> chunk = new LinkedHashMap<String, Object>(parsedChunk);
            String unitCode = idGenerator.next("CU_");
            Integer pageNo = toInteger(chunk.get("page_no"));
            String headingPath = nullableString(chunk.get("heading_path"));
            String content = nullableString(chunk.get("content"));
            // Chunk metadata is what makes a hit explainable later: the strategy that produced the
            // chunk, its page/character span, its size in tokens, and the source version it came
            // from. Without them a citation can point at a chunk but never explain its boundaries.
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("documentId", document.getId());
            row.put("unitCode", unitCode);
            row.put("parentId", null);
            row.put("headingPath", headingPath);
            row.put("chunkType", nullableString(chunk.get("chunk_type"), "PARAGRAPH"));
            row.put("pageNo", pageNo);
            row.put("pageEnd", toInteger(chunk.get("page_end")) == null ? pageNo : toInteger(chunk.get("page_end")));
            row.put("charStart", toInteger(chunk.get("char_start")));
            row.put("charEnd", toInteger(chunk.get("char_end")));
            row.put("content", content);
            row.put("contentHash", nullableString(chunk.get("content_hash")));
            row.put("tokenCount", toInteger(chunk.get("token_count")) == null ? estimateTokenCount(content) : toInteger(chunk.get("token_count")));
            row.put("aclTags", source.getPermissionTags());
            row.put("sourceVersion", 1);
            row.put("revision", revision);
            contentRows.add(row);
            unitCodes.add(unitCode);
            chunk.put("_unit_code", unitCode);
            copiedChunks.add(chunk);
        }
        documentMapper.batchInsertContentUnits(contentRows);
        List<Map<String, Object>> ids = documentMapper.findContentUnitIdsByCodes(unitCodes);
        Map<String, Long> idByCode = new LinkedHashMap<String, Long>();
        for (Map<String, Object> row : ids) {
            idByCode.put(String.valueOf(row.get("unitCode")), Long.valueOf(String.valueOf(row.get("id"))));
        }
        List<Map<String, Object>> evidenceRows = new ArrayList<Map<String, Object>>(copiedChunks.size());
        List<Map<String, Object>> parentRows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> chunk : copiedChunks) {
            String unitCode = String.valueOf(chunk.remove("_unit_code"));
            Long contentUnitId = idByCode.get(unitCode);
            if (contentUnitId == null) {
                throw new IllegalStateException("ContentUnit ID not found for unitCode=" + unitCode);
            }
            String headingPath = nullableString(chunk.get("heading_path"));
            Long parentId = parentIdFor(chunk, headingPath, unitIdByHeading, idByParserUnitId);
            if (parentId != null) {
                Map<String, Object> parentRow = new LinkedHashMap<String, Object>();
                parentRow.put("unitCode", unitCode);
                parentRow.put("parentId", parentId);
                parentRows.add(parentRow);
            }
            unitIdByHeading.put(headingPath, contentUnitId);
            String parserUnitId = nullableString(chunk.get("unit_id"));
            if (parserUnitId != null && !parserUnitId.isEmpty()) {
                idByParserUnitId.put(parserUnitId, contentUnitId);
            }
            Integer pageNo = toInteger(chunk.get("page_no"));
            Map<String, Object> location = new LinkedHashMap<String, Object>();
            location.put("pageNo", pageNo);
            location.put("headingPath", headingPath);
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            String evidenceCode = idGenerator.next("EVD_");
            evidence.put("evidenceCode", evidenceCode);
            evidence.put("knowledgeBaseId", knowledgeBaseId);
            evidence.put("contentUnitId", contentUnitId);
            evidence.put("sourceId", source.getId());
            evidence.put("quoteText", nullableString(chunk.get("content")));
            evidence.put("locationJson", objectMapper.writeValueAsString(location));
            evidence.put("authorityScore", source.getAuthorityScore());
            evidenceRows.add(evidence);
            chunk.put("content_unit_id", contentUnitId);
            chunk.put("chunk_type", nullableString(chunk.get("chunk_type"), "PARAGRAPH"));
            chunk.put("token_count", chunk.get("token_count") == null ? estimateTokenCount(nullableString(chunk.get("content"))) :
                toInteger(chunk.get("token_count")));
            chunk.put("revision", revision);
            chunk.put("evidence_code", evidenceCode);
            chunk.put("evidence_hash", nullableString(chunk.get("content_hash")));
            chunk.put("source_version", 1);
            output.add(chunk);
        }
        evidenceMapper.batchInsertEvidence(evidenceRows);
        if (!parentRows.isEmpty()) {
            documentMapper.batchUpdateContentUnitParents(parentRows);
        }
    }

    /**
     * Nearest ancestor of a heading path, so a paragraph chunk can point at its section chunk.
     * This is what the design calls parent-child chunking: retrieval hits the precise chunk, then
     * expands to the parent for enough context to answer.
     */
    /**
     * Parent of a chunk: the parser knows it exactly under parent-child chunking, the
     * heading hierarchy is the fallback when the parser did not say.
     */
    private Long parentIdFor(Map<String, Object> chunk, String headingPath, Map<String, Long> unitIdByHeading, Map<String, Long> idByParserUnitId) {
        String explicit = nullableString(chunk.get("parent_unit_id"));
        if (explicit != null && !explicit.isEmpty()) {
            Long parentId = idByParserUnitId.get(explicit);
            if (parentId != null) {
                return parentId;
            }
        }
        return parentIdFor(headingPath, unitIdByHeading);
    }

    private Long parentIdFor(String headingPath, Map<String, Long> unitIdByHeading) {
        if (headingPath == null || headingPath.isEmpty()) {
            return null;
        }
        String best = null;
        for (String candidate : unitIdByHeading.keySet()) {
            if (candidate == null || candidate.isEmpty() || candidate.equals(headingPath)) {
                continue;
            }
            if (!headingPath.startsWith(candidate)) {
                continue;
            }
            if (best == null || candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best == null ? null : unitIdByHeading.get(best);
    }

    private Map<String, Object> buildIndexReference(Long kb, KnowledgeDocument document) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("documentId", document.getId());
        return payload;
    }

    private Map<String, Object> buildIndexPayload(Long kb, KnowledgeSource source, KnowledgeDocument document, List<Map<String, Object>> chunks) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("documentId", document.getId());
        payload.put("sourceName", source.getName());
        payload.put("sourceUri", source.getSourceUri());
        payload.put("chunks", chunks);
        payload.put("permissionTags", source.getPermissionTags());
        return payload;
    }

    /**
     * Payload for the standard-document store. Only document-level metadata is shipped
     * alongside the units: blocks are an intermediate artefact and would bloat the
     * outbox row without adding retrieval value.
     */
    private Map<String, Object> buildStandardPayload(Long kb, KnowledgeSource source, KnowledgeDocument document, Map<String, Object> standard,
        List<Map<String, Object>> units) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("documentId", document.getId());
        payload.put("sourceId", source.getId());
        payload.put("sourceName", source.getName());
        payload.put("sourceUri", source.getSourceUri());
        payload.put("units", units);
        payload.put("permissionTags", source.getPermissionTags());
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        for (String key : new String[] { "doc_id", "title", "source_format", "language", "sha256", "page_count" }) {
            metadata.put(key, standard.get(key));
        }
        metadata.put("stats", standard.get("stats"));
        payload.put("standard", metadata);
        return payload;
    }

    private Map<String, Object> buildExtractionPayload(Long kb, KnowledgeSource source, KnowledgeDocument document, Map<String, Object> parsed,
        List<Map<String, Object>> chunks, List<Map<String, Object>> standardUnits) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("sourceId", source.getId());
        payload.put("documentId", document.getId());
        payload.put("text", parsed.get("text"));
        payload.put("chunks", chunks);
        // Extraction reads normalized units first; chunks only carry provenance.
        payload.put("units", standardUnits);
        payload.put("permissionTags", source.getPermissionTags());
        return payload;
    }

    /**
     * Compact view of the canonical parse result surfaced to the ingestion UI.
     */
    private Map<String, Object> standardSummary(Map<String, Object> standard) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("docId", standard.get("doc_id"));
        summary.put("title", standard.get("title"));
        summary.put("sourceFormat", standard.get("source_format"));
        summary.put("language", standard.get("language"));
        summary.put("pageCount", standard.get("page_count"));
        summary.put("stats", standard.get("stats"));
        return summary;
    }

    private String normalizePermissionTags(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "[]";
        }
        try {
            if (raw.trim().startsWith("[")) {
                List<?> values = objectMapper.readValue(raw, List.class);
                return objectMapper.writeValueAsString(values);
            }
            List<String> values = new ArrayList<String>();
            for (String value : raw.split(",")) {
                if (!value.trim().isEmpty()) {
                    values.add(value.trim());
                }
            }
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid permissionTags", ex);
        }
    }

    private Integer toInteger(Object value) {
        return value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    private String nullableString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullableString(Object value, String fallback) {
        String text = nullableString(value);
        return text.isEmpty() ? fallback : text;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private int toInt(Object value) {
        Long parsed = toLong(value);
        return parsed == null ? 0 : parsed.intValue();
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return DEFAULT_AUTHORITY_SCORE;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return DEFAULT_AUTHORITY_SCORE;
        }
    }

    /**
     * Rough token estimate so a chunk without parser-provided counts still has a usable size.
     */
    private int estimateTokenCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int length = content.length();
        return (length + 3) / 4;
    }

    private String mimeFromExtension(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".doc")) {
            return "application/msword";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain";
    }

    private String abbreviate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * What is being ingested, independent of where it came from. An upload carries bytes and a
     * filename; a connector item carries a name, a URL and text already normalized into the parse
     * model. Everything after this point cannot tell them apart.
     */
    private static final class IngestionMaterial {

        private final String name;

        private final String sourceUri;

        private final String sourceType;

        private final String mimeType;

        private final String sha256;

        private final byte[] bytes;

        private final long size;

        private IngestionMaterial(String name, String sourceUri, String sourceType, String mimeType, String sha256, byte[] bytes, long size) {
            this.name = name;
            this.sourceUri = sourceUri;
            this.sourceType = sourceType;
            this.mimeType = mimeType;
            this.sha256 = sha256;
            this.bytes = bytes;
            this.size = size;
        }
    }

    private static final class IngestionState {

        private final KnowledgeSource source;

        private final KnowledgeDocument document;

        private final List<Map<String, Object>> indexChunks;

        private final Map<String, Object> indexEvent;

        private final Map<String, Object> standardEvent;

        private final int revision;

        private IngestionState(KnowledgeSource source, KnowledgeDocument document, List<Map<String, Object>> indexChunks, Map<String, Object>
            indexEvent, Map<String, Object> standardEvent, int revision) {
            this.source = source;
            this.document = document;
            this.indexChunks = indexChunks;
            this.indexEvent = indexEvent;
            this.standardEvent = standardEvent;
            this.revision = revision;
        }
    }
}
