package com.jh.knowledgeos.domain;

/**
 * Persistence model for the authoritative origin of imported knowledge.
 */
public class KnowledgeSource {
    private Long id;
    private String sourceCode;
    private Long knowledgeBaseId;
    private String name;
    private String sourceType;
    private String sourceUri;
    /** Path of the stored original upload, relative to the configured source storage root. */
    private String storagePath;
    private Double authorityScore;
    private String status;
    private String permissionTags;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public Double getAuthorityScore() { return authorityScore; }
    public void setAuthorityScore(Double authorityScore) { this.authorityScore = authorityScore; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPermissionTags() { return permissionTags; }
    public void setPermissionTags(String permissionTags) { this.permissionTags = permissionTags; }
}
