package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.QueryTraceRecord;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.TraceMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application service that invokes the AI query pipeline and persists query-trace observability data.
 */
@Service
public class KnowledgeQueryService {

    private final TraceMapper traceMapper;

    private final AiClient aiClient;

    private final ObjectMapper objectMapper;

    private final IdGenerator idGenerator;

    private final PlatformEventPublisher publisher;

    public KnowledgeQueryService(TraceMapper traceMapper, AiClient aiClient, ObjectMapper objectMapper, IdGenerator idGenerator, PlatformEventPublisher
        publisher) {
        this.traceMapper = traceMapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.publisher = publisher;
    }

    /**
     * Executes the AI query pipeline and persists trace data for routing, retrieval,
     * latency, model, and citation diagnostics.
     */
    @Transactional
    public Map<String, Object> query(Map<String, Object> request) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>(request);
        String queryId = idGenerator.next("Q_");
        payload.put("queryId", queryId);
        long startedAt = System.currentTimeMillis();
        Map<String, Object> response = aiClient.post(AiApiPaths.QUERY, payload);
        long clientLatencyMs = System.currentTimeMillis() - startedAt;
        traceMapper.insertTrace(toTrace(queryId, payload, response, clientLatencyMs));
        persistAnswerEvidenceAssessment(queryId, payload, response);
        persistAnswerQualification(queryId, payload, response);
        response.put("queryId", queryId);
        announceRetrieval(payload, response, clientLatencyMs);
        return response;
    }

    /**
     * Publishes one RETRIEVAL_EXECUTED event per call.
     *
     * <p>Every channel — console, REST, MCP and SSE — funnels through this single method, so one
     * subscription sees retrieval activity no matter where it originated. The payload carries the
     * full diagnostic picture (rewrite, routing, recall depth, citations, latency, model) while
     * leaving the answer text to the delivery layer, which trims it per credential.</p>
     */
    private void announceRetrieval(Map<String, Object> request, Map<String, Object> response, long clientLatencyMs) {
        Long kb = Long.valueOf(String.valueOf(request.get("knowledgeBaseId")));
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("queryId", request.get("queryId"));
        detail.put("question", String.valueOf(request.get("query")));
        detail.put("topK", request.get("topK"));
        detail.put("channel", request.get("source") == null ? "UI" : String.valueOf(request.get("source")));
        detail.put("rewrittenQueries", response.get("rewrittenQueries"));
        detail.put("routerDecision", response.get("router"));
        detail.put("recalledCount", count(response.get("recall")));
        detail.put("rerankedCount", count(response.get("reranker")));
        detail.put("selectedContextCount", count(response.get("selectedContext")));
        detail.put("citationCount", count(response.get("citations")));
        detail.put("model", response.get("model"));
        detail.put("latencyMs", latencyOf(response, clientLatencyMs));
        detail.put("tokenUsage", response.get("tokenUsage"));
        detail.put("answerChars", String.valueOf(response.get("answer")).length());
        detail.put("answerPreview", preview(String.valueOf(response.get("answer"))));
        detail.put("externalKeyCode", request.get("externalKeyCode"));
        detail.put("externalKeyName", request.get("externalKeyName"));
        User user = AuthContext.get();
        String source = request.get("source") == null ? PlatformEventPublisher.SOURCE_UI : String.valueOf(request.get("source"));
        publisher.emit(PlatformEventTypes.RETRIEVAL_EXECUTED, kb, source, "QUERY", String.valueOf(request.get("queryId")), "知识检索：" +
            preview(String.valueOf(request.get("query"))), detail, user == null ? PlatformEventPublisher.ACTOR_API_KEY :
            PlatformEventPublisher.ACTOR_USER, user == null ? String.valueOf(request.get("externalKeyCode")) : String.valueOf(user.getId()), user ==
            null ? String.valueOf(request.get("externalKeyName")) : user.getUsername());
    }

    private Object latencyOf(Map<String, Object> response, long fallback) {
        Object trace = response.get("trace");
        if (trace instanceof Map) {
            Object value = ((Map<String, Object>) trace).get("totalLatencyMs");
            if (value != null) {
                return value;
            }
        }
        return fallback;
    }

    private int count(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.length() > 200 ? text.substring(0, 200) + "…" : text;
        return flat.replace("\n", " ");
    }

    /**
     * Persist the final policy decision and every structured claim atomically with the trace.
     */
    @SuppressWarnings("unchecked")
    private void persistAnswerQualification(String queryId, Map<String, Object> request, Map<String, Object> response) throws Exception {
        Object value = response.get("answerQualification");
        if (!(value instanceof Map)) {
            return;
        }
        Map<String, Object> qualification = (Map<String, Object>) value;
        Long kb = Long.valueOf(String.valueOf(request.get("knowledgeBaseId")));
        String policyVersion = stringValue(qualification.get("policyVersion"));
        traceMapper.insertAnswerQualification(queryId, kb, policyVersion, stringValue(qualification.get("gateStatus")),
            stringValue(qualification.get("evidenceGateStatus")), stringValue(qualification.get("structuredGateStatus")),
            json(qualification.get("decisionReasons")), stringValue(qualification.get("originalAnswerHash")),
            stringValue(qualification.get("deliveredAnswerHash")), intValue(qualification.get("claimCount")));
        Object claims = qualification.get("claims");
        if (!(claims instanceof List)) {
            return;
        }
        for (Object item : (List<Object>) claims) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> claim = (Map<String, Object>) item;
            traceMapper.insertAnswerClaimAssessment(queryId, stringValue(claim.get("claimId")), policyVersion, stringValue(claim.get("statement")),
                stringValue(claim.get("decision")), doubleValue(claim.get("confidence")), doubleValue(claim.get("entailmentScore")),
                json(claim.get("citationIndexes")), json(claim.get("evidenceIds")), json(claim.get("evidenceStatuses")),
                booleanValue(claim.get("includedInFinalAnswer")));
        }
    }

    @SuppressWarnings("unchecked")
    private QueryTraceRecord toTrace(String queryId, Map<String, Object> request, Map<String, Object> response, long clientLatencyMs) throws Exception {
        QueryTraceRecord trace = new QueryTraceRecord();
        trace.setQueryId(queryId);
        trace.setKnowledgeBaseId(Long.valueOf(String.valueOf(request.get("knowledgeBaseId"))));
        trace.setTraceId(MDC.get("traceId"));
        trace.setOriginalQuery(String.valueOf(request.get("query")));
        trace.setRewrittenQueriesJson(json(response.get("rewrittenQueries")));
        trace.setRouterDecisionJson(json(response.get("router")));
        trace.setRetrievalJson(json(response.get("recall")));
        trace.setFusionJson(json(response.get("fusion")));
        trace.setRerankJson(json(response.get("reranker")));
        trace.setSelectedContextJson(json(response.get("selectedContext")));
        trace.setCitationsJson(json(response.get("citations")));
        trace.setModelName(stringValue(response.get("model")));
        Object aiTrace = response.get("trace");
        if (aiTrace instanceof Map && ((Map<String, Object>) aiTrace).get("totalLatencyMs") != null) {
            trace.setLatencyMs(Long.valueOf(String.valueOf(((Map<String, Object>) aiTrace).get("totalLatencyMs"))));
        } else {
            trace.setLatencyMs(clientLatencyMs);
        }
        trace.setTokenUsageJson(json(response.get("tokenUsage")));
        trace.setErrorCode(null);
        trace.setTraceJson(json(response));
        // §12.5: the stage timeline is stored in its own column so the console can render it
        // directly and later work can aggregate per-stage latency across queries.
        Object stages = aiTrace instanceof Map ? ((Map<String, Object>) aiTrace).get("stages") : null;
        trace.setPipelineJson(stages == null ? null : json(stages));
        trace.setAnswer(stringValue(response.get("answer")));
        return trace;
    }

    @SuppressWarnings("unchecked")
    private void persistAnswerEvidenceAssessment(String queryId, Map<String, Object> request, Map<String, Object> response) throws Exception {
        Object value = response.get("answerVerification");
        if (!(value instanceof Map)) {
            return;
        }
        Map<String, Object> verification = (Map<String, Object>) value;
        traceMapper.insertAnswerEvidenceAssessment(queryId, Long.valueOf(String.valueOf(request.get("knowledgeBaseId"))),
            stringValue(verification.get("gateStatus")), doubleValue(verification.get("completenessScore")),
            doubleValue(verification.get("faithfulnessScore")), intValue(verification.get("factualClaimCount")),
            intValue(verification.get("supportedClaimCount")), intValue(verification.get("citationCount")),
            json(verification.get("unsupportedClaims")), json(verification));
    }

    private Double doubleValue(Object value) {
        return value == null ? 0.0D : Double.valueOf(String.valueOf(value));
    }

    private Integer intValue(Object value) {
        return value == null ? 0 : Integer.valueOf(String.valueOf(value));
    }

    private Boolean booleanValue(Object value) {
        return value != null && Boolean.valueOf(String.valueOf(value));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
