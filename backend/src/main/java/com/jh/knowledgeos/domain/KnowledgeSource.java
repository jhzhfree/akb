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

    /**
     * Path of the stored original upload, relative to the configured source storage root.
     */
    private String storagePath;

    private Double authorityScore;

    /**
     * Who keeps this source current. Answers "who do I ask when the answer is wrong";
     * a source without one is the first thing a knowledge ledger should chase.
     */
    private Long ownerUserId;

    /** Start of the window in which this source may be cited. */
    private java.util.Date effectiveAt;

    /** End of that window; past it the source is stale until someone revalidates it. */
    private java.util.Date expireAt;

    /** Who this source applies to — whole company, one department, one product line. */
    private String scope;

    private String status;

    private String permissionTags;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public void setSourceUri(String sourceUri) {
        this.sourceUri = sourceUri;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Double getAuthorityScore() {
        return authorityScore;
    }

    public void setAuthorityScore(Double authorityScore) {
        this.authorityScore = authorityScore;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public java.util.Date getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(java.util.Date effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public java.util.Date getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(java.util.Date expireAt) {
        this.expireAt = expireAt;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPermissionTags() {
        return permissionTags;
    }

    public void setPermissionTags(String permissionTags) {
        this.permissionTags = permissionTags;
    }
}
