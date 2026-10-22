package com.jh.knowledgeos.dto;

/** Per-knowledge-base grant settings managed by a platform administrator. */
public class ExternalKnowledgeBaseGrantRequest {

    private Long knowledgeBaseId;
    private String expiresAt;
    private String reason;

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
