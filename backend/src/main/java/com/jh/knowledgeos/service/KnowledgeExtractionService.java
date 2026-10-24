package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.KnowledgeStatus;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.CardClaimMapper;
import com.jh.knowledgeos.mapper.IndexMapper;
import com.jh.knowledgeos.mapper.GraphMapper;
import com.jh.knowledgeos.mapper.GovernanceMapper;
import com.jh.knowledgeos.mapper.EvidenceMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

/**
 * Application service that converts parsed evidence into cards/claims and enqueues their retrieval-index updates.
 */
@Service
public class KnowledgeExtractionService {

    /** 有效正文短于这个长度、又看不出信息要素时，降级待审而不是当成空壳丢弃。 */
    private static final int LOW_INFORMATION_LENGTH = 16;

    /** 抽取版本：抽取逻辑变更时递增，让重跑能产出新键，而不是一直命中旧结果。 */
    private static final String EXTRACTOR_VERSION = "E3";

    /** 重复关系在待裁决项里的类型；与语义冲突共用一张表，靠类型区分。 */
    private static final String DUPLICATE_CONFLICT_TYPE = "DUPLICATE";

    private final KnowledgeMapper mapper;

    private final CardClaimMapper cardClaimMapper;

    private final EvidenceMapper evidenceMapper;

    private final GovernanceMapper governanceMapper;

    private final GraphMapper graphMapper;

    private final IndexMapper indexMapper;


    private final AiClient aiClient;

    private final ObjectMapper objectMapper;

    private final IdGenerator idGenerator;

    private final TransactionRunner transactionRunner;

    private final IndexOutboxService outboxService;

    private final EntityResolutionService entityResolutionService;

    private final PlatformEventPublisher publisher;

    private final KbScenarioService scenarios;

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeExtractionService.class);

    private final DocumentMapper documentMapper;

    private final ExtractionModelGate modelGate;

    private final DeterministicExtractionVerifier deterministicVerifier = new DeterministicExtractionVerifier();

    public KnowledgeExtractionService(KnowledgeMapper mapper, AiClient aiClient, ObjectMapper objectMapper, IdGenerator idGenerator, TransactionRunner
        transactionRunner, IndexOutboxService outboxService, EntityResolutionService entityResolutionService, PlatformEventPublisher publisher,
        EvidenceMapper evidenceMapper, GovernanceMapper governanceMapper, GraphMapper graphMapper, IndexMapper indexMapper, CardClaimMapper cardClaimMapper,
        KbScenarioService scenarios, DocumentMapper documentMapper, ExtractionModelGate modelGate) {
        this.cardClaimMapper = cardClaimMapper;
        this.evidenceMapper = evidenceMapper;
        this.governanceMapper = governanceMapper;
        this.graphMapper = graphMapper;
        this.indexMapper = indexMapper;
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
        this.entityResolutionService = entityResolutionService;
        this.publisher = publisher;
        this.scenarios = scenarios;
        this.documentMapper = documentMapper;
        this.modelGate = modelGate;
    }

    /** Graph step skipped because the library's scenario builds no graph. */
    private Map<String, Object> skippedGraph() {
        Map<String, Object> graph = new LinkedHashMap<String, Object>();
        graph.put("skipped", "GRAPH_DISABLED");
        graph.put("entities", 0);
        graph.put("relations", 0);
        return graph;
    }

    /** Entity resolution skipped because the scenario does not want it. */
    private Map<String, Object> skippedResolution() {
        Map<String, Object> resolution = new LinkedHashMap<String, Object>();
        resolution.put("skipped", "ENTITY_RESOLUTION_DISABLED");
        resolution.put("merged", 0);
        return resolution;
    }

    /**
     * Runs AI extraction outside the database transaction, persists cards/claims in a
     * short transaction, and enqueues rebuildable retrieval-view updates.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extract(Map<String, Object> request) {
        Long knowledgeBaseId = Long.valueOf(String.valueOf(request.get("knowledgeBaseId")));
        // The scenario travels with the extraction request because generation is part of the
        // template too: which vocabulary names the subject of a parsed row and what the model
        // is told to look for differ between a runbook and a policy handbook. Without it the
        // template only tunes chunking and recall, and every library is generated the same way.
        Map<String, Object> payload = new LinkedHashMap<String, Object>(request);
        payload.put("scenario", scenarios.scenario(knowledgeBaseId));
        Long documentId = request.get("documentId") == null ? null : Long.valueOf(String.valueOf(request.get("documentId")));
        Map<String, Object> source = documentId == null ? null : documentMapper.findReextractContext(documentId);
        if (documentId != null && (source == null || !knowledgeBaseId.equals(Long.valueOf(String.valueOf(source.get("knowledgeBaseId")))))) {
            throw new IllegalArgumentException("Document does not belong to knowledge base");
        }
        String permissionTags = normalizePermissionTags(source == null ? request.get("permissionTags") : source.get("permissionTags"));
        payload.put("permissionTags", permissionTags);
        if (documentId != null) {
            List<Map<String, Object>> storedUnits = documentMapper.findUnitsWithEvidence(documentId);
            if (storedUnits.isEmpty()) { throw new IllegalStateException("Document has no active evidence-backed units"); }
            List<String> texts = new ArrayList<String>();
            for (Map<String, Object> unit : storedUnits) { texts.add(stringValue(unit.get("content"))); }
            payload.put("text", String.join("\n\n", texts));
            payload.put("chunks", storedUnits);
            payload.put("units", storedUnits);
        }
        Map<String, Object> response = modelGate.extract(aiClient, payload);
        // Source authority is read once and stamped onto every extracted object: retrieval uses it
        // as a ranking signal (design V1.1 §8.3) instead of re-reading rows per object.
        Double authorityScore = loadAuthorityScore(documentId);
        // Retried on deadlock for the same reason batch ingestion is: cards, claims and entities all
        // upsert here, and a worker importing another document at the same time asks for the same
        // entity rows in a different order — MySQL then rolls this transaction back whole and asks
        // it to restart. Every statement inside is an idempotent upsert and the AI call above sits
        // outside the transaction, so redoing it neither duplicates rows nor pays for the model twice.
        // What this library wants derived is a property of the library, not of the pipeline: a
        // policy handbook needs versioned assertions and conflict detection but no graph walk,
        // while a company directory wants exactly the opposite.
        Map<String, Object> extractPolicy = scenarios.extraction(knowledgeBaseId);
        boolean graphEnabled = scenarios.graphEnabled(knowledgeBaseId);
        boolean claimsEnabled = Boolean.TRUE.equals(extractPolicy.get("claims"));
        PersistedKnowledge persisted = transactionRunner.retryingOnDeadlock(() -> {
            // Serialize one document's write set before querying its dedup keys. A rolled-back attempt owns no counters.
            if (documentId != null) {
                if (documentMapper.lockDocumentForExtraction(documentId, knowledgeBaseId) == null) {
                    throw new IllegalArgumentException("Document no longer exists");
                }
                Map<String, Object> current = documentMapper.findReextractContext(documentId);
                String currentTags = normalizePermissionTags(current.get("permissionTags"));
                if (!ExtractionCanonical.labels(permissionTags).equals(ExtractionCanonical.labels(currentTags))) {
                    throw new IllegalStateException("Source authorization changed during extraction; request a fresh run");
                }
            } else {
                documentMapper.lockBaseForExtraction(knowledgeBaseId);
            }
            PersistStats cardStats = new PersistStats();
            PersistStats claimStats = new PersistStats();
            List<Map<String, Object>> cards = persistCards(knowledgeBaseId, documentId, response.get("cards"), permissionTags,
                authorityScore, cardStats);
            List<Map<String, Object>> claims = claimsEnabled
                ? persistClaims(knowledgeBaseId, documentId, response.get("claims"), permissionTags, authorityScore, claimStats)
                : new ArrayList<Map<String, Object>>();
            if (!claimsEnabled && response.get("claims") instanceof List) {
                claimStats.received = ((List<?>) response.get("claims")).size();
                claimStats.rejected = claimStats.received;
                claimStats.reasons.put("CHANNEL_DISABLED", claimStats.received);
            }
            Map<String, Object> graph = graphEnabled ? persistAuthoritativeGraph(knowledgeBaseId, documentId, response, permissionTags)
                : skippedGraph();
            PersistedKnowledge result = new PersistedKnowledge(cards, claims, graph, cardStats, claimStats);
            if (documentId != null) {
                documentMapper.updateExtractionSummary(documentId, result.outcome(), jsonOrDefault(result.stats(), "{}"));
            }
            return result;
        });
        List<Map<String, Object>> savedCards = persisted.cards;
        List<Map<String, Object>> savedClaims = persisted.claims;
        PersistStats cardStats = persisted.cardStats;
        PersistStats claimStats = persisted.claimStats;
        response.put("cards", savedCards);
        response.put("claims", savedClaims);
        response.put("extractionStats", persisted.stats());
        response.put("extractionStatus", persisted.outcome());
        Map<String, String> warnings = new LinkedHashMap<String, String>();
        response.put("authoritativeGraph", persisted.graph);
        Map<String, Object> graphEvent = null;
        try {
            if (graphEnabled) {
            Map<String, Object> graphPayload = buildKnowledgeIndexPayload(knowledgeBaseId, new ArrayList<Map<String, Object>>(),
                new ArrayList<Map<String, Object>>(), withPermissionTags(response.get("entities"), permissionTags),
                withPermissionTags(response.get("relations"), permissionTags));
            String graphKey = "KB:" + knowledgeBaseId + ":GRAPH:" + (documentId == null ? "MANUAL" : documentId);
            graphEvent = transactionRunner.required(() -> outboxService.enqueue(knowledgeBaseId, "GRAPH", graphKey,
                "GRAPH_BATCH_UPSERT", AiApiPaths.INDEX_KNOWLEDGE, graphPayload, documentId == null ? "GRAPH_EXTRACT:" + graphKey + ":" +
                UUID.randomUUID().toString() : "GRAPH_EXTRACT:" + graphKey + ":" + fingerprint(jsonOrDefault(graphPayload, "{}"))));
            }
        } catch (RuntimeException ex) { postFailure(warnings, "GRAPH_INDEX", ex); }
        Map<String, Object> indexSummary = new LinkedHashMap<String, Object>();
        indexSummary.put("mode", "transactional-outbox");
        indexSummary.put("cardEvents", savedCards.size());
        indexSummary.put("claimEvents", savedClaims.size());
        indexSummary.put("graphEvent", graphEvent);
        response.put("knowledgeIndex", indexSummary);
        try {
            response.put("conflicts", Boolean.TRUE.equals(extractPolicy.get("conflictDetection"))
                ? detectAndPersistConflicts(knowledgeBaseId, claimStats.acceptedClaims) : new ArrayList<Object>());
        } catch (RuntimeException ex) { postFailure(warnings, "CONFLICT_DETECTION", ex); }
        try {
            response.put("entityResolution", Boolean.TRUE.equals(extractPolicy.get("entityResolution"))
                ? entityResolutionService.resolveAndMerge(knowledgeBaseId, response.get("entities")) : skippedResolution());
        } catch (RuntimeException ex) { postFailure(warnings, "ENTITY_RESOLUTION", ex); }
        try { announceExtraction(knowledgeBaseId, documentId, savedCards, savedClaims, cardStats, claimStats); }
        catch (RuntimeException ex) { postFailure(warnings, "ANNOUNCE", ex); }
        response.put("postProcessingWarnings", warnings);
        if (!warnings.isEmpty()) {
            response.put("extractionStatus", "PARTIAL");
            if (documentId != null) {
                Map<String, Object> summary = persisted.stats();
                summary.put("postProcessingWarnings", warnings);
                try { documentMapper.updateExtractionSummary(documentId, "PARTIAL", jsonOrDefault(summary, "{}")); }
                catch (RuntimeException ex) { LOG.error("Could not save partial extraction outcome document={}", documentId, ex); }
            }
        }
        return response;
    }

    /**
     * One EXTRACTION_COMPLETED event per batch.
     *
     * <p>Emission is deliberately batched rather than one event per card: a single document can
     * yield dozens of objects, and subscribers care about the batch outcome with its identifiers,
     * while individual objects announce themselves again when they are reviewed or superseded.</p>
     */
    private void announceExtraction(Long knowledgeBaseId, Long documentId, List<Map<String, Object>> savedCards, List<Map<String, Object>> savedClaims,
        PersistStats cardStats, PersistStats claimStats) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("documentId", documentId);
        detail.put("cardCount", savedCards == null ? 0 : savedCards.size());
        detail.put("claimCount", savedClaims == null ? 0 : savedClaims.size());
        // 处置明细：卡片/断言各自收到了多少候选、新增多少、复用了多少、拒绝了多少。
        // 没有它，一次“新增 0 条”的运行无法区分是模型没产出、还是产出被质量闸门挡掉、
        // 还是全部命中了已有知识——而这三者的处理方式完全不同。
        detail.put("cardStats", cardStats == null ? null : cardStats.toMap());
        detail.put("claimStats", claimStats == null ? null : claimStats.toMap());
        detail.put("cardCodes", codes(savedCards, "knowledge_code", 20));
        detail.put("claimCodes", codes(savedClaims, "claim_code", 20));
        detail.put("cardTitles", titles(savedCards, 5));
        publisher.emitUser(PlatformEventTypes.EXTRACTION_COMPLETED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "DOCUMENT", documentId == null
            ? null : String.valueOf(documentId), "知识抽取完成：新增 " + (savedCards == null ? 0 : savedCards.size()) + " 张卡片、" + (savedClaims == null ? 0 :
            savedClaims.size()) + " 条主张", detail);
        int candidateCount = (savedCards == null ? 0 : savedCards.size()) + (savedClaims == null ? 0 : savedClaims.size());
        if (candidateCount > 0) {
            // Candidates are distinguished from approved knowledge so a subscriber can follow the
            // review pipeline instead of assuming extraction means "usable now" (§16.2).
            publisher.emitUser(PlatformEventTypes.KNOWLEDGE_CANDIDATE_CREATED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "DOCUMENT",
                documentId == null ? null : String.valueOf(documentId), "新增 " + candidateCount + " 条待审核候选知识", detail);
        }
    }

    private List<String> codes(List<Map<String, Object>> items, String key, int limit) {
        List<String> values = new ArrayList<String>();
        if (items == null) {
            return values;
        }
        for (Map<String, Object> item : items) {
            if (values.size() >= limit) {
                break;
            }
            Object value = item.get(key);
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    private List<String> titles(List<Map<String, Object>> items, int limit) {
        List<String> values = new ArrayList<String>();
        if (items == null) {
            return values;
        }
        for (Map<String, Object> item : items) {
            if (values.size() >= limit) {
                break;
            }
            Object value = item.get("title");
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            values.add(text.length() > 80 ? text.substring(0, 80) + "…" : text);
        }
        return values;
    }

    /**
     * Source authority from the owning source; neutral 0.5 when unresolvable.
     */
    private Double loadAuthorityScore(Long documentId) {
        if (documentId == null) {
            return 0.5D;
        }
        Map<String, Object> header = indexMapper.getDocumentIndexHeader(documentId);
        return header == null ? 0.5D : doubleValue(header.get("authorityScore"), 0.5D);
    }

    @SuppressWarnings("unchecked")
    /**
     * 卡片入库：空壳判定 → 幂等 → 写入 → 记录跨来源重复关系。
     *
     * <p>三件事各有各的键，不要混用：</p>
     * <ul>
     *   <li><b>空壳判定</b>：只剔除排版噪声与作为独立标题行重复出现的标题；摘要<b>不参与</b>
     *       扣除——摘要常常就是正文的前若干字，扣掉它会把「请假要求 / 年假应提前三个工作日
     *       申请」这种有效短卡片误判为空壳。拿不准时降级待审而不是丢弃。</li>
     *   <li><b>幂等键</b>（知识库 + 文档 + 抽取版本 + 指纹，唯一约束）：同一篇文档重跑不重复
     *       落库，命中就只补证据。并发下的重复由数据库约束兜底，不靠先查后写。</li>
     *   <li><b>等价指纹</b>（完整规范化正文 + 类型）：只用来发现「不同来源表达了同一条知识」，
     *       命中<b>不合并</b>——两侧各自保留状态与权限标签，重复关系交人工裁决。</li>
     * </ul>
     */
    private List<Map<String, Object>> persistCards(Long knowledgeBaseId, Long documentId, Object cardsObject, String permissionTags, Double authorityScore,
        PersistStats stats) {
        List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
        if (!(cardsObject instanceof List)) {
            return saved;
        }
        List<Map<String, Object>> evidencePool = documentId == null ? Collections.emptyList() : documentMapper.findUnitsWithEvidence(documentId);
        for (Object item : (List<Object>) cardsObject) {
            if (!(item instanceof Map)) {
                stats.received++;
                stats.reject("INVALID_SHAPE");
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<String, Object>((Map<String, Object>) item);
            stats.received++;
            KnowledgeCard card = new KnowledgeCard();
            card.setKnowledgeCode(valueOrId(source.get("knowledge_code"), "K_"));
            card.setKnowledgeBaseId(knowledgeBaseId);
            card.setType(stringValue(source.get("type")));
            card.setTitle(stringValue(source.get("title")));
            card.setSummary(stringValue(source.get("summary")));
            card.setContent(stringValue(source.get("content")));
            card.setConfidence(doubleValue(source.get("confidence"), 0.0D));
            boolean deterministicHint = isDeterministicHint(source);
            boolean deterministic = documentId != null && deterministicVerifier.verifyCard(source, evidencePool);
            String governanceReason = deterministic ? "DETERMINISTIC_VERIFIED"
                : deterministicHint ? "DETERMINISTIC_VERIFICATION_FAILED" : "AI_EXTRACTION";
            // See persistClaims: a card assembled from the clause rows a unit already contains
            // adds no claim of its own, so it does not need to wait for review.
            card.setPermissionTags(permissionTags);
            card.setVersion(1);
            List<Long> evidence = resolveEvidence(source, evidencePool, "CARD");
            if (documentId != null && evidence.isEmpty()) { stats.reject("INVALID_EVIDENCE"); continue; }
            int effective = effectiveContentLength(card);
            if (effective == 0) { stats.reject("EMPTY_BODY"); continue; }
            String fingerprint = cardFingerprint(card);
            String dedupKey = dedupKey(knowledgeBaseId, documentId, fingerprint, permissionTags, evidencePool);
            card.setContentFingerprint(fingerprint);
            card.setDedupKey(dedupKey);
            // 同一篇文档重跑：对象已经存在，只把这一次的证据补上，不重复插入。
            String existingCode = cardClaimMapper.findCardCodeByDedupKey(knowledgeBaseId, dedupKey);
            if (existingCode != null) {
                stats.reused++;
                stats.evidenceLinked += linkEvidence("CARD", existingCode, evidence, knowledgeBaseId);
                continue;
            }
            if (effective < LOW_INFORMATION_LENGTH && !hasInformationSignal(card.getContent())) {
                // 短而且看不出信息要素：拿不准是不是空壳。宁可降级待审，也不要把可能有用的
                // 短知识直接丢掉——丢掉就无法恢复，而待审只是多一道人工确认。
                card.setStatus(KnowledgeStatus.UNCERTAIN.name());
                stats.lowInformation++;
            } else {
                card.setStatus(deterministic ? KnowledgeStatus.APPROVED.name() : KnowledgeStatus.DRAFT.name());
            }
            cardClaimMapper.insertCard(card);
            stats.created++;
            recordDuplicates(knowledgeBaseId, "CARD", card.getKnowledgeCode(), fingerprint, permissionTags, stats);
            governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CARD", card.getKnowledgeCode(), "CREATED",
                governanceReason);
            stats.evidenceLinked += linkEvidence("CARD", card.getKnowledgeCode(), evidence, knowledgeBaseId);
            source.put("knowledge_code", card.getKnowledgeCode());
            source.put("status", card.getStatus());
            source.put("permission_tags", permissionTags);
            // Retrieval quality signals: authority of the source, freshness anchor, evidence count.
            source.put("authority_score", authorityScore);
            source.put("updated_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            source.put("evidence_count", cardClaimMapper.countCardEvidence(card.getKnowledgeCode()));
            attachEvidenceAnchor("CARD", card.getKnowledgeCode(), source);
            Map<String, Object> payload = buildSingleKnowledgeIndexPayload(knowledgeBaseId, source, null);
            outboxService.enqueue(knowledgeBaseId, "CARD", "KB:" + knowledgeBaseId + ":CARD:" + card.getKnowledgeCode(), "KNOWLEDGE_OBJECT_UPSERT",
                AiApiPaths.INDEX_KNOWLEDGE, payload, "CARD_CREATE:" + card.getKnowledgeCode() + ":1");
            saved.add(source);
        }
        return saved;
    }

    @SuppressWarnings("unchecked")
    /**
     * 断言入库：无意义过滤 → 幂等 → 写入 → 记录跨来源重复关系。
     *
     * <p>「无意义」指的是确定的噪声，只判两种：主词与宾词相同（“X DESCRIBES X”读起来是
     * 一句废话），以及宾语是被塞进来的元数据键值表（“文档编号: 文档类型；…”是表格不是主张）。
     * 它们会占住结构化通道，还会被冲突检测当成互相矛盾的主张，把状态判成 CONFLICTED，
     * 于是这条知识既进不了衍生也进不了检索。</p>
     *
     * <p>重复判断<b>不再只按三元组</b>：指纹同时包含极性、条件与有效期。「支持 MySQL」与
     * 「不支持 MySQL」只差极性，同一主张在不同有效期下也并不等价。</p>
     */
    private List<Map<String, Object>> persistClaims(Long knowledgeBaseId, Long documentId, Object claimsObject, String permissionTags, Double authorityScore,
        PersistStats stats) {
        List<Map<String, Object>> saved = new ArrayList<Map<String, Object>>();
        if (!(claimsObject instanceof List)) {
            return saved;
        }
        List<Map<String, Object>> evidencePool = documentId == null ? Collections.emptyList() : documentMapper.findUnitsWithEvidence(documentId);
        for (Object item : (List<Object>) claimsObject) {
            if (!(item instanceof Map)) {
                stats.received++;
                stats.reject("INVALID_SHAPE");
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<String, Object>((Map<String, Object>) item);
            stats.received++;
            Claim claim = new Claim();
            claim.setClaimCode(valueOrId(source.get("claim_code"), "CLM_"));
            claim.setKnowledgeBaseId(knowledgeBaseId);
            claim.setSubject(stringValue(source.get("subject")));
            claim.setPredicate(stringValue(source.get("predicate")));
            claim.setObjectValue(stringValue(source.get("object")));
            claim.setConditionJson(jsonOrDefault(source.get("conditions"), "[]"));
            claim.setQualifierJson(jsonOrDefault(source.get("qualifiers"), "{}"));
            claim.setPolarity(stringValue(source.get("polarity")));
            claim.setConfidence(doubleValue(source.get("confidence"), 0.0D));
            claim.setCertainty(source.get("certainty") == null ? claim.getConfidence() : doubleValue(source.get("certainty"), claim.getConfidence()));
            claim.setValidFrom(dateValue(source.get("valid_from")));
            claim.setValidTo(dateValue(source.get("valid_to")));
            claim.setVersion(source.get("version") == null ? 1 : integerValue(source.get("version"), 1));
            boolean deterministicHint = isDeterministicHint(source);
            boolean deterministic = documentId != null && deterministicVerifier.verifyClaim(source, evidencePool);
            String governanceReason = deterministic ? "DETERMINISTIC_VERIFIED"
                : deterministicHint ? "DETERMINISTIC_VERIFICATION_FAILED" : "AI_EXTRACTION";
            // A clause read straight out of the source ("额度: 司龄满 1 年 5 天") asserts nothing
            // the source does not already say, so it is as trustworthy as the chunk it is
            // anchored to — and that chunk is retrievable today. Holding it in UNCERTAIN until
            // a human approves it leaves the structured channels permanently empty, which is
            // why an exact question such as "年假多少天" never reaches a claim. Model-inferred
            // knowledge still enters the governance queue, as before.
            claim.setPermissionTags(permissionTags);
            if (!isMeaningfulClaim(claim)) {
                stats.reject("INVALID_CLAIM");
                continue;
            }
            List<Long> evidence = resolveEvidence(source, evidencePool, "CLAIM");
            if (documentId != null && evidence.isEmpty()) { stats.reject("INVALID_EVIDENCE"); continue; }
            String fingerprint = claimFingerprint(claim);
            String dedupKey = dedupKey(knowledgeBaseId, documentId, fingerprint, permissionTags, evidencePool);
            claim.setClaimFingerprint(fingerprint);
            claim.setDedupKey(dedupKey);
            String existingCode = cardClaimMapper.findClaimCodeByDedupKey(knowledgeBaseId, dedupKey);
            if (existingCode != null) {
                stats.reused++;
                source.put("claim_code", existingCode);
                stats.acceptedClaims.add(source);
                stats.evidenceLinked += linkEvidence("CLAIM", existingCode, evidence, knowledgeBaseId);
                continue;
            }
            claim.setStatus(deterministic ? KnowledgeStatus.APPROVED.name() : KnowledgeStatus.UNCERTAIN.name());
            cardClaimMapper.insertClaim(claim);
            stats.created++;
            recordDuplicates(knowledgeBaseId, "CLAIM", claim.getClaimCode(), fingerprint, permissionTags, stats);
            governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CLAIM", claim.getClaimCode(), "CREATED",
                governanceReason);
            stats.evidenceLinked += linkEvidence("CLAIM", claim.getClaimCode(), evidence, knowledgeBaseId);
            source.put("claim_code", claim.getClaimCode());
            source.put("status", claim.getStatus());
            source.put("permission_tags", permissionTags);
            // Keep claim semantics visible downstream (conflict detection, UI, retrieval ranking).
            source.put("polarity", claim.getPolarity());
            source.put("certainty", claim.getCertainty());
            source.put("qualifiers", claim.getQualifierJson());
            source.put("valid_from", formatDate(claim.getValidFrom()));
            source.put("valid_to", formatDate(claim.getValidTo()));
            source.put("authority_score", authorityScore);
            source.put("updated_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            source.put("evidence_count", cardClaimMapper.countClaimEvidence(claim.getClaimCode()));
            attachEvidenceAnchor("CLAIM", claim.getClaimCode(), source);
            Map<String, Object> payload = buildSingleKnowledgeIndexPayload(knowledgeBaseId, null, source);
            outboxService.enqueue(knowledgeBaseId, "CLAIM", "KB:" + knowledgeBaseId + ":CLAIM:" + claim.getClaimCode(), "KNOWLEDGE_OBJECT_UPSERT",
                AiApiPaths.INDEX_KNOWLEDGE, payload, "CLAIM_CREATE:" + claim.getClaimCode() + ":1");
            saved.add(source);
            stats.acceptedClaims.add(source);
        }
        return saved;
    }

    /**
     * Persist entities, relations and knowledge-entity links of one extraction batch.
     *
     * <p>Entity codes are deterministic: the same term inside one knowledge base must reuse
     * one node, otherwise relations and mentions can never meet. Relation endpoints arrive
     * as names (extraction works on text) and are resolved against this batch first and the
     * authoritative entity table second, so a relation is not dropped when the model quotes
     * the entity name instead of its code.</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> persistAuthoritativeGraph(Long kb, Long documentId, Map<String, Object> response, String permissionTags) {
        Long sourceId = null;
        if (documentId != null) {
            Map<String, Object> header = indexMapper.getDocumentIndexHeader(documentId);
            if (header != null && header.get("sourceId") != null) {
                sourceId = Long.valueOf(String.valueOf(header.get("sourceId")));
            }
        }
        String aclFrom = sourceId == null ? "EXTRACTION_REQUEST" : "SOURCE:" + sourceId;
        Map<String, String> codeByName = new LinkedHashMap<String, String>();
        int entityCount = 0;
        Object entitiesObject = response.get("entities");
        if (entitiesObject instanceof List) {
            for (Object value : (List<Object>) entitiesObject) {
                if (!(value instanceof Map)) {
                    continue;
                }
                Map<String, Object> entity = (Map<String, Object>) value;
                String name = stringValue(first(entity, "canonical_name", "name"));
                String explicit = stringValue(first(entity, "entity_code", "code"));
                // Reuse the node of a term this knowledge base already knows, otherwise derive a
                // stable code from the name so the next document connects to the same node.
                String known = explicit.isEmpty() && !name.isEmpty() ? existingEntityCode(kb, name) : "";
                String code = !explicit.isEmpty() ? explicit : (!known.isEmpty() ? known : (name.isEmpty() ? idGenerator.next("ENT_") :
                    stableCode("ENT_", kb + "|" + normalizeName(name))));
                graphMapper.upsertExtractedEntity(kb, sourceId, code, stringValue(first(entity, "entity_type", "type")), name,
                    stringValue(entity.get("description")), doubleValue(entity.get("confidence"), 0.0D), "ACTIVE", permissionTags, aclFrom);
                String scopeHash = aclScopeHash(permissionTags);
                graphMapper.insertEntityAclScope(kb, code, sourceId, documentId, permissionTags, scopeHash, aclFrom);
                graphMapper.upsertEntityFacet(kb, code, code + ":" + scopeHash, sourceId, documentId, stringValue(first(entity, "entity_type",
                    "type")), name, stringValue(entity.get("description")), permissionTags, scopeHash, aclFrom);
                graphMapper.recomputeEntityAclMergePolicy(kb, code);
                entity.put("entity_code", code);
                if (!name.isEmpty()) {
                    codeByName.put(normalizeName(name), code);
                }
                entityCount++;
            }
        }
        int relationCount = 0;
        Object relationsObject = response.get("relations");
        if (relationsObject instanceof List) {
            for (Object value : (List<Object>) relationsObject) {
                if (!(value instanceof Map)) {
                    continue;
                }
                Map<String, Object> relation = (Map<String, Object>) value;
                EntityRef source = resolveEntity(kb, first(relation, "source_entity_code", "source", "subject"), codeByName);
                EntityRef target = resolveEntity(kb, first(relation, "target_entity_code", "target", "object"), codeByName);
                if (source == null || target == null || source.code.equals(target.code)) {
                    continue;
                }
                String type = stringValue(first(relation, "relation_type", "type", "predicate"));
                if (type.isEmpty()) {
                    type = "RELATED_TO";
                }
                String explicit = stringValue(first(relation, "relation_code", "code"));
                String relationCode = !explicit.isEmpty() ? explicit : stableCode("REL_", kb + "|" + source.code + "|" + type + "|" + target.code);
                graphMapper.upsertExtractedRelation(kb, sourceId, relationCode, source.id, type, target.id, doubleValue(relation.get("confidence"),
                    0.0D), "ACTIVE", permissionTags, aclFrom);
                String scopeHash = aclScopeHash(permissionTags);
                graphMapper.insertRelationAclScope(kb, relationCode, sourceId, documentId, permissionTags, scopeHash, aclFrom);
                graphMapper.upsertRelationFacet(kb, relationCode, relationCode + ":" + scopeHash, sourceId, documentId, source.code, target.code,
                    type, permissionTags, scopeHash, aclFrom);
                graphMapper.recomputeRelationAclMergePolicy(kb, relationCode);
                relation.put("relation_code", relationCode);
                // Stamp the resolved entity codes so the graph index event connects the real
                // entity nodes; name-only endpoints would create duplicate nodes in the graph view.
                relation.put("source_entity_code", source.code);
                relation.put("target_entity_code", target.code);
                relationCount++;
            }
        }
        int linkCount = persistKnowledgeEntityLinks(kb, response.get("claims"), true, permissionTags, codeByName) + persistKnowledgeEntityLinks(kb,
            response.get("cards"), false, permissionTags, codeByName);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("entityCount", entityCount);
        summary.put("relationCount", relationCount);
        summary.put("knowledgeEntityLinkCount", linkCount);
        return summary;
    }

    /**
     * Code of the node already carrying this term, or empty when the term is new.
     */
    private String existingEntityCode(Long kb, String name) {
        Map<String, Object> row = graphMapper.findEntityRef(kb, name);
        return row == null ? "" : stringValue(row.get("entityCode"));
    }

    /**
     * Resolve one entity reference that may be a code, a batch name or a stored name.
     */
    private EntityRef resolveEntity(Long kb, Object raw, Map<String, String> codeByName) {
        String value = stringValue(raw);
        if (value.isEmpty()) {
            return null;
        }
        String batchCode = codeByName.get(normalizeName(value));
        Map<String, Object> row = graphMapper.findEntityRef(kb, batchCode == null ? value : batchCode);
        if (row == null && batchCode != null) {
            row = graphMapper.findEntityRef(kb, value);
        }
        if (row == null || row.get("id") == null) {
            return null;
        }
        return new EntityRef(Long.valueOf(String.valueOf(row.get("id"))), stringValue(row.get("entityCode")));
    }

    /**
     * Stable identifier derived from authoritative content; identical input gives one node.
     */
    private String stableCode(String prefix, String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return prefix + hex(digest.digest(material.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception ex) {
            return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    @SuppressWarnings("unchecked")
    private int persistKnowledgeEntityLinks(Long kb, Object objects, boolean claim, String permissionTags, Map<String, String> codeByName) {
        int links = 0;
        if (!(objects instanceof List)) {
            return links;
        }
        for (Object value : (List<Object>) objects) {
            if (!(value instanceof Map)) {
                continue;
            }
            Map<String, Object> object = (Map<String, Object>) value;
            String objectCode = stringValue(first(object, claim ? "claim_code" : "knowledge_code", "code"));
            Object entityCodes = first(object, "entity_codes", "entities");
            if (!(entityCodes instanceof List)) {
                continue;
            }
            for (Object entityValue : (List<Object>) entityCodes) {
                String reference = entityValue instanceof Map ? stringValue(first((Map<String, Object>) entityValue, "entity_code", "code")) :
                    stringValue(entityValue);
                EntityRef entity = resolveEntity(kb, reference, codeByName);
                if (entity == null) {
                    continue;
                }
                if (claim) {
                    graphMapper.linkClaimEntityAcl(objectCode, entity.code, "ABOUT", permissionTags);
                } else {
                    graphMapper.linkCardEntityAcl(objectCode, entity.code, "ABOUT", permissionTags);
                }
                links++;
            }
        }
        return links;
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private Object first(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    /**
     * Stamp the evidence anchor of one knowledge object onto its retrieval payload.
     *
     * <p>A retrieved card or claim can only be cited by an answer when the citation carries a
     * resolvable {@code evidence_code} and content hash: the answer gate treats any other citation
     * as unverifiable. Evidence is linked card-level in MySQL, so the anchor is read here instead of
     * being reconstructed from the extracted text during retrieval.</p>
     */
    private void attachEvidenceAnchor(String objectType, String objectCode, Map<String, Object> target) {
        if (objectCode == null || objectCode.isEmpty()) {
            return;
        }
        Map<String, Object> anchor = evidenceMapper.findPrimaryEvidenceAnchor(objectType, objectCode);
        if (anchor == null) {
            return;
        }
        Object code = anchor.get("evidenceCode");
        if (code != null && stringValue(code).length() > 0) {
            target.put("evidence_code", stringValue(code));
            target.put("evidence_hash", stringValue(anchor.get("evidenceHash")));
        }
    }

    private Map<String, Object> buildSingleKnowledgeIndexPayload(Long kb, Map<String, Object> card, Map<String, Object> claim) {
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> claims = new ArrayList<Map<String, Object>>();
        if (card != null) {
            cards.add(card);
        }
        if (claim != null) {
            claims.add(claim);
        }
        return buildKnowledgeIndexPayload(kb, cards, claims, null, null);
    }

    @SuppressWarnings("unchecked")
    private Object withPermissionTags(Object value, String permissionTags) {
        if (!(value instanceof List)) {
            return value;
        }
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
        for (Object row : (List<Object>) value) {
            if (!(row instanceof Map)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<String, Object>((Map<String, Object>) row);
            copy.put("permission_tags", permissionTags);
            output.add(copy);
        }
        return output;
    }

    private Map<String, Object> buildKnowledgeIndexPayload(Long kb, List<Map<String, Object>> cards, List<Map<String, Object>> claims, Object
        entities, Object relations) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("cards", cards);
        payload.put("claims", claims);
        payload.put("entities", entities);
        payload.put("relations", relations);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Object detectAndPersistConflicts(Long knowledgeBaseId, List<Map<String, Object>> newClaims) {
        if (newClaims.isEmpty()) {
            return new ArrayList<Object>();
        }
        List<String> subjects = new ArrayList<String>();
        List<String> focusCodes = new ArrayList<String>();
        for (Map<String, Object> claim : newClaims) {
            String subject = stringValue(claim.get("subject"));
            if (!subject.isEmpty() && !subjects.contains(subject)) {
                subjects.add(subject);
            }
            focusCodes.add(stringValue(claim.get("claim_code")));
        }
        List<Claim> candidates = cardClaimMapper.listClaimsBySubjects(knowledgeBaseId, subjects);
        List<Map<String, Object>> conflictClaims = new ArrayList<Map<String, Object>>(candidates.size());
        for (Claim existing : candidates) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("claimCode", existing.getClaimCode());
            item.put("subject", existing.getSubject());
            item.put("predicate", existing.getPredicate());
            item.put("objectValue", existing.getObjectValue());
            item.put("confidence", existing.getConfidence());
            item.put("status", existing.getStatus());
            item.put("polarity", existing.getPolarity());
            item.put("valid_from", formatDate(existing.getValidFrom()));
            item.put("valid_to", formatDate(existing.getValidTo()));
            item.put("conditions", readComparisonJson(existing.getConditionJson(), "[]"));
            item.put("qualifiers", readComparisonJson(existing.getQualifierJson(), "{}"));
            conflictClaims.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("claims", conflictClaims);
        payload.put("focusCodes", focusCodes);
        Map<String, Object> detected = aiClient.post(AiApiPaths.DETECT_CONFLICTS, payload);
        Object conflictsObject = detected.get("conflicts");
        if (!(conflictsObject instanceof List)) {
            return new ArrayList<Object>();
        }
        transactionRunner.required(() -> {
            for (Object item : (List<Object>) conflictsObject) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> conflict = (Map<String, Object>) item;
                String objectA = stringValue(conflict.get("objectACode"));
                String objectB = stringValue(conflict.get("objectBCode"));
                governanceMapper.insertConflict(knowledgeBaseId, objectA, objectB, stringValue(conflict.get("conflictType")),
                    doubleValue(conflict.get("conflictScore"), 0.0D));
                // A detected difference is an OPEN review candidate, never an automatic withdrawal.

            }
        });
        return conflictsObject;
    }

    /**
     * 归一化比较键：去掉空白与标点差异，让“知识管理办法 V1.0”与“知识管理办法V1.0”算同一条。
     *
     * <p>只用于判断相同与否，不入库、不展示，所以可以放心折叠大小写与标点。</p>
     */
    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || c == '｜' || c == '|' || c == '：' || c == ':' || c == '、' || c == '，'
                || c == ',' || c == '。' || c == '.' || c == '（' || c == '）' || c == '(' || c == ')') {
                continue;
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    private Set<String> normalizedKeys(List<String> values) {
        Set<String> keys = new HashSet<String>();
        if (values == null) {
            return keys;
        }
        for (String value : values) {
            String key = normalizeKey(value);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * 有效正文长度：剔除排版噪声与作为独立标题行重复出现的标题之后，正文还剩多少字。
     *
     * <p><b>摘要刻意不参与扣除</b>。摘要通常是正文的前若干字，扣掉它等价于把内容清空：
     * 「请假要求 / 年假应提前三个工作日申请」这种卡片会被算成零长度。同理，标题只在它
     * 单独成行重复出现时才剔除，不把正文里出现过的同一个词都删掉——正文提到标题里的
     * 名词是正常的写法。</p>
     */
    private int effectiveContentLength(KnowledgeCard card) {
        String content = card.getContent() == null ? "" : card.getContent();
        String title = card.getTitle() == null ? "" : card.getTitle().trim();
        StringBuilder builder = new StringBuilder();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || isNoiseLine(line)) {
                continue;
            }
            if (!title.isEmpty() && line.equals(title)) {
                continue;
            }
            builder.append(line);
        }
        return normalizeContent(builder.toString()).length();
    }

    /** 页码与纯装饰行：“3”“第 3 页”“— 2 —”这类不该算作正文。 */
    private boolean isNoiseLine(String line) {
        String stripped = normalizeContent(line);
        if (stripped.isEmpty()) {
            return true;
        }
        if (stripped.matches("\\d+")) {
            return true;
        }
        return stripped.matches("(?i)第\\s*[0-9]+\\s*页.*") || stripped.matches("[-—_.]{2,}");
    }

    /**
     * 正文里有没有明确的信息要素（数字、单位、责任或规则词）。
     *
     * <p>短卡片不必然是空壳：制度文本里大量有效条款就是一句话。只要有数字或规则性表述，
     * 就按有效知识处理，不因为“短”而降级。</p>
     */
    private boolean hasInformationSignal(String content) {
        if (content == null) {
            return false;
        }
        String text = content.replaceAll("\\s+", "");
        if (text.matches(".*[0-9].*")) {
            return true;
        }
        String[] signals = {"应", "须", "必须", "不得", "严禁", "负责", "包含", "要求", "适用于", "提前",
            "不超过", "至少", "工作日", "百分比", "%", "由", "限于"};
        for (int i = 0; i < signals.length; i++) {
            if (text.contains(signals[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 断言是否言之有物：主词与宾词相同、或宾词是被塞进来的结构化噪声，都不算。
     *
     * <p>噪声的典型形态是“文档编号: 文档类型；REPORT-001: 报告”——抽取把文档头部的
     * 元数据表当成了一句话的宾语。它既读不通，又会和同一主词的正常主张互相判成冲突。</p>
     */
    private boolean isMeaningfulClaim(Claim claim) {
        String subject = ExtractionCanonical.text(claim.getSubject());
        String objectValue = ExtractionCanonical.text(claim.getObjectValue());
        if (subject.isEmpty() || objectValue.isEmpty() || subject.equals(objectValue)) {
            return false;
        }
        String raw = String.valueOf(claim.getObjectValue() == null ? "" : claim.getObjectValue());
        int colons = raw.replaceAll("[^：:]", "").length();
        // 两个以上的键值对分隔符 + 分号分隔：这是表格，不是一句主张。
        return !(colons >= 2 && (raw.contains("；") || raw.contains(";")));
    }

    /**
     * 卡片等价指纹：完整规范化正文 + 类型。
     *
     * <p>取完整内容而不是前若干字——同一套模板下的两篇文档往往前半段相同、结论不同，
     * 截断比对会把它们误判为同一条知识。类型参与是因为同一段文字作为「流程」和作为
     * 「概念」并不是一回事。</p>
     */
    private String cardFingerprint(KnowledgeCard card) {
        return fingerprint("CARD", normalizeContent(card.getType()), normalizeContent(card.getContent()));
    }

    /**
     * 断言等价指纹：主词 / 谓词 / 宾词 + 极性 + 条件 + 限定 + 有效期。
     *
     * <p>极性、条件与有效期必须参与：只差极性或有效期的两条主张并不等价，只按三元组
     * 比对会把它们错误地合并成一条（或反过来，把同一条主张在不同有效期下的两次出现
     * 当成重复）。</p>
     */
    private String claimFingerprint(Claim claim) {
        return fingerprint("CLAIM", normalizeContent(claim.getSubject()), normalizeContent(claim.getPredicate()),
            normalizeContent(claim.getObjectValue()), normalizeContent(claim.getPolarity()),
            ExtractionCanonical.json(claim.getConditionJson(), "[]"), ExtractionCanonical.json(claim.getQualifierJson(), "{}"),
            claim.getValidFrom() == null ? "" : String.valueOf(claim.getValidFrom().getTime()),
            claim.getValidTo() == null ? "" : String.valueOf(claim.getValidTo().getTime()));
    }

    /**
     * 抽取幂等键：知识库 + 文档 + 抽取版本 + 指纹。
     *
     * <p>重跑同一篇文档时键不变，于是被唯一约束挡住，只补证据不重复插入。并发下的重复
     * 同样由约束兜底：两个线程同时写同一条时，后写的那次拿到 0 行影响，按“命中已有”处理。
     * 抽取版本参与是为了让抽取逻辑升级后能产出新键，而不是一直命中旧结果。</p>
     */
    private String dedupKey(Long kb, Long documentId, String contentHash, String tags, List<Map<String, Object>> evidence) {
        List<String> anchors = new ArrayList<String>();
        for (Map<String, Object> row : evidence) { anchors.add(String.valueOf(row.get("evidence_code"))); }
        Collections.sort(anchors);
        return fingerprint(String.valueOf(kb), String.valueOf(documentId), EXTRACTOR_VERSION, contentHash,
            ExtractionCanonical.labels(tags), jsonOrDefault(anchors, "[]"));
    }

    private String normalizeContent(String value) { return ExtractionCanonical.text(value); }

    private String fingerprint(String... parts) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(java.util.Arrays.asList(parts)));
            StringBuilder value = new StringBuilder();
            for (byte item : bytes) { value.append(String.format("%02x", item)); }
            return value.toString();
        } catch (Exception ex) { throw new IllegalStateException("Cannot compute extraction fingerprint", ex); }
    }

    private List<Long> resolveEvidence(Map<String, Object> source, List<Map<String, Object>> pool, String kind) {
        List<String> supplied = toStringList(source.get("evidence_ids"));
        Map<String, Long> allowed = new LinkedHashMap<String, Long>();
        for (Map<String, Object> row : pool) {
            if (row.get("evidence_id") instanceof Number) {
                allowed.put(String.valueOf(row.get("evidence_code")), ((Number) row.get("evidence_id")).longValue());
            }
        }
        List<Long> result = new ArrayList<Long>();
        if (!supplied.isEmpty()) {
            for (String code : supplied) {
                if (!allowed.containsKey(code)) { return Collections.emptyList(); }
                if (!result.contains(allowed.get(code))) { result.add(allowed.get(code)); }
            }
            return result;
        }
        String body = ExtractionCanonical.text(stringValue(source.get("CARD".equals(kind) ? "content" : "object")));
        String subject = ExtractionCanonical.text(stringValue(source.get("subject")));
        if (body.isEmpty()) { return result; }
        // Legacy candidates may be anchored by an exact substring; never call all document chunks SUPPORT.
        for (Map<String, Object> row : pool) {
            String text = ExtractionCanonical.text(stringValue(row.get("content")));
            Long id = allowed.get(String.valueOf(row.get("evidence_code")));
            if (id != null && text.contains(body) && ("CARD".equals(kind) || !subject.isEmpty() && text.contains(subject))) {
                result.add(id);
            }
        }
        return result;
    }

    private int linkEvidence(String kind, String code, List<Long> evidence, Long kb) {
        if (evidence.isEmpty()) { return 0; }
        return "CARD".equals(kind) ? cardClaimMapper.linkCardEvidenceByIds(kb, code, evidence)
            : cardClaimMapper.linkClaimEvidenceByIds(kb, code, evidence);
    }

    private List<String> toStringList(Object value) {
        List<String> out = new ArrayList<String>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) { out.add(String.valueOf(item).trim()); }
            }
        }
        return out;
    }

    private void postFailure(Map<String, String> warnings, String stage, RuntimeException ex) {
        LOG.error("Extraction committed; post-processing failed at {}", stage, ex);
        warnings.put(stage, "FAILED_RETRYABLE");
    }

    private void recordDuplicates(Long knowledgeBaseId, String objectType, String code, String fingerprint, String permissionTags, PersistStats stats) {
        List<Map<String, Object>> twins = "CARD".equals(objectType)
            ? cardClaimMapper.findCardCodesByFingerprint(knowledgeBaseId, fingerprint, code)
            : cardClaimMapper.findClaimCodesByFingerprint(knowledgeBaseId, fingerprint, code);
        if (twins == null || twins.isEmpty()) {
            return;
        }
        for (int i = 0; i < twins.size(); i++) {
            Map<String, Object> row = twins.get(i);
            Object twinCode = row.get("code");
            if (twinCode == null) {
                continue;
            }
            String twin = String.valueOf(twinCode);
            String twinTags = row.get("permissionTags") == null ? "" : String.valueOf(row.get("permissionTags"));
            if (!sameVisibility(permissionTags, twinTags)) {
                // 可见范围不同的两条知识之间不记重复关系：把受控对象的存在暴露给原本看不到
                // 它的人，本身就是越权。宁可少记一条待裁决项，也不能让裁决台成为绕过 ACL 的入口。
                continue;
            }
            // INSERT IGNORE + uk_conflict_pair：同一对对象只记一次。
            governanceMapper.insertConflict(knowledgeBaseId, code, twin, DUPLICATE_CONFLICT_TYPE, 1.0D);
            stats.duplicates++;
        }
    }

    private boolean sameVisibility(String left, String right) {
        return ExtractionCanonical.labels(left).equals(ExtractionCanonical.labels(right));
    }

    /**
     * 一次抽取里某一类对象的处置结果。
     *
     * <p>恒等式：<code>received = created + reused + rejected</code>。lowInformation 与
     * duplicates 是 created 内部的细分（降级待审的、发现了同指纹邻居的），不参与等式；
     * evidenceLinked 单独记，因为“复用已有知识并补上证据”同样有业务价值，不该被
     * created=0 抹掉。</p>
     */
    private static final class PersistStats {
        int received;
        int created;
        int reused;
        int rejected;
        int lowInformation;
        int duplicates;
        int evidenceLinked;
        final Map<String, Integer> reasons = new LinkedHashMap<String, Integer>();
        final List<Map<String, Object>> acceptedClaims = new ArrayList<Map<String, Object>>();

        void reject(String reason) {
            rejected++;
            reasons.put(reason, reasons.containsKey(reason) ? reasons.get(reason) + 1 : 1);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("received", received);
            map.put("created", created);
            map.put("reused", reused);
            map.put("rejected", rejected);
            map.put("lowInformation", lowInformation);
            map.put("duplicates", duplicates);
            map.put("evidenceLinked", evidenceLinked);
            map.put("rejectedReasons", reasons);
            return map;
        }
    }

    private Object readComparisonJson(String value, String fallback) {
        try { return objectMapper.readValue(value == null ? fallback : value, Object.class); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid persisted comparison context", ex); }
    }

    private boolean isDeterministicHint(Map<String, Object> source) {
        return "DETERMINISTIC".equalsIgnoreCase(stringValue(source.get("derivation")));
    }

    private String valueOrId(Object value, String prefix) {
        return value == null || String.valueOf(value).trim().isEmpty() ? idGenerator.next(prefix) : String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleValue(Object value, Double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private Integer integerValue(Object value, Integer defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * Parse a validity boundary; unparsable or missing timestamps stay null (open bound).
     */
    private Date dateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return (Date) value;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.length() == 10) {
            // Date-only bounds are anchored at day start for from, day end for to semantics handled by caller.
            raw = raw + " 00:00:00";
        }
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(raw);
        } catch (ParseException ex) {
            return null;
        }
    }

    private String formatDate(Date value) {
        return value == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value);
    }

    private String normalizePermissionTags(Object value) {
        if (value == null) {
            return "[]";
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return "[]";
        }
        try {
            if (raw.startsWith("[")) {
                Object parsed = objectMapper.readValue(raw, Object.class);
                return objectMapper.writeValueAsString(parsed);
            }
            return objectMapper.writeValueAsString(java.util.Arrays.asList(raw.split(",")));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid permissionTags", ex);
        }
    }

    private String jsonOrDefault(Object value, String defaultJson) {
        try {
            return objectMapper.writeValueAsString(value == null ? new ArrayList<Object>() : value);
        } catch (Exception ex) {
            return defaultJson;
        }
    }

    private static final class PersistedKnowledge {

        private final List<Map<String, Object>> cards;

        private final List<Map<String, Object>> claims;

        private final Map<String, Object> graph;

        private final PersistStats cardStats;
        private final PersistStats claimStats;

        private PersistedKnowledge(List<Map<String, Object>> cards, List<Map<String, Object>> claims, Map<String, Object> graph,
            PersistStats cardStats, PersistStats claimStats) {
            this.cards = cards;
            this.claims = claims;
            this.graph = graph;
            this.cardStats = cardStats;
            this.claimStats = claimStats;
        }

        Map<String, Object> stats() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("card", cardStats.toMap());
            out.put("claim", claimStats.toMap());
            return out;
        }

        String outcome() {
            if (cardStats.created + cardStats.reused + claimStats.created + claimStats.reused > 0) { return "EXTRACTED"; }
            return cardStats.received + claimStats.received > 0 ? "QUALITY_REJECTED" : "EMPTY";
        }
    }

    /**
     * A resolved entity endpoint: authoritative row id plus the stable code edges reference.
     */
    private static final class EntityRef {

        private final Long id;

        private final String code;

        private EntityRef(Long id, String code) {
            this.id = id;
            this.code = code;
        }
    }

    /**
     * Stable hash for one evidence ACL scope; tags are normalized before hashing.
     */
    private String aclScopeHash(String tagsJson) {
        try {
            @SuppressWarnings("unchecked")
            List<String> tags = objectMapper.readValue(tagsJson, List.class);
            Collections.sort(tags);
            String normalized = objectMapper.writeValueAsString(tags);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot hash ACL scope", ex);
        }
    }
}
