package com.jh.knowledgeos.domain;

/**
 * Persistent lifecycle run for one source ingestion.
 */
public class IngestionRun {

    private Long id;

    private String runCode;

    private Long knowledgeBaseId;

    private String sourceName;

    private String status;

    private String currentStage;

    private Integer chunkCount;

    private Integer cardCount;

    private Integer claimCount;

    private String errorMessage;

    private java.time.LocalDateTime startedTime;

    private java.time.LocalDateTime finishedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long v) {
        id = v;
    }

    public String getRunCode() {
        return runCode;
    }

    public void setRunCode(String v) {
        runCode = v;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long v) {
        knowledgeBaseId = v;
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String v) {
        sourceName = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String v) {
        currentStage = v;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer v) {
        chunkCount = v;
    }

    public Integer getCardCount() {
        return cardCount;
    }

    public void setCardCount(Integer v) {
        cardCount = v;
    }

    public Integer getClaimCount() {
        return claimCount;
    }

    public void setClaimCount(Integer v) {
        claimCount = v;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String v) {
        errorMessage = v;
    }

    public java.time.LocalDateTime getStartedTime() {
        return startedTime;
    }

    public void setStartedTime(java.time.LocalDateTime v) {
        startedTime = v;
    }

    public java.time.LocalDateTime getFinishedTime() {
        return finishedTime;
    }

    public void setFinishedTime(java.time.LocalDateTime v) {
        finishedTime = v;
    }
}
