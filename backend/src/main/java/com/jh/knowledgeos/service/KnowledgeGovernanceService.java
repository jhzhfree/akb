package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.KnowledgeStatus;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.GovernanceMapper;
import com.jh.knowledgeos.mapper.EvidenceMapper;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Application service for adjudication, conflict resolution, lifecycle status changes, and corresponding index events.
 */
@Service
public class KnowledgeGovernanceService {

    private static final int DEFAULT_ASSESSMENT_LIMIT = 100;

    /** How a decision is spoken, mapped to the state it actually means. */
    private static final Map<String, String> DECISION_ALIASES = new LinkedHashMap<String, String>();

    static {
        DECISION_ALIASES.put("APPROVE", KnowledgeStatus.APPROVED.name());
        DECISION_ALIASES.put("ACCEPT", KnowledgeStatus.APPROVED.name());
        DECISION_ALIASES.put("REJECT", KnowledgeStatus.REJECTED.name());
        DECISION_ALIASES.put("DENY", KnowledgeStatus.REJECTED.name());
    }

    private static final int MAX_ASSESSMENT_LIMIT = 500;

    /**
     * Accepted verdicts for conflict adjudication; anything else is rejected early rather than silently ignored.
     */
    private static final Set<String> SUPPORTED_VERDICTS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("KEEP_A", "KEEP_B", "MERGE",
        "REJECT_BOTH", "REQUEST_EVIDENCE", "IGNORE")));

    /**
     * Conflict stays open while evidence is being collected: closing it would hide the pending work.
     */
    private static final String CONFLICT_STATUS_EVIDENCE_PENDING = "OPEN";

    private final KnowledgeMapper mapper;

    private final EvidenceMapper evidenceMapper;

    private final GovernanceMapper governanceMapper;


    private final AiClient aiClient;

    private final ObjectMapper objectMapper;

    private final TransactionRunner transactionRunner;

    private final IndexOutboxService outboxService;

    public KnowledgeGovernanceService(KnowledgeMapper mapper, AiClient aiClient, ObjectMapper objectMapper, TransactionRunner transactionRunner,
        IndexOutboxService outboxService, EvidenceMapper evidenceMapper, GovernanceMapper governanceMapper) {
        this.evidenceMapper = evidenceMapper;
        this.governanceMapper = governanceMapper;
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.transactionRunner = transactionRunner;
        this.outboxService = outboxService;
    }

    @Transactional
    public /**
     * Persist a human adjudication decision and enqueue the resulting lifecycle change.
     */
    Map<String, Object> adjudicate(Map<String, Object> request) {
        String type = stringValue(request.get("objectType"));
        String code = stringValue(request.get("objectCode"));
        String decision = normalizeDecision(request.get("decision"));
        String reviewer = reviewerOrDefault(request.get("reviewer"));
        updateObjectStatus(type, code, decision);
        Long kb = governanceMapper.findKnowledgeBaseIdForObject(type, code);
        if (kb != null) {
            governanceMapper.snapshotKnowledgeObject(kb, type, code, "HUMAN_REVIEW", reviewer);
            outboxService.enqueueObjectStatus(kb, type, code, decision, MDC.get("traceId"));
        }
        // A decision is only auditable when it also records what was compared and under which policy.
        governanceMapper.insertJudgment(type, code, decision, reviewer, stringValue(request.get("comment")),
            stringValue(request.get("inputObjectCodes")), stringValue(request.get("resultObjectCode")), stringValue(request.get("policyVersion")));
        return request;
    }

    @SuppressWarnings("unchecked")
    public /**
     * Scores governance candidates with deterministic policy + AI judge assistance;
     * external model calls remain outside database transactions.
     */
    Map<String, Object> intelligentAdjudicate(Long knowledgeBaseId) throws Exception {
        List<Map<String, Object>> candidates = governanceMapper.listAdjudicationCandidates(knowledgeBaseId);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>(candidates.size());
        int autoApproved = 0;
        int needsHuman = 0;
        int conflicted = 0;
        for (Map<String, Object> candidate : candidates) {
            String type = stringValue(candidate.get("objectType"));
            String code = stringValue(candidate.get("objectCode"));
            List<Map<String, Object>> evidence = evidenceMapper.listEvidenceForObject(type, code);
            List<Map<String, Object>> conflicts = governanceMapper.listObjectConflicts(code);
            // Evidence support is scored once, inside the AI adjudication engine, from evidenceCount;
            // a second formula here would silently diverge from the published weights.
            Map<String, Object> payload = new LinkedHashMap<String, Object>(candidate);
            payload.put("evidenceSamples", evidence);
            payload.put("conflicts", conflicts);
            Map<String, Object> result = aiClient.post(AiApiPaths.ADJUDICATE, payload);
            String decision = stringValue(result.get("decision"));
            boolean requiresHuman = Boolean.parseBoolean(String.valueOf(result.get("requiresHumanReview")));
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
                governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, type, code, "AI_ADJUDICATION", "AI_JUDGE");
                outboxService.enqueueObjectStatus(knowledgeBaseId, type, code, finalStatus, String.valueOf(MDC.get("traceId")) + ":" + type + ":" + code);
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
        int normalized = limit == null ? DEFAULT_ASSESSMENT_LIMIT : Math.min(MAX_ASSESSMENT_LIMIT, Math.max(1, limit));
        return governanceMapper.listAssessments(kb, normalized);
    }

    public List<Map<String, Object>> pendingReviews(Long kb) {
        return governanceMapper.listPendingReviews(kb);
    }

    public List<Map<String, Object>> conflicts(Long kb, String status) {
        return governanceMapper.listConflicts(kb, status);
    }

    @Transactional
    public Map<String, Object> resolveConflict(Long id, String status, String resolution) {
        governanceMapper.resolveConflict(id, status, resolution);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("status", status);
        result.put("resolution", resolution);
        return result;
    }

    /**
     * Side-by-side content of the two objects in conflict, so the reviewer compares facts instead of codes.
     * Each side carries its own evidence list: source, page and authority are what a verdict is justified by.
     */
    public Map<String, Object> conflictTargets(Long id) {
        Map<String, Object> conflict = governanceMapper.getConflict(id);
        if (conflict == null) {
            throw new IllegalArgumentException("Conflict not found: " + id);
        }
        String codeA = stringValue(conflict.get("objectACode"));
        String codeB = stringValue(conflict.get("objectBCode"));
        List<String> codes = new ArrayList<String>(2);
        for (String code : Arrays.asList(codeA, codeB)) {
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        Map<String, String> types = objectTypes(codes);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<String, Map<String, Object>>();
        if (!codes.isEmpty()) {
            for (Map<String, Object> row : governanceMapper.listAdjudicationTargets(codes)) {
                rows.put(stringValue(row.get("objectCode")), row);
            }
        }
        List<Map<String, Object>> sides = new ArrayList<Map<String, Object>>(codes.size());
        for (String code : codes) {
            String type = types.get(code);
            Map<String, Object> side = rows.get(code) == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(rows.get(code));
            side.put("objectType", type);
            side.put("objectCode", code);
            List<Map<String, Object>> evidence = evidenceMapper.listEvidenceForObject(type, code);
            side.put("evidenceCount", evidence.size());
            side.put("evidence", evidence);
            sides.add(side);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("conflictType", stringValue(conflict.get("conflictType")));
        result.put("conflictScore", conflict.get("conflictScore"));
        result.put("status", stringValue(conflict.get("status")));
        result.put("objectACode", codeA);
        result.put("objectBCode", codeB);
        result.put("sides", sides);
        return result;
    }

    @Transactional
    public /**
     * Applies a verdict to both sides of a conflict: the surviving object is approved, the losing one rejected,
     * and every status change is snapshot and published so the index and the audit trail agree with the decision.
     */
    Map<String, Object> adjudicateConflict(Long id, String decision, String comment, String reviewer) {
        Map<String, Object> conflict = governanceMapper.getConflict(id);
        if (conflict == null) {
            throw new IllegalArgumentException("Conflict not found: " + id);
        }
        String verdict = decision == null || decision.trim().isEmpty() ? "MERGE" : decision.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_VERDICTS.contains(verdict)) {
            throw new IllegalArgumentException("Unsupported adjudication verdict: " + decision);
        }
        String codeA = stringValue(conflict.get("objectACode"));
        String codeB = stringValue(conflict.get("objectBCode"));
        Object kbValue = conflict.get("knowledgeBaseId");
        Long kb = kbValue == null ? null : Long.valueOf(stringValue(kbValue));
        Map<String, String> types = objectTypes(Arrays.asList(codeA, codeB));
        String resolvedReviewer = reviewerOrDefault(reviewer);
        boolean evidenceRequested = "REQUEST_EVIDENCE".equals(verdict);
        String winner = "IGNORE".equals(verdict) || evidenceRequested ? "" : resolveWinner(verdict, codeA, codeB);
        String loser = winner.isEmpty() ? "" : (winner.equals(codeA) ? codeB : codeA);
        Map<String, String> pendingStatus = new LinkedHashMap<String, String>();
        if (evidenceRequested) {
            // Nothing is approved or rejected yet: both sides wait for better evidence.
            pendingStatus.put(codeA, KnowledgeStatus.PENDING_REVIEW.name());
            pendingStatus.put(codeB, KnowledgeStatus.PENDING_REVIEW.name());
        } else if ("REJECT_BOTH".equals(verdict)) {
            pendingStatus.put(codeA, KnowledgeStatus.REJECTED.name());
            pendingStatus.put(codeB, KnowledgeStatus.REJECTED.name());
        } else if (!"IGNORE".equals(verdict)) {
            pendingStatus.put(winner, KnowledgeStatus.APPROVED.name());
            if (!winner.equals(loser)) {
                // A merge absorbs B into A: B stays traceable as superseded, a rejection would erase the lineage.
                pendingStatus.put(loser, "MERGE".equals(verdict) ? KnowledgeStatus.SUPERSEDED.name() : KnowledgeStatus.REJECTED.name());
            }
        }
        for (Map.Entry<String, String> entry : pendingStatus.entrySet()) {
            String code = entry.getKey();
            String type = types.get(code);
            updateObjectStatus(type, code, entry.getValue());
            if (kb != null) {
                governanceMapper.snapshotKnowledgeObject(kb, type, code, "CONFLICT_ADJUDICATION", resolvedReviewer);
                outboxService.enqueueObjectStatus(kb, type, code, entry.getValue(), MDC.get("traceId") + ":" + type + ":" + code);
            }
        }
        String conflictStatus = evidenceRequested ? CONFLICT_STATUS_EVIDENCE_PENDING : ("IGNORE".equals(verdict) ? "IGNORED" : "RESOLVED");
        governanceMapper.resolveConflict(id, conflictStatus, adjudicationNote(verdict, winner, comment));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("verdict", verdict);
        result.put("winner", winner);
        result.put("status", conflictStatus);
        result.put("objectStatus", pendingStatus);
        return result;
    }

    /**
     * KEEP_A / KEEP_B name the survivor explicitly; MERGE keeps A as the surviving object by convention.
     */
    private String resolveWinner(String verdict, String codeA, String codeB) {
        return "KEEP_B".equals(verdict) ? codeB : codeA;
    }

    /**
     * Object type must come from storage: conflicting codes are not always claims, and a wrong update would be silent.
     */
    private Map<String, String> objectTypes(List<String> codes) {
        Map<String, String> types = new LinkedHashMap<String, String>();
        for (String code : codes) {
            types.put(code, "CLAIM");
        }
        List<String> known = new ArrayList<String>(codes.size());
        for (String code : codes) {
            if (!code.isEmpty()) {
                known.add(code);
            }
        }
        if (known.isEmpty()) {
            return types;
        }
        for (Map<String, Object> row : governanceMapper.listAdjudicationTargets(known)) {
            String code = stringValue(row.get("objectCode"));
            String type = stringValue(row.get("objectType"));
            if (!code.isEmpty() && !type.isEmpty()) {
                types.put(code, type);
            }
        }
        return types;
    }

    /**
     * The resolution text must record the verdict itself, otherwise "RESOLVED" says nothing about how.
     */
    private String adjudicationNote(String verdict, String winner, String comment) {
        String trimmed = comment == null ? "" : comment.trim();
        String note = "VERDICT=" + verdict + (winner.isEmpty() ? "" : ";KEEP=" + winner);
        return trimmed.isEmpty() ? note : note + ";COMMENT=" + trimmed;
    }

    @SuppressWarnings("unchecked")
    private void persistAssessment(Long kb, String type, String code, Map<String, Object> result, boolean requiresHuman) throws Exception {
        Object aiJudge = result.get("aiJudge");
        String reason = aiJudge instanceof Map ? stringValue(((Map<String, Object>) aiJudge).get("reason")) : "";
        governanceMapper.insertAssessment(kb, type, code, stringValue(result.get("policyVersion")), doubleValue(result.get("knowledgeConfidence"),
            0.0D), stringValue(result.get("riskLevel")), stringValue(result.get("decision")), requiresHuman,
            objectMapper.writeValueAsString(result.get("components")), objectMapper.writeValueAsString(result.get("aiJudge")), reason);
    }

    private void updateObjectStatus(String type, String code, String status) {
        if ("CLAIM".equalsIgnoreCase(type)) {
            governanceMapper.updateClaimStatus(code, status);
        } else {
            governanceMapper.updateCardStatus(code, status);
        }
    }

    /**
     * A verdict without an author cannot be audited, so the signed-in user is the fallback reviewer.
     */
    private String reviewerOrDefault(Object reviewer) {
        String value = stringValue(reviewer).trim();
        if (!value.isEmpty()) {
            return value;
        }
        User current = AuthContext.get();
        return current == null ? "" : stringValue(current.getUsername());
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

    /**
     * Accept only a state the lifecycle knows, translating how a decision is spoken into how it is stored.
     *
     * <p>"APPROVE" is what an operator types and what an integration sends, but the stored state is
     * "APPROVED". Writing the raw value straight through used to mark an object approved on every
     * screen while retrieval silently skipped it, because retrieval only admits APPROVED/EFFECTIVE —
     * a knowledge base that looks populated and answers nothing. An unrecognised decision is refused
     * here rather than persisted, so the caller finds out at the call instead of at the answer.</p>
     */
    private String normalizeDecision(Object raw) {
        String value = stringValue(raw).trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("裁决结果不能为空");
        }
        String translated = DECISION_ALIASES.get(value);
        if (translated != null) {
            value = translated;
        }
        for (KnowledgeStatus status : KnowledgeStatus.values()) {
            if (status.name().equals(value)) {
                return status.name();
            }
        }
        List<String> allowed = new ArrayList<String>();
        for (KnowledgeStatus status : KnowledgeStatus.values()) {
            allowed.add(status.name());
        }
        throw new IllegalArgumentException("裁决结果不是合法状态：" + value + "（可选 " + String.join("、", allowed) + "）");
    }
}
