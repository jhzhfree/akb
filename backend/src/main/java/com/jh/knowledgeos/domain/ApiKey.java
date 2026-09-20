package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Credential for the external interface-service layer (RESTful / MCP Server / SSE).
 *
 * <p>Only the hash and a display prefix are persisted; the plaintext secret is generated
 * once at creation time and returned to the caller, never stored.</p>
 */
public class ApiKey {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    /** READ: 检索与问答；WRITE: 知识对象治理；INGEST: 接入与摄入。 */
    public static final String SCOPE_READ = "READ";
    public static final String SCOPE_WRITE = "WRITE";
    public static final String SCOPE_INGEST = "INGEST";

    private Long id;
    private String keyCode;
    private String name;
    private String secretHash;
    private String keyPrefix;
    private Long knowledgeBaseId;
    private String knowledgeBaseName;
    private String scopes;
    private Integer rateLimitPerMinute;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private Long createdBy;
    private String createdByName;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;
    /** Plaintext secret, populated only by the create response. */
    private String secret;

    public boolean hasScope(String scope) {
        if (scope == null) return false;
        List<String> owned = scopeList();
        return owned.contains(SCOPE_WRITE) || owned.contains(scope.toUpperCase());
    }

    public List<String> scopeList() {
        if (scopes == null || scopes.trim().isEmpty()) {
            List<String> defaults = new ArrayList<String>();
            defaults.add(SCOPE_READ);
            return defaults;
        }
        return Arrays.asList(scopes.toUpperCase().split(","));
    }

    public boolean isUsable() {
        return STATUS_ACTIVE.equalsIgnoreCase(status)
                && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKeyCode() { return keyCode; }
    public void setKeyCode(String v) { keyCode = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getSecretHash() { return secretHash; }
    public void setSecretHash(String v) { secretHash = v; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String v) { keyPrefix = v; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long v) { knowledgeBaseId = v; }
    public String getKnowledgeBaseName() { return knowledgeBaseName; }
    public void setKnowledgeBaseName(String v) { knowledgeBaseName = v; }
    public String getScopes() { return scopes; }
    public void setScopes(String v) { scopes = v; }
    public Integer getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(Integer v) { rateLimitPerMinute = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime v) { expiresAt = v; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime v) { lastUsedAt = v; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long v) { createdBy = v; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String v) { createdByName = v; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime v) { createdTime = v; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime v) { updatedTime = v; }
    public String getSecret() { return secret; }
    public void setSecret(String v) { secret = v; }
}
