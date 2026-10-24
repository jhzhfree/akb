package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/** Durable execution checkpoint for one post-extraction stage. */
public class ExtractionPostStage {
    private Long id;
    private String stageCode;
    private Long documentId;
    private Long knowledgeBaseId;
    private String runId;
    private String status;
    private Integer attemptCount;
    private Integer manualReplayCount;
    private Integer maxAttempts;
    private String idempotencyKey;
    private String payloadJson;
    private String lastError;
    private LocalDateTime nextAttemptAt;
    private String lockedBy;
    private LocalDateTime lockedAt;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getStageCode() { return stageCode; }
    public void setStageCode(String value) { stageCode = value; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long value) { documentId = value; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long value) { knowledgeBaseId = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
    public Integer getManualReplayCount() { return manualReplayCount; }
    public void setManualReplayCount(Integer value) { manualReplayCount = value; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer value) { maxAttempts = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String value) { payloadJson = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { lastError = value; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime value) { nextAttemptAt = value; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String value) { lockedBy = value; }
    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime value) { lockedAt = value; }
}
