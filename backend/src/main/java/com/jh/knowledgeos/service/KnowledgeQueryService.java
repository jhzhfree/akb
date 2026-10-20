package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.AnswerFormat;
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

    private final KbScenarioService scenarios;

    public KnowledgeQueryService(TraceMapper traceMapper, AiClient aiClient, ObjectMapper objectMapper, IdGenerator idGenerator,
        PlatformEventPublisher publisher, KbScenarioService scenarios) {
        this.traceMapper = traceMapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.publisher = publisher;
        this.scenarios = scenarios;
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    /**
     * Executes the AI query pipeline and persists trace data for routing, retrieval,
     * latency, model, and citation diagnostics.
     */
    @Transactional
    public Map<String, Object> query(Map<String, Object> request) throws Exception {
        return runQuery(request);
    }

    /** Returns retrieval diagnostics without answer text for the retrieval endpoint. */
    @Transactional
    public Map<String, Object> retrieve(Map<String, Object> request) throws Exception {
        Map<String, Object> response = runQuery(request);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("queryId", response.get("queryId"));
        result.put("rewrittenQueries", response.get("rewrittenQueries"));
        result.put("router", response.get("router"));
        result.put("recall", response.get("recall"));
        result.put("candidates", response.get("candidates"));
        result.put("reranker", response.get("reranker"));
        result.put("fusion", response.get("fusion"));
        result.put("trace", response.get("trace"));
        return result;
    }

    /** Returns the answer payload with the delivery format normalized by the AI service. */
    @Transactional
    public Map<String, Object> answer(Map<String, Object> request) throws Exception {
        Map<String, Object> response = runQuery(request);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("queryId", response.get("queryId"));
        result.put("answerFormat", AnswerFormat.normalize(response.get("answerFormat") == null ? null
            : String.valueOf(response.get("answerFormat"))));
        result.put("answer", response.get("answer"));
        result.put("citations", response.get("citations"));
        result.put("confidence", response.get("confidence"));
        result.put("model", response.get("model"));
        result.put("trace", response.get("trace"));
        return result;
    }

    private Map<String, Object> runQuery(Map<String, Object> request) throws Exception {
        Map<String, Object> payload = prepare(request);
        String queryId = String.valueOf(payload.get("queryId"));
        long startedAt = System.currentTimeMillis();
        Map<String, Object> response = aiClient.post(AiApiPaths.QUERY, payload);
        long clientLatencyMs = System.currentTimeMillis() - startedAt;
        persistAndAnnounce(queryId, payload, response, clientLatencyMs);
        return response;
    }

    /**
     * Same pipeline as {@link #query}, delivered as events instead of one response.
     *
     * <p>The listener receives each stage as the engine finishes it and each answer chunk as the
     * model writes it, which is what makes the embedded widget able to show the retrieval
     * happening rather than the answer appearing all at once. Everything that makes this a
     * governed call still happens: the knowledge base strategy is applied up front, and the
     * trace, the evidence assessments and the retrieval event are written when the stream ends.
     * The writes are not inside one transaction because a transaction that spans a stream would
     * hold a connection for as long as the model takes to answer.</p>
     */
    public void streamQuery(Map<String, Object> request, QueryStreamListener listener) throws Exception {
        Map<String, Object> payload = prepare(request);
        final String queryId = String.valueOf(payload.get("queryId"));
        final long startedAt = System.currentTimeMillis();
        final boolean[] completed = { false };
        aiClient.postStream(AiApiPaths.QUERY_STREAM, payload, (event, eventPayload) -> {
            if ("stage".equals(event) && eventPayload instanceof Map) {
                listener.stage((Map<String, Object>) eventPayload);
            } else if ("delta".equals(event)) {
                listener.delta(eventPayload == null ? "" : String.valueOf(eventPayload));
            } else if ("result".equals(event) && eventPayload instanceof Map) {
                Map<String, Object> response = new LinkedHashMap<String, Object>(unwrapResult((Map<String, Object>) eventPayload));
                response.put("queryId", queryId);
                persistAndAnnounce(queryId, payload, response, System.currentTimeMillis() - startedAt);
                completed[0] = true;
                listener.done(response);
            } else if ("error".equals(event)) {
                String message = eventPayload instanceof Map ? String.valueOf(((Map<String, Object>) eventPayload).get("message"))
                    : String.valueOf(eventPayload);
                throw new IllegalStateException(message);
            }
        });
        if (!completed[0]) {
            throw new IllegalStateException("AI 流式服务未返回完整结果");
        }
    }

    /**
     * Takes the finished result out of the frame the AI service wraps it in.
     *
     * <p>The blocking endpoint answers with the result object itself; the streamed one has to
     * label every frame, so it sends {@code {"result": {...}}} instead. Treating that frame as
     * the result is what made every streamed answer empty: no {@code answer}, no {@code
     * citations}, no {@code recall} — the embedding widget then reported "no result found" for a
     * question the console answers in full, and the stored trace recorded a null recall for it.
     * Unwrapping here is what makes a streamed call indistinguishable from a blocking one for
     * everything downstream.</p>
     */
    private static Map<String, Object> unwrapResult(Map<String, Object> frame) {
        Object nested = frame.get("result");
        // Only a bare envelope is unwrapped: a frame that already carries the answer fields is
        // the result itself and is left alone.
        if (nested instanceof Map && !frame.containsKey("answer")) {
            return (Map<String, Object>) nested;
        }
        return frame;
    }

    /**
     * Everything a caller is allowed to influence still has to pass through here: the query id is
     * generated by us, and the knowledge base's own strategy is merged in, so a streamed call is
     * governed by exactly the same rules as a blocking one.
     */
    private Map<String, Object> prepare(Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>(request);
        String queryId = idGenerator.next("Q_");
        payload.put("queryId", queryId);
        // Every channel funnels through this method, so it is the one place that can hand the
        // knowledge base its own strategy: a library created as 漏洞库 answers CVE questions with
        // the channels and thresholds that suit it, while an untouched library keeps following the
        // platform policy.
        Long kbId = toLong(payload.get("knowledgeBaseId"));
        Map<String, Object> override = kbId == null ? null : scenarios.queryOverride(kbId);
        if (override != null) {
            payload.put("policyOverride", override);
        }
        if (kbId != null && isBlank(payload.get("answerFormat"))) {
            String format = scenarios.answerFormat(kbId);
            if (!format.isEmpty()) {
                payload.put("answerFormat", format);
            }
        }
        return payload;
    }

    /**
     * Recording side of a query: trace, evidence assessments and the retrieval event. Shared by
     * the blocking and the streamed path so a streamed answer is observed in exactly the same way.
     */
    private void persistAndAnnounce(String queryId, Map<String, Object> payload, Map<String, Object> response, long clientLatencyMs)
        throws Exception {
        traceMapper.insertTrace(toTrace(queryId, payload, response, clientLatencyMs));
        persistAnswerEvidenceAssessment(queryId, payload, response);
        persistAnswerQualification(queryId, payload, response);
        response.put("queryId", queryId);
        announceRetrieval(payload, response, clientLatencyMs);
    }

    /**
     * What a streamed query reports back: stages as they finish, answer text as it is written,
     * and the assembled result once the pipeline ends.
     */
    public interface QueryStreamListener {

        void stage(Map<String, Object> stage);

        void delta(String text);

        void done(Map<String, Object> result);
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
        User actor = AuthContext.get();
        trace.setActorUserId(actor == null ? null : actor.getId());
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
