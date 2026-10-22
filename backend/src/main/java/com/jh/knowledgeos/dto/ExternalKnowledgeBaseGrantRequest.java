package com.jh.knowledgeos.dto;

import java.util.List;

/** Per-knowledge-base grant settings managed by a platform administrator. */
public class ExternalKnowledgeBaseGrantRequest {

    private Long knowledgeBaseId;
    private String expiresAt;
    private String reason;
    /** null inherits the API Key policy; empty means public resources only. */
    private List<String> permissionTags;

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public List<String> getPermissionTags() { return permissionTags; }
    public void setPermissionTags(List<String> permissionTags) { this.permissionTags = permissionTags; }
}
