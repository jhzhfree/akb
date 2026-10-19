package com.jh.knowledgeos.dto;

import java.util.List;

/**
 * API key creation request; scopes default to read-only when omitted.
 */
public class ExternalKeyRequest {

    private String name;

    private Long knowledgeBaseId;

    private List<String> scopes;

    private Integer rateLimitPerMinute;

    /**
     * ISO-8601 local date-time, e.g. 2026-12-31T23:59:59; null means never expires.
     */
    private String expiresAt;

    /**
     * Origins allowed to embed the widget with this key, e.g. https://intranet.corp.com.
     */
    private List<String> allowedOrigins;

    private String memoryNamespace;

    private String memoryAgentCode;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public Integer getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(Integer rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getMemoryNamespace() {
        return memoryNamespace;
    }

    public void setMemoryNamespace(String memoryNamespace) {
        this.memoryNamespace = memoryNamespace;
    }

    public String getMemoryAgentCode() {
        return memoryAgentCode;
    }

    public void setMemoryAgentCode(String memoryAgentCode) {
        this.memoryAgentCode = memoryAgentCode;
    }
}
