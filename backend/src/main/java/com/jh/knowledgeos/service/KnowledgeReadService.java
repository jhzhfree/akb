package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Read-only application service for cards, claims, sources, conflicts, and operational views.
 */
@Service
public class KnowledgeReadService {
    private final KnowledgeMapper mapper;
    private final AiClient aiClient;
    private final AuthoritativeGraphNeighborhoodService authoritativeGraph;

    public KnowledgeReadService(KnowledgeMapper mapper, AiClient aiClient,
            AuthoritativeGraphNeighborhoodService authoritativeGraph) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.authoritativeGraph = authoritativeGraph;
    }

    /**
     * Graph neighborhood for visualization.
     *
     * <p>Neo4j is the serving view, but a knowledge base whose relationships were not yet projected
     * has authoritative evidence bindings that Neo4j cannot show. In that case the response falls
     * back to the MySQL projection and says so through {@code source}, so the UI never renders an
     * empty canvas while real relations exist.</p>
     */
    public Map<String, Object> graph(Long kb, String code, List<String> permissionTags,
            Integer depth, Integer limit) {
        int hops = depth == null || depth < 1 ? 2 : Math.min(depth, 3);
        int cap = limit == null || limit < 10 ? 80 : Math.min(limit, 400);
        Map<String, Object> served = null;
        try {
            String tags = String.join(",", permissionTags == null
                    ? java.util.Collections.<String>emptyList() : permissionTags);
            served = aiClient.get("/ai/graph/" + kb + "/" + code
                    + "?depth=" + hops + "&limit=" + cap + "&permissionTags=" + tags);
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

    private static boolean isEmpty(Object value) {
        if (value == null) return true;
        return !(value instanceof List) || ((List<?>) value).isEmpty();
    }

    public Map<String, Object> knowledgeDetail(Long kb, String objectType, String objectCode, List<String> permissionTags) {
        Map<String, Object> object = mapper.getKnowledgeObject(kb, objectType, objectCode, permissionTags);
        if (object == null) return new LinkedHashMap<String, Object>();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("object", object);
        result.put("evidence", mapper.listEvidenceForObject(objectType, objectCode));
        result.put("conflicts", mapper.listObjectConflicts(objectCode));
        result.put("reviews", mapper.listReviewsForObject(objectType, objectCode));
        result.put("assessments", mapper.listAssessmentsForObject(kb, objectType, objectCode));
        result.put("versions", mapper.listKnowledgeVersions(kb, objectType, objectCode));
        try {
            result.put("graph", graph(kb, objectCode, permissionTags, null, null));
        } catch (RuntimeException ex) {
            result.put("graph", new LinkedHashMap<String, Object>());
        }
        return result;
    }

    public Map<String, Object> capabilities() {
        return aiClient.get(AiApiPaths.CAPABILITIES);
    }

    public List<KnowledgeCard> cards(Long kb, List<String> permissionTags) {
        return mapper.listCards(kb, permissionTags);
    }

    public List<Claim> claims(Long kb, List<String> permissionTags) {
        return mapper.listClaims(kb, permissionTags);
    }

    public List<KnowledgeSource> sources(Long kb, List<String> permissionTags) {
        return mapper.listSources(kb, permissionTags);
    }

    public List<Map<String, Object>> documents(Long kb) {
        return mapper.listDocuments(kb);
    }

    /**
     * Return the lightweight workspace summary required by the front-end prototype.
     * This intentionally composes existing authoritative reads instead of creating
     * another denormalized dashboard table.
     */
    public Map<String, Object> overview(Long kb, List<String> permissionTags) {
        List<KnowledgeSource> sources = mapper.listSources(kb, permissionTags);
        List<Map<String, Object>> documents = mapper.listDocuments(kb);
        List<KnowledgeCard> cards = mapper.listCards(kb, permissionTags);
        List<Claim> claims = mapper.listClaims(kb, permissionTags);
        List<Map<String, Object>> pending = mapper.listPendingReviews(kb);
        List<Map<String, Object>> conflicts = mapper.listConflicts(kb, "OPEN");
        List<Map<String, Object>> assessments = mapper.listAssessments(kb, 20);
        long chunks = 0L;
        long effectiveCards = 0L;
        for (Map<String, Object> document : documents) {
            Object value = document.get("chunkCount");
            if (value instanceof Number) chunks += ((Number) value).longValue();
        }
        for (KnowledgeCard card : cards) {
            String status = card.getStatus();
            if ("APPROVED".equals(status) || "EFFECTIVE".equals(status)) effectiveCards++;
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
        return mapper.getContentUnitDetail(kb, contentUnitId, permissionTags);
    }

    public Map<String, Object> evidenceDetail(Long kb, String evidenceCode, List<String> permissionTags) {
        return mapper.getEvidenceDetail(kb, evidenceCode, permissionTags);
    }

    public Map<String, Object> trace(String queryId) {
        Map<String, Object> trace = mapper.getTrace(queryId);
        if (trace == null) return new LinkedHashMap<String, Object>();
        Map<String, Object> qualification = answerQualification(queryId);
        trace.put("answerQualification", qualification);
        return trace;
    }

    public Map<String, Object> answerQualification(String queryId) {
        Map<String, Object> qualification = mapper.getAnswerQualification(queryId);
        if (qualification == null) return new LinkedHashMap<String, Object>();
        Map<String, Object> result = new LinkedHashMap<String, Object>(qualification);
        result.put("claims", mapper.listAnswerClaimAssessments(queryId));
        return result;
    }

    public Map<String, Object> answerQualificationSummary(Long kb, Integer hours) {
        int safeHours = hours == null ? 24 : Math.max(1, Math.min(hours, 24 * 90));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("windowHours", safeHours);
        result.put("summary", mapper.summarizeAnswerQualifications(kb, safeHours));
        result.put("policies", mapper.listAnswerQualificationPolicyStats(kb, safeHours));
        return result;
    }

    public List<Map<String, Object>> queryHistory(Long kb, Integer limit) {
        int safeLimit = limit == null ? 30 : Math.max(1, Math.min(limit, 100));
        return mapper.listQueryTraces(kb, safeLimit);
    }
}
