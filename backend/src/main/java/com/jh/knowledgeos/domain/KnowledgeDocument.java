package com.jh.knowledgeos.domain;

/**
 * Persistence model for a parsed source document.
 *
 * <p>{@code parseStatus} is the coarse parser view kept for backwards compatibility, while
 * {@code status} is the authoritative lifecycle defined by the design:
 * {@code UPLOADED → PROCESSING → READY / FAILED → RETRYING → PROCESSING}, plus
 * {@code READY → DIRTY → REINDEXING → READY} and {@code READY → ARCHIVED}. A failure always
 * records the stage it died in, the error and how many retries were spent on it, so an operator
 * can tell a parse failure from a dirty index without reading logs.</p>
 */
public class KnowledgeDocument {
    private Long id;
    private Long sourceVersionId;
    private String documentCode;
    private String title;
    private String mimeType;
    private String parseStatus;
    private String status;
    private String currentStage;
    private String errorMessage;
    private Integer retryCount;
    private Integer chunkCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(Long sourceVersionId) { this.sourceVersionId = sourceVersionId; }
    public String getDocumentCode() { return documentCode; }
    public void setDocumentCode(String documentCode) { this.documentCode = documentCode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
}
