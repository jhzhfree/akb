package com.jh.knowledgeos.dto;

import java.util.Map;

/**
 * Payload for creating, updating or — without saving — previewing a connector.
 *
 * <p>{@code config} stays free-form on purpose: the fields a connector needs differ per type
 * (JQL for Jira, space key for Confluence, path plus field mapping for a generic REST API), and
 * inventing a Java field per type would make every new connector a schema migration.</p>
 */
public class ConnectorRequest {

    private Long knowledgeBaseId;

    private String name;

    private String type;

    private String baseUrl;

    private String authType;

    private String username;

    private String secret;

    private Map<String, Object> config;

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }
}
