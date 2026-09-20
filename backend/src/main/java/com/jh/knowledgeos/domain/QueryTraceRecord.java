package com.jh.knowledgeos.domain;

/**
 * Persistence model for end-to-end query routing, retrieval, latency, and citation diagnostics.
 */
public class QueryTraceRecord {
    private String queryId;
    private Long knowledgeBaseId;
    private String traceId;
    private String originalQuery;
    private String rewrittenQueriesJson;
    private String routerDecisionJson;
    private String retrievalJson;
    private String fusionJson;
    private String rerankJson;
    private String selectedContextJson;
    private String citationsJson;
    private String modelName;
    private Long latencyMs;
    private String tokenUsageJson;
    private String errorCode;
    private String traceJson;
    /** Stage timeline (design §12.5): rewrite → recall → ACL → RRF → rerank → context → LLM → citation. */
    private String pipelineJson;
    private String answer;

    public String getQueryId(){return queryId;} public void setQueryId(String v){queryId=v;}
    public Long getKnowledgeBaseId(){return knowledgeBaseId;} public void setKnowledgeBaseId(Long v){knowledgeBaseId=v;}
    public String getTraceId(){return traceId;} public void setTraceId(String v){traceId=v;}
    public String getOriginalQuery(){return originalQuery;} public void setOriginalQuery(String v){originalQuery=v;}
    public String getRewrittenQueriesJson(){return rewrittenQueriesJson;} public void setRewrittenQueriesJson(String v){rewrittenQueriesJson=v;}
    public String getRouterDecisionJson(){return routerDecisionJson;} public void setRouterDecisionJson(String v){routerDecisionJson=v;}
    public String getRetrievalJson(){return retrievalJson;} public void setRetrievalJson(String v){retrievalJson=v;}
    public String getFusionJson(){return fusionJson;} public void setFusionJson(String v){fusionJson=v;}
    public String getRerankJson(){return rerankJson;} public void setRerankJson(String v){rerankJson=v;}
    public String getSelectedContextJson(){return selectedContextJson;} public void setSelectedContextJson(String v){selectedContextJson=v;}
    public String getCitationsJson(){return citationsJson;} public void setCitationsJson(String v){citationsJson=v;}
    public String getModelName(){return modelName;} public void setModelName(String v){modelName=v;}
    public Long getLatencyMs(){return latencyMs;} public void setLatencyMs(Long v){latencyMs=v;}
    public String getTokenUsageJson(){return tokenUsageJson;} public void setTokenUsageJson(String v){tokenUsageJson=v;}
    public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
    public String getTraceJson(){return traceJson;} public void setTraceJson(String v){traceJson=v;}
    public String getPipelineJson(){return pipelineJson;} public void setPipelineJson(String v){pipelineJson=v;}
    public String getAnswer(){return answer;} public void setAnswer(String v){answer=v;}
}
