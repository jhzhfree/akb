package com.jh.knowledgeos.dto;

import java.util.List;

/** Per-knowledge-base grant settings managed by a platform administrator. */
public class ExternalKnowledgeBaseGrantRequest {

    /** 知识库编码（{@code KB_xxxx}）；过渡期也收数字主键。 */
    private String knowledgeBaseId;
    private String expiresAt;
    private String reason;
    /** null inherits the API Key policy; empty means public resources only. */
    private List<String> permissionTags;

    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public List<String> getPermissionTags() { return permissionTags; }
    public void setPermissionTags(List<String> permissionTags) { this.permissionTags = permissionTags; }
}
