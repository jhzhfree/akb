package com.jh.knowledgeos.domain;

import java.util.Date;
import java.util.Map;

/**
 * Persistence model for an external system the console can list knowledge from.
 *
 * <p>{@code config} is not a column: it is the parsed {@code config_json}, filled in by the
 * service so the console can render the type-specific form (JQL, space key, REST field mapping)
 * without knowing how the JSON is stored. {@code secretConfigured} exists for the same reason —
 * the secret itself is never sent back to a browser, but the form still has to show whether one
 * is already saved.</p>
 */
public class ConnectorSource {

    private Long id;

    private String connectorCode;

    private Long knowledgeBaseId;

    private String name;

    private String type;

    private String baseUrl;

    private String authType;

    private String username;

    private String secret;

    private String configJson;

    private Map<String, Object> config;

    private Boolean secretConfigured;

    private String status;

    private String lastTestStatus;

    private String lastTestMessage;

    private Date lastTestTime;

    /** 最近一次把外部知识同步落库的时间与条数（t_connector_item）。 */
    private Date lastSyncTime;

    private Integer lastSyncCount;

    /** 定时同步的周期：五段 cron，空表示只等人手动点。 */
    private String syncCron;

    private Date nextFireAt;

    /** 定时同步的运行态：IDLE / RUNNING / FAILED，调度器只认 IDLE 与 FAILED。 */
    private String syncStatus;

    private String lastError;

    private String createdBy;

    private Date createdTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConnectorCode() {
        return connectorCode;
    }

    public void setConnectorCode(String connectorCode) {
        this.connectorCode = connectorCode;
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

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public Boolean getSecretConfigured() {
        return secretConfigured;
    }

    public void setSecretConfigured(Boolean secretConfigured) {
        this.secretConfigured = secretConfigured;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastTestStatus() {
        return lastTestStatus;
    }

    public void setLastTestStatus(String lastTestStatus) {
        this.lastTestStatus = lastTestStatus;
    }

    public String getLastTestMessage() {
        return lastTestMessage;
    }

    public void setLastTestMessage(String lastTestMessage) {
        this.lastTestMessage = lastTestMessage;
    }

    public Date getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(Date lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Date getLastSyncTime() {
        return lastSyncTime;
    }

    public void setLastSyncTime(Date lastSyncTime) {
        this.lastSyncTime = lastSyncTime;
    }

    public Integer getLastSyncCount() {
        return lastSyncCount;
    }

    public void setLastSyncCount(Integer lastSyncCount) {
        this.lastSyncCount = lastSyncCount;
    }

    public String getSyncCron() {
        return syncCron;
    }

    public void setSyncCron(String syncCron) {
        this.syncCron = syncCron;
    }

    public Date getNextFireAt() {
        return nextFireAt;
    }

    public void setNextFireAt(Date nextFireAt) {
        this.nextFireAt = nextFireAt;
    }

    public String getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Date createdTime) {
        this.createdTime = createdTime;
    }
}
