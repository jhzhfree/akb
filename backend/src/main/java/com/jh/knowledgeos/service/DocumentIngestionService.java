package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.EventTaskContext;
import com.jh.knowledgeos.common.InMemoryMultipartFile;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.KnowledgeDocument;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

    private final KnowledgeMapper mapper;
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
    private final TaskCenterService taskCenterService;

    public DocumentIngestionService(
            KnowledgeMapper mapper,
            AiClient aiClient,
            KnowledgeExtractionService extractionService,
            ObjectMapper objectMapper,
            IdGenerator idGenerator,
            TransactionRunner transactionRunner,
            IndexOutboxService outboxService,
            IngestionObservabilityService observabilityService,
            SourceFileStorageService sourceFileStorage,
            PlatformEventPublisher publisher,
            ChunkPolicyService chunkPolicyService,
            @Lazy TaskCenterService taskCenterService) {
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
        this.chunkPolicyService = chunkPolicyService;
        this.taskCenterService = taskCenterService;
    }

    /**
     * Parses an uploaded source outside the DB transaction, persists its authoritative
     * source/document/chunk/evidence state, enqueues indexing, then triggers extraction.
     */
    public Map<String, Object> upload(Long knowledgeBaseId, MultipartFile file, Double authorityScore, String permissionTags)
            throws Exception {
        String name = file == null ? "" : String.valueOf(file.getOriginalFilename());
        // Ingestion is the longest job on the platform and the one operators are asked about
        // most, so it is registered in the task centre before a single byte is parsed: a crash
        // mid-way leaves a failed task instead of a silent hole.
        return tracked(knowledgeBaseId, "文档接入：" + name, null,
                () -> ingest(knowledgeBaseId, file, authorityScore, permissionTags, null));
    }

    /** Runs work as a tracked task: the ledger is updated whatever the outcome. */
    private Map<String, Object> tracked(Long knowledgeBaseId, String taskName, String targetCode,
            Callable<Map<String, Object>> work) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", knowledgeBaseId);
        payload.put("documentId", targetCode);
        Map<String, Object> task = taskCenterService.enqueue(knowledgeBaseId,
                TaskCenterService.TYPE_INGESTION, taskName, "DOCUMENT", targetCode,
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
        Map<String, Object> context = mapper.findDocumentRetryContext(documentId);
        if (context == null) throw new IllegalArgumentException("文档不存在：" + documentId);
        String status = String.valueOf(context.get("status"));
        if (!"FAILED".equals(status) && !"DIRTY".equals(status)) {
            throw new IllegalStateException("仅 FAILED / DIRTY 文档可重试，当前状态：" + status);
        }
        Path stored = sourceFileStorage.resolve(String.valueOf(context.get("storagePath")));
        if (stored == null || !Files.isReadable(stored)) {
            int retries = toInt(context.get("retryCount")) + 1;
            mapper.updateDocumentState(documentId, "FAILED", "RETRY", "原始文件缺失，无法重跑", retries);
            throw new IllegalStateException("原始文件缺失，无法重跑该文档");
        }
        final InMemoryMultipartFile file = InMemoryMultipartFile.of(
                stored,
                String.valueOf(context.get("sourceName")),
                mimeFromExtension(String.valueOf(context.get("sourceName"))));
        final Long knowledgeBaseId = toLong(context.get("knowledgeBaseId"));
        return tracked(knowledgeBaseId, "文档重跑：" + documentId, String.valueOf(documentId),
                () -> ingest(
                        knowledgeBaseId,
                        file,
                        toDouble(context.get("authorityScore")),
                        String.valueOf(context.get("permissionTags")),
                        context));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ingest(
            Long knowledgeBaseId,
            MultipartFile file,
            Double authorityScore,
            String permissionTags,
            Map<String, Object> retryContext) throws Exception {
        Long retryDocumentId = retryContext == null ? null : toLong(retryContext.get("id"));
        int retryCount = retryContext == null ? 0 : toInt(retryContext.get("retryCount")) + 1;
        com.jh.knowledgeos.domain.IngestionRun run =
                observabilityService.start(knowledgeBaseId, file.getOriginalFilename());
        // Mutable holders: a failure has to record the stage it died in, and the document id only
        // exists once the persist transaction ran, but the catch block needs both.
        final String[] stageHolder = new String[]{"PARSE"};
        final Long[] documentIdHolder = new Long[]{retryDocumentId};
        try {
            publisher.emitUser(PlatformEventTypes.INGESTION_DOCUMENT_RECEIVED, knowledgeBaseId,
                    PlatformEventPublisher.SOURCE_UI, "INGESTION_RUN", run.getRunCode(),
                    "文档接收开始解析：" + file.getOriginalFilename(),
                    receivedDetail(run, file, authorityScore),
                    taskContext(run.getRunCode(), retryDocumentId, null, retryCount));
            if (retryDocumentId != null) {
                mapper.updateDocumentState(retryDocumentId, "RETRYING", "PARSE", null, retryCount);
            }
            observabilityService.event(run.getId(), "PARSE_STARTED", "PARSE", "RUNNING",
                    "开始解析文档", null);
            // The knowledge base owns its chunking policy: the parser only applies it, so a
            // manual can be re-cut by section while a report stays window-based.
            Map<String, Object> parsed =
                    aiClient.parse(file, chunkPolicyService.policyFor(knowledgeBaseId));
            List<Map<String, Object>> parsedChunks =
                    parsed.get("chunks") instanceof List
                            ? (List<Map<String, Object>>) parsed.get("chunks")
                            : new ArrayList<Map<String, Object>>();
            // The parser returns one canonical model for every source format; chunks are the
            // normalized units of that model, already free of layout-only content.
            Map<String, Object> standard = parsed.get("standard") instanceof Map
                    ? (Map<String, Object>) parsed.get("standard")
                    : new LinkedHashMap<String, Object>();
            observabilityService.event(run.getId(), "PARSE_COMPLETED", "PARSE", "SUCCESS",
                    "文档解析完成，共 " + parsedChunks.size() + " 个标准单元", null);

            observabilityService.event(run.getId(), "PERSIST_STARTED", "PERSIST", "RUNNING",
                    "写入权威知识数据", null);
            // Retried on deadlock: a batch runs several documents through this write set at once,
            // and every INSERT here is rolled back cleanly by the database, so redoing the whole
            // transaction is a no-op rather than a duplicate. The expensive AI calls sit outside
            // it precisely so that a retry never pays for them twice.
            IngestionState state = transactionRunner.retryingOnDeadlock(() -> {
                try {
                    KnowledgeSource source;
                    KnowledgeDocument document;
                    if (retryDocumentId == null) {
                        source = createSource(knowledgeBaseId, file, parsed, authorityScore, permissionTags);
                        document = createDocument(source, file);
                    } else {
                        // A retry keeps the original source and document identity: the chunks are
                        // what gets a new revision, so every Evidence row stays resolvable.
                        source = sourceFromRetryContext(retryContext);
                        document = documentFromRetryContext(retryContext);
                        mapper.updateDocumentState(document.getId(), "PROCESSING", "CHUNK", null, retryCount);
                        mapper.supersedeDocumentChunks(document.getId());
                    }
                    documentIdHolder[0] = document.getId();
                    int revision = retryDocumentId == null ? 1 : mapper.nextChunkRevision(document.getId());
                    List<Map<String, Object>> indexChunks = persistChunks(
                            knowledgeBaseId, source, document, parsedChunks, revision);
                    mapper.updateDocumentParsed(
                            document.getId(), String.valueOf(parsed.get("text")), indexChunks.size(), "PARSED");
                    Map<String, Object> indexEvent = outboxService.enqueue(
                            knowledgeBaseId, "DOCUMENT",
                            "KB:" + knowledgeBaseId + ":DOCUMENT:" + document.getId(),
                            "DOCUMENT_CHUNKS_UPSERT", AiApiPaths.INDEX_CHUNKS,
                            buildIndexReference(knowledgeBaseId, document),
                            "DOCUMENT_CHUNKS:" + document.getId() + ":" + revision);
                    // The normalized standard document is stored as its own retrieval view: it is
                    // the material card generation reads from, so it is persisted in the same
                    // transaction that makes the document durable.
                    Map<String, Object> standardEvent = outboxService.enqueue(
                            knowledgeBaseId, "DOCUMENT",
                            "KB:" + knowledgeBaseId + ":STANDARD:" + document.getId(),
                            "DOCUMENT_STANDARD_UPSERT", AiApiPaths.INDEX_STANDARD_DOCUMENT,
                            buildStandardPayload(knowledgeBaseId, source, document, standard, parsedChunks),
                            "DOCUMENT_STANDARD:" + document.getId() + ":" + revision);
                    return new IngestionState(source, document, indexChunks, indexEvent, standardEvent, revision);
                } catch (Exception ex) {
                    throw new IllegalStateException("Persist parsed document failed", ex);
                }
            });
            stageHolder[0] = "CHUNK";
            publisher.emitUser(PlatformEventTypes.CHUNK_CHANGED, knowledgeBaseId,
                    PlatformEventPublisher.SOURCE_UI, "DOCUMENT", String.valueOf(state.document.getId()),
                    "文档切片完成：" + state.indexChunks.size() + " 个 Chunk（revision " + state.revision + "）",
                    chunkDetail(state),
                    taskContext(run.getRunCode(), state.document.getId(), String.valueOf(state.revision), retryCount));
            observabilityService.event(run.getId(), "PERSIST_COMPLETED", "PERSIST", "SUCCESS",
                    "Source / Document / ContentUnit / Evidence 已保存", null);
            observabilityService.event(run.getId(), "INDEX_EVENT_CREATED", "INDEX", "SUCCESS",
                    "已创建 Transactional Outbox 索引事件", state.indexEvent);
            observabilityService.event(run.getId(), "STANDARD_EVENT_CREATED", "STANDARD", "SUCCESS",
                    "标准化数据已入队写入 Elasticsearch", state.standardEvent);
            observabilityService.event(run.getId(), "EXTRACTION_STARTED", "EXTRACT", "RUNNING",
                    "开始抽取 Knowledge Card / Claim / Entity / Relation", null);
            stageHolder[0] = "EXTRACT";
            mapper.updateDocumentState(state.document.getId(), "PROCESSING", "EXTRACT", null, retryCount);

            Map<String, Object> knowledgeResult = extractionService.extract(
                    buildExtractionPayload(knowledgeBaseId, state.source, state.document, parsed,
                            state.indexChunks, parsedChunks));
            int cardCount = resultCount(knowledgeResult, "cards");
            int claimCount = resultCount(knowledgeResult, "claims");
            observabilityService.event(run.getId(), "EXTRACTION_COMPLETED", "EXTRACT", "SUCCESS",
                    "候选知识抽取完成",
                    extractionEventDetail(state.document.getId(), knowledgeResult));
            observabilityService.complete(run.getId(), state.indexChunks.size(), cardCount, claimCount);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("runId", run.getId());
            result.put("runCode", run.getRunCode());
            result.put("sourceId", state.source.getId());
            result.put("documentId", state.document.getId());
            result.put("filename", file.getOriginalFilename());
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
            mapper.updateDocumentState(state.document.getId(), "READY", "READY", null, retryCount);
            publisher.emitUser(PlatformEventTypes.INGESTION_COMPLETED, knowledgeBaseId,
                    PlatformEventPublisher.SOURCE_UI, "INGESTION_RUN", run.getRunCode(),
                    "文档摄入完成：" + file.getOriginalFilename(),
                    completedDetail(run, file, state, cardCount, claimCount, knowledgeResult),
                    taskContext(run.getRunCode(), state.document.getId(),
                            String.valueOf(state.revision), retryCount));
            return result;
        } catch (Exception ex) {
            observabilityService.fail(run.getId(), "FAILED", ex);
            Long failedDocument = documentIdHolder[0];
            if (failedDocument != null) {
                mapper.updateDocumentState(failedDocument, "FAILED", stageHolder[0],
                        abbreviate(ex.getMessage(), 900), retryCount);
            }
            Map<String, Object> detail = receivedDetail(run, file, authorityScore);
            detail.put("stage", stageHolder[0]);
            detail.put("documentId", failedDocument);
            detail.put("retryCount", retryCount);
            detail.put("error", ex.getMessage());
            publisher.emitUser(PlatformEventTypes.INGESTION_FAILED, knowledgeBaseId,
                    PlatformEventPublisher.SOURCE_UI, "INGESTION_RUN",
                    run == null ? null : run.getRunCode(),
                    "文档摄入失败：" + file.getOriginalFilename(), detail,
                    taskContext(run == null ? null : run.getRunCode(), failedDocument, null, retryCount));
            throw ex;
        }
    }

    /** Task identity every ingestion event carries, so a subscriber can join events to one run. */
    private EventTaskContext taskContext(String taskId, Long objectId, String objectVersion, int retryCount) {
        return EventTaskContext.task(taskId)
                .object(objectId == null ? null : String.valueOf(objectId), objectVersion)
                .retryCount(retryCount)
                .build();
    }

    private Map<String, Object> chunkDetail(IngestionState state) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("documentId", state.document.getId());
        detail.put("chunkCount", state.indexChunks.size());
        detail.put("revision", state.revision);
        return detail;
    }

    /** Shared ingestion detail so the received / completed / failed events read the same way. */
    private Map<String, Object> receivedDetail(
            com.jh.knowledgeos.domain.IngestionRun run, MultipartFile file, Double authorityScore) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("runCode", run == null ? null : run.getRunCode());
        detail.put("filename", file.getOriginalFilename());
        detail.put("size", file.getSize());
        detail.put("contentType", file.getContentType());
        detail.put("authorityScore", authorityScore);
        return detail;
    }

    private Map<String, Object> completedDetail(
            com.jh.knowledgeos.domain.IngestionRun run, MultipartFile file, IngestionState state,
            int cardCount, int claimCount, Map<String, Object> knowledgeResult) {
        Map<String, Object> detail = receivedDetail(run, file, null);
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
        if (value instanceof List) return ((List<?>) value).size();
        if (value instanceof Number) return ((Number) value).intValue();
        Object count = result.get(key + "Count");
        return count instanceof Number ? ((Number) count).intValue() : 0;
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
        detail.put("entityCount", sizeOf(knowledge.get("entities")));
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

    private int sizeOf(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    private List<String> codesOf(Object items, String key, int limit) {
        List<String> codes = new ArrayList<String>();
        if (!(items instanceof List)) return codes;
        for (Object item : (List<?>) items) {
            if (codes.size() >= limit) break;
            if (item instanceof Map) {
                Object code = ((Map<?, ?>) item).get(key);
                if (code != null) codes.add(String.valueOf(code));
            }
        }
        return codes;
    }

    private KnowledgeSource createSource(
            Long knowledgeBaseId,
            MultipartFile file,
            Map<String, Object> parsed,
            Double authorityScore,
            String permissionTags) throws Exception {
        KnowledgeSource source = new KnowledgeSource();
        source.setSourceCode(idGenerator.next("SRC_"));
        source.setKnowledgeBaseId(knowledgeBaseId);
        source.setName(file.getOriginalFilename());
        source.setSourceType(String.valueOf(parsed.get("extension")));
        source.setSourceUri("upload://" + file.getOriginalFilename());
        // The original bytes are kept so the console can preview what was imported; a storage
        // problem only costs preview, so it never aborts an otherwise valid ingestion.
        source.setStoragePath(sourceFileStorage.store(
                knowledgeBaseId, source.getSourceCode(), file.getOriginalFilename(), file.getBytes()));
        source.setAuthorityScore(authorityScore == null ? DEFAULT_AUTHORITY_SCORE : authorityScore);
        source.setPermissionTags(normalizePermissionTags(permissionTags));
        source.setStatus("ACTIVE");
        mapper.insertSource(source);
        mapper.insertSourceVersion(
                source.getId(),
                1,
                String.valueOf(parsed.get("sha256")),
                source.getSourceUri());
        return source;
    }

    private KnowledgeDocument createDocument(KnowledgeSource source, MultipartFile file) {
        Long sourceVersionId = mapper.findSourceVersionId(source.getId(), 1);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setSourceVersionId(sourceVersionId);
        document.setDocumentCode(idGenerator.next("DOC_"));
        document.setTitle(file.getOriginalFilename());
        document.setMimeType(file.getContentType());
        document.setParseStatus("PARSING");
        document.setStatus("PROCESSING");
        document.setCurrentStage("PARSE");
        document.setRetryCount(0);
        mapper.insertDocument(document);
        return document;
    }

    /** Rebuilds the source aggregate for a retry: the bytes are re-read, the identity is not. */
    private KnowledgeSource sourceFromRetryContext(Map<String, Object> context) {
        KnowledgeSource source = new KnowledgeSource();
        source.setId(toLong(context.get("sourceId")));
        source.setKnowledgeBaseId(toLong(context.get("knowledgeBaseId")));
        source.setName(String.valueOf(context.get("sourceName")));
        source.setSourceType(String.valueOf(context.get("sourceType")));
        source.setAuthorityScore(toDouble(context.get("authorityScore")));
        Object tags = context.get("permissionTags");
        source.setPermissionTags(normalizePermissionTags(
                tags == null || "null".equals(tags) ? null : String.valueOf(tags)));
        return source;
    }

    private KnowledgeDocument documentFromRetryContext(Map<String, Object> context) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(toLong(context.get("id")));
        document.setDocumentCode(String.valueOf(context.get("documentCode")));
        document.setTitle(String.valueOf(context.get("title")));
        return document;
    }

    private List<Map<String, Object>> persistChunks(
            Long knowledgeBaseId,
            KnowledgeSource source,
            KnowledgeDocument document,
            List<Map<String, Object>> parsedChunks,
            int revision) throws Exception {
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
            persistChunkBatch(knowledgeBaseId, source, document, batch, indexChunks, revision,
                    unitIdByHeading, idByParserUnitId);
        }
        return indexChunks;
    }

    private void persistChunkBatch(
            Long knowledgeBaseId,
            KnowledgeSource source,
            KnowledgeDocument document,
            List<Map<String, Object>> batch,
            List<Map<String, Object>> output,
            int revision,
            Map<String, Long> unitIdByHeading,
            Map<String, Long> idByParserUnitId) throws Exception {
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
            row.put("tokenCount", toInteger(chunk.get("token_count")) == null
                    ? estimateTokenCount(content)
                    : toInteger(chunk.get("token_count")));
            row.put("aclTags", source.getPermissionTags());
            row.put("sourceVersion", 1);
            row.put("revision", revision);
            contentRows.add(row);
            unitCodes.add(unitCode);

            chunk.put("_unit_code", unitCode);
            copiedChunks.add(chunk);
        }

        mapper.batchInsertContentUnits(contentRows);
        List<Map<String, Object>> ids = mapper.findContentUnitIdsByCodes(unitCodes);
        Map<String, Long> idByCode = new LinkedHashMap<String, Long>();
        for (Map<String, Object> row : ids) {
            idByCode.put(
                    String.valueOf(row.get("unitCode")),
                    Long.valueOf(String.valueOf(row.get("id"))));
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
            chunk.put("token_count", chunk.get("token_count") == null
                    ? estimateTokenCount(nullableString(chunk.get("content")))
                    : toInteger(chunk.get("token_count")));
            chunk.put("revision", revision);
            chunk.put("evidence_code", evidenceCode);
            chunk.put("evidence_hash", nullableString(chunk.get("content_hash")));
            chunk.put("source_version", 1);
            output.add(chunk);
        }
        mapper.batchInsertEvidence(evidenceRows);
        if (!parentRows.isEmpty()) {
            mapper.batchUpdateContentUnitParents(parentRows);
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
    private Long parentIdFor(Map<String, Object> chunk, String headingPath,
            Map<String, Long> unitIdByHeading, Map<String, Long> idByParserUnitId) {
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
        if (headingPath == null || headingPath.isEmpty()) return null;
        String best = null;
        for (String candidate : unitIdByHeading.keySet()) {
            if (candidate == null || candidate.isEmpty() || candidate.equals(headingPath)) continue;
            if (!headingPath.startsWith(candidate)) continue;
            if (best == null || candidate.length() > best.length()) best = candidate;
        }
        return best == null ? null : unitIdByHeading.get(best);
    }

    private Map<String, Object> buildIndexReference(Long kb, KnowledgeDocument document) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("documentId", document.getId());
        return payload;
    }

    private Map<String, Object> buildIndexPayload(
            Long kb,
            KnowledgeSource source,
            KnowledgeDocument document,
            List<Map<String, Object>> chunks) {
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
    private Map<String, Object> buildStandardPayload(
            Long kb,
            KnowledgeSource source,
            KnowledgeDocument document,
            Map<String, Object> standard,
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
        for (String key : new String[] {"doc_id", "title", "source_format", "language", "sha256", "page_count"}) {
            metadata.put(key, standard.get(key));
        }
        metadata.put("stats", standard.get("stats"));
        payload.put("standard", metadata);
        return payload;
    }

    private Map<String, Object> buildExtractionPayload(
            Long kb,
            KnowledgeSource source,
            KnowledgeDocument document,
            Map<String, Object> parsed,
            List<Map<String, Object>> chunks,
            List<Map<String, Object>> standardUnits) {
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

    /** Compact view of the canonical parse result surfaced to the ingestion UI. */
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
        if (raw == null || raw.trim().isEmpty()) return "[]";
        try {
            if (raw.trim().startsWith("[")) {
                List<?> values = objectMapper.readValue(raw, List.class);
                return objectMapper.writeValueAsString(values);
            }
            List<String> values = new ArrayList<String>();
            for (String value : raw.split(",")) {
                if (!value.trim().isEmpty()) values.add(value.trim());
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
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private int toInt(Object value) {
        Long parsed = toLong(value);
        return parsed == null ? 0 : parsed.intValue();
    }

    private Double toDouble(Object value) {
        if (value == null) return DEFAULT_AUTHORITY_SCORE;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return DEFAULT_AUTHORITY_SCORE;
        }
    }

    /** Rough token estimate so a chunk without parser-provided counts still has a usable size. */
    private int estimateTokenCount(String content) {
        if (content == null || content.isEmpty()) return 0;
        int length = content.length();
        return (length + 3) / 4;
    }

    private String mimeFromExtension(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".md")) return "text/markdown";
        return "text/plain";
    }

    private String abbreviate(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static final class IngestionState {
        private final KnowledgeSource source;
        private final KnowledgeDocument document;
        private final List<Map<String, Object>> indexChunks;
        private final Map<String, Object> indexEvent;
        private final Map<String, Object> standardEvent;
        private final int revision;

        private IngestionState(
                KnowledgeSource source,
                KnowledgeDocument document,
                List<Map<String, Object>> indexChunks,
                Map<String, Object> indexEvent,
                Map<String, Object> standardEvent,
                int revision) {
            this.source = source;
            this.document = document;
            this.indexChunks = indexChunks;
            this.indexEvent = indexEvent;
            this.standardEvent = standardEvent;
            this.revision = revision;
        }
    }

}
