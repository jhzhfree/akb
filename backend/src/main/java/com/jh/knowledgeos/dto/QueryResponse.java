package com.jh.knowledgeos.dto;

import java.util.List;
import java.util.Map;

/**
 * API response containing the answer, citations, confidence, and query trace identifiers.
 */
public class QueryResponse {
    private String queryId;
    private List<String> rewrittenQueries;
    private Map<String, Object> router;
    private List<String> strategies;
    private Map<String, Object> recall;
    private List<Map<String, Object>> candidates;
    private Map<String, Object> reranker;
    private Object context;
    private List<Map<String, Object>> citations;
    private String answer;
    private String model;
    private Map<String, Object> trace;

    public String getQueryId() { return queryId; }
    public void setQueryId(String queryId) { this.queryId = queryId; }
    public List<String> getRewrittenQueries() { return rewrittenQueries; }
    public void setRewrittenQueries(List<String> rewrittenQueries) { this.rewrittenQueries = rewrittenQueries; }
    public Map<String, Object> getRouter() { return router; }
    public void setRouter(Map<String, Object> router) { this.router = router; }
    public List<String> getStrategies() { return strategies; }
    public void setStrategies(List<String> strategies) { this.strategies = strategies; }
    public Map<String, Object> getRecall() { return recall; }
    public void setRecall(Map<String, Object> recall) { this.recall = recall; }
    public List<Map<String, Object>> getCandidates() { return candidates; }
    public void setCandidates(List<Map<String, Object>> candidates) { this.candidates = candidates; }
    public Map<String, Object> getReranker() { return reranker; }
    public void setReranker(Map<String, Object> reranker) { this.reranker = reranker; }
    public Object getContext() { return context; }
    public void setContext(Object context) { this.context = context; }
    public List<Map<String, Object>> getCitations() { return citations; }
    public void setCitations(List<Map<String, Object>> citations) { this.citations = citations; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Map<String, Object> getTrace() { return trace; }
    public void setTrace(Map<String, Object> trace) { this.trace = trace; }
}
