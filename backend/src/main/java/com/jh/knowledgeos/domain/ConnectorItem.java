package com.jh.knowledgeos.domain;

import java.util.Date;
import java.util.Map;

/**
 * One item an external system listed, kept so it can be browsed by type and attributes.
 *
 * <p>{@code attributes} is not a column: it is the parsed {@code attributes_json}, filled in by the
 * service the same way {@link ConnectorSource#getConfig} is. Keeping the parse in one place means
 * the console never has to know whether an attribute came from Jira's {@code fields.status.name} or
 * from a generic REST field.</p>
 */
public class ConnectorItem {

    private Long id;

    private Long connectorId;

    private String itemType;

    private String externalId;

    private String title;

    private String itemUrl;

    private String excerpt;

    private String attributesJson;

    private Map<String, Object> attributes;

    private String rawJson;

    private Date remoteUpdatedAt;

    private Date syncedTime;

    private Date createdTime;

    private Date updatedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConnectorId() {
        return connectorId;
    }

    public void setConnectorId(Long connectorId) {
        this.connectorId = connectorId;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getItemUrl() {
        return itemUrl;
    }

    public void setItemUrl(String itemUrl) {
        this.itemUrl = itemUrl;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    public String getAttributesJson() {
        return attributesJson;
    }

    public void setAttributesJson(String attributesJson) {
        this.attributesJson = attributesJson;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public String getRawJson() {
        return rawJson;
    }

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    public Date getRemoteUpdatedAt() {
        return remoteUpdatedAt;
    }

    public void setRemoteUpdatedAt(Date remoteUpdatedAt) {
        this.remoteUpdatedAt = remoteUpdatedAt;
    }

    public Date getSyncedTime() {
        return syncedTime;
    }

    public void setSyncedTime(Date syncedTime) {
        this.syncedTime = syncedTime;
    }

    public Date getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(Date createdTime) {
        this.createdTime = createdTime;
    }

    public Date getUpdatedTime() {
        return updatedTime;
    }

    public void setUpdatedTime(Date updatedTime) {
        this.updatedTime = updatedTime;
    }
}
