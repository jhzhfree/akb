package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.KnowledgeStatus;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service for adjudication, conflict resolution, lifecycle status changes, and corresponding index events.
 */
@Service
public class KnowledgeGovernanceService {
    private static final int DEFAULT_ASSESSMENT_LIMIT = 100;
    private static final int MAX_ASSESSMENT_LIMIT = 500;

    private final KnowledgeMapper mapper;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final TransactionRunner transactionRunner;
    private final IndexOutboxService outboxService;

    public KnowledgeGovernanceService(
            KnowledgeMapper mapper,
            AiClient aiClient,
            ObjectMapper objectMapper,
            TransactionRunner transactionRunner,
            IndexOutboxService outboxService) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
    }

    @Transactional
    /** Persist a human adjudication decision and enqueue the resulting lifecycle change. */
    public Map<String, Object> adjudicate(Map<String, Object> request) {
        String type = stringValue(request.get("objectType"));
        String code = stringValue(request.get("objectCode"));
        String decision = stringValue(request.get("decision"));
        updateObjectStatus(type, code, decision);
        Long kb = mapper.findKnowledgeBaseIdForObject(type, code);
        if (kb != null) {
            outboxService.enqueueObjectStatus(kb, type, code, decision, MDC.get("traceId"));
        }
        mapper.insertReview(
                type,
                code,
                decision,
                stringValue(request.get("reviewer")),
                stringValue(request.get("comment")));
        return request;
    }

    @SuppressWarnings("unchecked")
    /**
     * Scores governance candidates with deterministic policy + AI judge assistance;
     * external model calls remain outside database transactions.
     */
    public Map<String, Object> intelligentAdjudicate(Long knowledgeBaseId) throws Exception {
        List<Map<String, Object>> candidates = mapper.listAdjudicationCandidates(knowledgeBaseId);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>(candidates.size());
        int autoApproved = 0;
        int needsHuman = 0;
        int conflicted = 0;

        for (Map<String, Object> candidate : candidates) {
            String type = stringValue(candidate.get("objectType"));
            String code = stringValue(candidate.get("objectCode"));
            List<Map<String, Object>> evidence = mapper.listEvidenceForObject(type, code);
            List<Map<String, Object>> conflicts = mapper.listObjectConflicts(code);

            Map<String, Object> payload = new LinkedHashMap<String, Object>(candidate);
            payload.put("evidenceSamples", evidence);
            payload.put("conflicts", conflicts);
            payload.put(
                    "evidenceSupportScore",
                    evidence.isEmpty() ? 0.0D : Math.min(1.0D, 0.55D + evidence.size() * 0.12D));

            Map<String, Object> result = aiClient.post(AiApiPaths.ADJUDICATE, payload);
            String decision = stringValue(result.get("decision"));
            boolean requiresHuman = Boolean.parseBoolean(
                    String.valueOf(result.get("requiresHumanReview")));

            transactionRunner.required(() -> {
                try {
                    persistAssessment(knowledgeBaseId, type, code, result, requiresHuman);
                } catch (Exception ex) {
                    throw new IllegalStateException("Persist adjudication assessment failed", ex);
                }
                String finalStatus;
                if (KnowledgeStatus.APPROVED.name().equals(decision)) {
                    finalStatus = KnowledgeStatus.APPROVED.name();
                } else if (KnowledgeStatus.CONFLICTED.name().equals(decision)) {
                    finalStatus = KnowledgeStatus.CONFLICTED.name();
                } else {
                    finalStatus = KnowledgeStatus.PENDING_REVIEW.name();
                }
                updateObjectStatus(type, code, finalStatus);
                outboxService.enqueueObjectStatus(
                        knowledgeBaseId, type, code, finalStatus,
                        String.valueOf(MDC.get("traceId")) + ":" + type + ":" + code);
            });

            if (KnowledgeStatus.APPROVED.name().equals(decision)) {
                autoApproved++;
            } else if (KnowledgeStatus.CONFLICTED.name().equals(decision)) {
                conflicted++;
                needsHuman++;
            } else {
                needsHuman++;
            }

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("objectType", type);
            item.put("objectCode", code);
            item.putAll(result);
            results.add(item);
        }

        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("knowledgeBaseId", knowledgeBaseId);
        output.put("total", results.size());
        output.put("autoApproved", autoApproved);
        output.put("requiresHumanReview", needsHuman);
        output.put("conflicted", conflicted);
        output.put("results", results);
        return output;
    }

    public List<Map<String, Object>> assessments(Long kb, Integer limit) {
        int normalized = limit == null
                ? DEFAULT_ASSESSMENT_LIMIT
                : Math.min(MAX_ASSESSMENT_LIMIT, Math.max(1, limit));
        return mapper.listAssessments(kb, normalized);
    }

    public List<Map<String, Object>> pendingReviews(Long kb) {
        return mapper.listPendingReviews(kb);
    }

    public List<Map<String, Object>> conflicts(Long kb, String status) {
        return mapper.listConflicts(kb, status);
    }

    @Transactional
    public Map<String, Object> resolveConflict(Long id, String status) {
        mapper.resolveConflict(id, status);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("status", status);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void persistAssessment(
            Long kb,
            String type,
            String code,
            Map<String, Object> result,
            boolean requiresHuman) throws Exception {
        Object aiJudge = result.get("aiJudge");
        String reason = aiJudge instanceof Map
                ? stringValue(((Map<String, Object>) aiJudge).get("reason"))
                : "";
        mapper.insertAssessment(
                kb,
                type,
                code,
                stringValue(result.get("policyVersion")),
                doubleValue(result.get("knowledgeConfidence"), 0.0D),
                stringValue(result.get("riskLevel")),
                stringValue(result.get("decision")),
                requiresHuman,
                objectMapper.writeValueAsString(result.get("components")),
                objectMapper.writeValueAsString(result.get("aiJudge")),
                reason);
    }

    private void updateObjectStatus(String type, String code, String status) {
        if ("CLAIM".equalsIgnoreCase(type)) {
            mapper.updateClaimStatus(code, status);
        } else {
            mapper.updateCardStatus(code, status);
        }
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
}
