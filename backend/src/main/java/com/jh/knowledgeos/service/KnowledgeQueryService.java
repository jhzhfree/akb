package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.QueryTraceRecord;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application service that invokes the AI query pipeline and persists query-trace observability data.
 */
@Service
public class KnowledgeQueryService {
    private final KnowledgeMapper mapper;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;

    public KnowledgeQueryService(
            KnowledgeMapper mapper,
            AiClient aiClient,
            ObjectMapper objectMapper,
            IdGenerator idGenerator) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * Executes the AI query pipeline and persists trace data for routing, retrieval,
     * latency, model, and citation diagnostics.
     */
    public Map<String, Object> query(Map<String, Object> request) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<String, Object>(request);
        String queryId = idGenerator.next("Q_");
        payload.put("queryId", queryId);

        long startedAt = System.currentTimeMillis();
        Map<String, Object> response = aiClient.post(AiApiPaths.QUERY, payload);
        long clientLatencyMs = System.currentTimeMillis() - startedAt;

        mapper.insertTrace(toTrace(queryId, payload, response, clientLatencyMs));
        response.put("queryId", queryId);
        return response;
    }

    @SuppressWarnings("unchecked")
    private QueryTraceRecord toTrace(
            String queryId,
            Map<String, Object> request,
            Map<String, Object> response,
            long clientLatencyMs) throws Exception {
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
            trace.setLatencyMs(Long.valueOf(
                    String.valueOf(((Map<String, Object>) aiTrace).get("totalLatencyMs"))));
        } else {
            trace.setLatencyMs(clientLatencyMs);
        }

        trace.setTokenUsageJson(json(response.get("tokenUsage")));
        trace.setErrorCode(null);
        trace.setTraceJson(json(response));
        trace.setAnswer(stringValue(response.get("answer")));
        return trace;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
