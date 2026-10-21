package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.CardClaimMapper;
import com.jh.knowledgeos.mapper.TraceMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.mapper.GovernanceMapper;
import com.jh.knowledgeos.mapper.EvidenceMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Read-only application service for cards, claims, sources, conflicts, and operational views.
 */
@Service
public class KnowledgeReadService {

    private final KnowledgeMapper mapper;

    private final CardClaimMapper cardClaimMapper;

    private final TraceMapper traceMapper;

    private final EvidenceMapper evidenceMapper;

    private final GovernanceMapper governanceMapper;

    private final DocumentMapper documentMapper;


    private final AiClient aiClient;

    private final AuthoritativeGraphNeighborhoodService authoritativeGraph;

    private final ObjectMapper objectMapper;

    public KnowledgeReadService(
        KnowledgeMapper mapper,
        AiClient aiClient,
        AuthoritativeGraphNeighborhoodService authoritativeGraph,
        ObjectMapper objectMapper,
        EvidenceMapper evidenceMapper,
        GovernanceMapper governanceMapper,
        DocumentMapper documentMapper,
        CardClaimMapper cardClaimMapper,
        TraceMapper traceMapper) {
        this.cardClaimMapper = cardClaimMapper;
        this.traceMapper = traceMapper;
        this.evidenceMapper = evidenceMapper;
        this.governanceMapper = governanceMapper;
        this.documentMapper = documentMapper;
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.authoritativeGraph = authoritativeGraph;
        this.objectMapper = objectMapper;
    }

    /**
     * Graph neighborhood for visualization.
     *
     * <p>Neo4j is the serving view, but a knowledge base whose relationships were not yet projected
     * has authoritative evidence bindings that Neo4j cannot show. In that case the response falls
     * back to the MySQL projection and says so through {@code source}, so the UI never renders an
     * empty canvas while real relations exist.</p>
     */
    public Map<String, Object> graph(Long kb, String code, List<String> permissionTags, Integer depth, Integer limit) {
        int hops = depth == null || depth < 1 ? 2 : Math.min(depth, 3);
        int cap = limit == null || limit < 10 ? 80 : Math.min(limit, 400);
        Map<String, Object> served = null;
        try {
            String tags = String.join(",", permissionTags == null ? java.util.Collections.<String>emptyList() : permissionTags);
            served = aiClient.get("/ai/graph/" + kb + "/" + code + "?depth=" + hops + "&limit=" + cap + "&permissionTags=" + tags);
        } catch (RuntimeException ex) {
            served = null;
        }
        if (served != null && !isEmpty(served.get("edges"))) {
            served.put("source", "neo4j");
            return served;
        }
        Map<String, Object> fallback = authoritativeGraph.neighborhood(kb, code, hops, cap);
        fallback.put("source", "authoritative");
        if (served != null) {
            fallback.put("graphProvider", served.get("provider"));
            fallback.put("graphAvailable", served.get("available"));
        }
        return fallback;
    }

    /**
     * Workbench overview of the graph: one bounded picture of the whole knowledge base.
     *
     * <p>It is read from the authoritative model on purpose. The workbench counts entities and
     * relations from that same model, so a picture taken from a different store would disagree
     * with the numbers printed next to it. Neo4j stays the serving view for neighborhoods, where
     * traversal depth is what matters.</p>
     */
    public Map<String, Object> graphOverview(Long kb, Integer limit) {
        Map<String, Object> result = authoritativeGraph.overview(kb, limit == null ? 24 : limit);
        result.put("source", "authoritative");
        return result;
    }

    private static boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }
        return !(value instanceof List) || ((List<?>) value).isEmpty();
    }

    public Map<String, Object> knowledgeDetail(Long kb, String objectType, String objectCode, List<String> permissionTags) {
        String type = objectType == null ? "" : objectType.toUpperCase();
        // A chunk is addressable too: an operator reviewing a citation needs the raw unit,
        // its evidence and its neighbours, not only the cards derived from it.
        if ("CHUNK".equals(type) || "CONTENT_UNIT".equals(type)) {
            return chunkDetail(kb, objectCode);
        }
        Map<String, Object> object = mapper.getKnowledgeObject(kb, type, objectCode, permissionTags);
        if (object == null) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("object", object);
        result.put("evidence", evidenceMapper.listEvidenceForObject(objectType, objectCode));
        result.put("conflicts", governanceMapper.listObjectConflicts(objectCode));
        result.put("reviews", governanceMapper.listReviewsForObject(objectType, objectCode));
        result.put("assessments", governanceMapper.listAssessmentsForObject(kb, objectType, objectCode));
        result.put("versions", mapper.listKnowledgeVersions(kb, type, objectCode));
        try {
            result.put("graph", graph(kb, objectCode, permissionTags, null, null));
        } catch (RuntimeException ex) {
            result.put("graph", new LinkedHashMap<String, Object>());
        }
        return result;
    }

    /**
     * Detail of one content unit: the raw text a citation points at, the evidence that
     * quotes it, and the neighbouring chunks that give the hit its context.
     */
    private Map<String, Object> chunkDetail(Long kb, String unitCode) {
        Map<String, Object> object = mapper.getContentUnitObject(kb, unitCode);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (object == null) {
            return result;
        }
        result.put("object", object);
        result.put("evidence", mapper.listEvidenceForContentUnit(kb, unitCode));
        Long documentId = toLong(object.get("documentId"));
        Long unitId = toLong(object.get("id"));
        if (documentId != null && unitId != null) {
            result.put("neighbors", mapper.listNeighborContentUnits(documentId, unitId));
        } else {
            result.put("neighbors", new ArrayList<Map<String, Object>>());
        }
        result.put("conflicts", new ArrayList<Map<String, Object>>());
        result.put("reviews", new ArrayList<Map<String, Object>>());
        result.put("assessments", new ArrayList<Map<String, Object>>());
        result.put("versions", new ArrayList<Map<String, Object>>());
        result.put("graph", new LinkedHashMap<String, Object>());
        return result;
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public Map<String, Object> capabilities() {
        return aiClient.get(AiApiPaths.CAPABILITIES);
    }

    public List<KnowledgeCard> cards(Long kb, List<String> permissionTags) {
        return cardClaimMapper.listCards(kb, permissionTags);
    }

    public List<Claim> claims(Long kb, List<String> permissionTags) {
        return cardClaimMapper.listClaims(kb, permissionTags);
    }

    public List<KnowledgeSource> sources(Long kb, List<String> permissionTags) {
        return documentMapper.listSources(kb, permissionTags);
    }

    /**
     * 文档通过来源归属知识库，因此与来源共用同一套 ACL：
     * 否则会出现「来源列表为空、文档数却照常显示」。
     */
    public List<Map<String, Object>> documents(Long kb, List<String> permissionTags) {
        return documentMapper.listDocuments(kb, permissionTags);
    }

    /**
     * Return the lightweight workspace summary required by the front-end prototype.
     * This intentionally composes existing authoritative reads instead of creating
     * another denormalized dashboard table.
     */
    public Map<String, Object> overview(Long kb, List<String> permissionTags) {
        List<KnowledgeSource> sources = documentMapper.listSources(kb, permissionTags);
        List<Map<String, Object>> documents = documentMapper.listDocuments(kb, permissionTags);
        List<KnowledgeCard> cards = cardClaimMapper.listCards(kb, permissionTags);
        List<Claim> claims = cardClaimMapper.listClaims(kb, permissionTags);
        List<Map<String, Object>> pending = governanceMapper.listPendingReviews(kb);
        List<Map<String, Object>> conflicts = governanceMapper.listConflicts(kb, "OPEN");
        List<Map<String, Object>> assessments = governanceMapper.listAssessments(kb, 20);
        long chunks = 0L;
        long effectiveCards = 0L;
        for (Map<String, Object> document : documents) {
            Object value = document.get("chunkCount");
            if (value instanceof Number) {
                chunks += ((Number) value).longValue();
            }
        }
        for (KnowledgeCard card : cards) {
            String status = card.getStatus();
            if ("APPROVED".equals(status) || "EFFECTIVE".equals(status)) {
                effectiveCards++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("sourceCount", sources.size());
        result.put("documentCount", documents.size());
        result.put("chunkCount", chunks);
        result.put("cardCount", cards.size());
        result.put("effectiveCardCount", effectiveCards);
        result.put("claimCount", claims.size());
        result.put("pendingReviewCount", pending.size());
        result.put("openConflictCount", conflicts.size());
        result.put("assessmentCount", assessments.size());
        return result;
    }

    public Map<String, Object> contentUnitDetail(Long kb, Long contentUnitId, List<String> permissionTags) {
        return evidenceMapper.getContentUnitDetail(kb, contentUnitId, permissionTags);
    }

    public Map<String, Object> evidenceDetail(Long kb, String evidenceCode, List<String> permissionTags) {
        return evidenceMapper.getEvidenceDetail(kb, evidenceCode, permissionTags);
    }

    /**
     * Chunk list of one document, including the metadata that explains each chunk's boundaries.
     */
    public List<Map<String, Object>> documentChunks(Long documentId) {
        return documentMapper.listDocumentChunks(documentId);
    }

    /**
     * Walks one knowledge object back to its origin: object → evidence → chunk → document → source.
     * A reviewer judging a card has to be able to inspect exactly which chunk of which document of
     * which source supports it, so the chain is returned as one flat, ordered list of hops.
     */
    public Map<String, Object> lineage(Long kb, String objectType, String objectCode) {
        String type = objectType == null || objectType.trim().isEmpty() ? "CARD" : objectType.trim().toUpperCase();
        List<Map<String, Object>> nodes = evidenceMapper.listLineageForObject(kb, type, objectCode);
        if (nodes.isEmpty() && !"CLAIM".equals(type)) {
            type = "CLAIM";
            nodes = evidenceMapper.listLineageForObject(kb, type, objectCode);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("objectType", type);
        result.put("objectCode", objectCode);
        result.put("evidenceCount", nodes.size());
        result.put("nodes", nodes);
        return result;
    }

    public Map<String, Object> trace(String queryId) {
        Map<String, Object> trace = traceMapper.getTrace(queryId);
        if (trace == null) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> qualification = answerQualification(queryId);
        trace.put("answerQualification", qualification);
        // §12.5: expose the pipeline timeline as a parsed array; the raw column stays available
        // for consumers that still want to parse the whole JSON document themselves.
        trace.put("stages", parseJsonList(trace.get("pipelineJson")));
        return trace;
    }

    private List<Object> parseJsonList(Object value) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            Object parsed = objectMapper.readValue(String.valueOf(value), Object.class);
            if (parsed instanceof List) {
                return (List<Object>) parsed;
            }
        } catch (Exception ignored) {
            // A malformed diagnostic document must not make the trace endpoint fail.
        }
        return java.util.Collections.emptyList();
    }

    public Map<String, Object> answerQualification(String queryId) {
        Map<String, Object> qualification = traceMapper.getAnswerQualification(queryId);
        if (qualification == null) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>(qualification);
        result.put("claims", traceMapper.listAnswerClaimAssessments(queryId));
        Object reasons = blockReasons(queryId);
        if (reasons != null) {
            result.put("blockReasons", reasons);
        }
        return result;
    }

    /**
     * Why the evidence gate withheld sentences: no citation, a citation without an exact
     * evidence binding, or a faithfulness score under the threshold. Without this split,
     * "the answer was blocked" leaves nothing to tune.
     */
    @SuppressWarnings("unchecked")
    private Object blockReasons(String queryId) {
        try {
            Map<String, Object> row = traceMapper.getAnswerVerificationJson(queryId);
            Object raw = row == null ? null : row.get("verificationJson");
            if (raw == null) {
                return null;
            }
            Map<String, Object> verification = objectMapper.readValue(String.valueOf(raw), Map.class);
            return verification.get("blockReasons");
        } catch (Exception ignored) {
            // A missing or malformed verification document must not break the qualification view.
            return null;
        }
    }

    public Map<String, Object> answerQualificationSummary(Long kb, Integer hours) {
        int safeHours = hours == null ? 24 : Math.max(1, Math.min(hours, 24 * 90));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("windowHours", safeHours);
        result.put("summary", traceMapper.summarizeAnswerQualifications(kb, safeHours));
        result.put("policies", traceMapper.listAnswerQualificationPolicyStats(kb, safeHours));
        return result;
    }

    /**
     * 问答历史（检索链路追踪页的数据源）。
     *
     * <p>返回 {@code items + total} 而不是裸列表：链路记录只增不减，前端要分页就必须知道
     * 总条数，否则只能"下一页按钮永远可点"——点到底才发现是空的。</p>
     */
    public Map<String, Object> queryHistory(Long kb, Integer page, Integer pageSize) {
        int safeSize = pageSize == null ? 20 : Math.max(1, Math.min(pageSize, 100));
        int safePage = page == null || page < 1 ? 1 : page;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", traceMapper.listQueryTraces(kb, safeSize, (safePage - 1) * safeSize));
        result.put("total", traceMapper.countQueryTraces(kb));
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }
}
