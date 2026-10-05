package com.jh.knowledgeos.domain;

import java.util.Date;

/**
 * Which knowledge base document one external catalog item became.
 *
 * <p>After ingestion the item is an ordinary document — same source, chunk, card and claim rows as
 * anything uploaded. This row is the only thing that remembers where it came from, and it exists
 * for one reason: a sync runs repeatedly, and re-running extraction on an unchanged Jira ticket
 * would spend a model call to produce exactly the same cards. The stored content hash answers
 * "did the remote system actually change?" without asking it.</p>
 */
public class ConnectorItemBinding {

    private Long id;

    private Long connectorId;

    private Long connectorItemId;

    private Long knowledgeBaseId;

    private Long sourceId;

    private Long documentId;

    private String contentHash;

    private Date remoteUpdatedAt;

    private String ingestStatus;

    private Date lastIngestTime;

    private String lastIngestMessage;

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

    public Long getConnectorItemId() {
        return connectorItemId;
    }

    public void setConnectorItemId(Long connectorItemId) {
        this.connectorItemId = connectorItemId;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public Long getSourceId() {
        return sourceId;
    }

    public void setSourceId(Long sourceId) {
        this.sourceId = sourceId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Date getRemoteUpdatedAt() {
        return remoteUpdatedAt;
    }

    public void setRemoteUpdatedAt(Date remoteUpdatedAt) {
        this.remoteUpdatedAt = remoteUpdatedAt;
    }

    public String getIngestStatus() {
        return ingestStatus;
    }

    public void setIngestStatus(String ingestStatus) {
        this.ingestStatus = ingestStatus;
    }

    public Date getLastIngestTime() {
        return lastIngestTime;
    }

    public void setLastIngestTime(Date lastIngestTime) {
        this.lastIngestTime = lastIngestTime;
    }

    public String getLastIngestMessage() {
        return lastIngestMessage;
    }

    public void setLastIngestMessage(String lastIngestMessage) {
        this.lastIngestMessage = lastIngestMessage;
    }
}
