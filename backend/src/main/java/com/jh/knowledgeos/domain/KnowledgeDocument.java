package com.jh.knowledgeos.domain;

/**
 * Persistence model for a parsed source document.
 */
public class KnowledgeDocument {
    private Long id;
    private Long sourceVersionId;
    private String documentCode;
    private String title;
    private String mimeType;
    private String parseStatus;
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
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
}
