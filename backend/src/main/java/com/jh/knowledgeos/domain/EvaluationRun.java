package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/** One execution of a dataset against the live pipeline, with the metrics it produced (design §14). */
public class EvaluationRun {
    private Long id;
    private String runCode;
    private Long datasetId;
    private Long knowledgeBaseId;
    private String status;
    private String mode;
    private Integer topK;
    private String policyVersion;
    private String baselineRunCode;
    private Integer caseCount;
    private Double hitAt1Rate;
    private Double hitAt3Rate;
    private Double hitAt5Rate;
    private Double recallAt5;
    private Double mrr;
    private Double ndcgAt10;
    private Integer latencyP50Ms;
    private Integer latencyP95Ms;
    private Double citationAccuracy;
    private Double faithfulness;
    private String regressionVerdict;
    private String baselineDeltaJson;
    private String gapsJson;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;

    public Long getId(){return id;} public void setId(Long v){id=v;}
    public String getRunCode(){return runCode;} public void setRunCode(String v){runCode=v;}
    public Long getDatasetId(){return datasetId;} public void setDatasetId(Long v){datasetId=v;}
    public Long getKnowledgeBaseId(){return knowledgeBaseId;} public void setKnowledgeBaseId(Long v){knowledgeBaseId=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getMode(){return mode;} public void setMode(String v){mode=v;}
    public Integer getTopK(){return topK;} public void setTopK(Integer v){topK=v;}
    public String getPolicyVersion(){return policyVersion;} public void setPolicyVersion(String v){policyVersion=v;}
    public String getBaselineRunCode(){return baselineRunCode;} public void setBaselineRunCode(String v){baselineRunCode=v;}
    public Integer getCaseCount(){return caseCount;} public void setCaseCount(Integer v){caseCount=v;}
    public Double getHitAt1Rate(){return hitAt1Rate;} public void setHitAt1Rate(Double v){hitAt1Rate=v;}
    public Double getHitAt3Rate(){return hitAt3Rate;} public void setHitAt3Rate(Double v){hitAt3Rate=v;}
    public Double getHitAt5Rate(){return hitAt5Rate;} public void setHitAt5Rate(Double v){hitAt5Rate=v;}
    public Double getRecallAt5(){return recallAt5;} public void setRecallAt5(Double v){recallAt5=v;}
    public Double getMrr(){return mrr;} public void setMrr(Double v){mrr=v;}
    public Double getNdcgAt10(){return ndcgAt10;} public void setNdcgAt10(Double v){ndcgAt10=v;}
    public Integer getLatencyP50Ms(){return latencyP50Ms;} public void setLatencyP50Ms(Integer v){latencyP50Ms=v;}
    public Integer getLatencyP95Ms(){return latencyP95Ms;} public void setLatencyP95Ms(Integer v){latencyP95Ms=v;}
    public Double getCitationAccuracy(){return citationAccuracy;} public void setCitationAccuracy(Double v){citationAccuracy=v;}
    public Double getFaithfulness(){return faithfulness;} public void setFaithfulness(Double v){faithfulness=v;}
    public String getRegressionVerdict(){return regressionVerdict;} public void setRegressionVerdict(String v){regressionVerdict=v;}
    public String getBaselineDeltaJson(){return baselineDeltaJson;} public void setBaselineDeltaJson(String v){baselineDeltaJson=v;}
    public String getGapsJson(){return gapsJson;} public void setGapsJson(String v){gapsJson=v;}
    public String getErrorMessage(){return errorMessage;} public void setErrorMessage(String v){errorMessage=v;}
    public LocalDateTime getStartedTime(){return startedTime;} public void setStartedTime(LocalDateTime v){startedTime=v;}
    public LocalDateTime getFinishedTime(){return finishedTime;} public void setFinishedTime(LocalDateTime v){finishedTime=v;}
}
