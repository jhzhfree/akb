package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One durable occurrence on the platform, published to SSE subscribers and replayable by event id.
 *
 * <p>The {@code detail} map is deliberately coarse from the database's point of view (a JSON blob)
 * and filtered per subscriber before leaving the process: publishers attach the full payload, and
 * the bus decides how much of it a given credential is allowed to see.</p>
 */
public class PlatformEvent {

    private Long id;

    private String eventId;

    private LocalDateTime eventTime;

    private String eventType;

    private Long knowledgeBaseId;

    private String source;

    private String actorType;

    private String actorId;

    private String actorName;

    private String targetType;

    private String targetCode;

    private String summary;

    private String detailJson;

    private Map<String, Object> detail;

    private String traceId;

    private String taskId;

    private String objectId;

    private String objectVersion;

    private String idempotencyKey;

    private Integer retryCount;

    private LocalDateTime createdTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String v) {
        this.eventId = v;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(LocalDateTime v) {
        this.eventTime = v;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String v) {
        this.eventType = v;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long v) {
        this.knowledgeBaseId = v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String v) {
        this.source = v;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String v) {
        this.actorType = v;
    }

    public String getActorId() {
        return actorId;
    }

    public void setActorId(String v) {
        this.actorId = v;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String v) {
        this.actorName = v;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String v) {
        this.targetType = v;
    }

    public String getTargetCode() {
        return targetCode;
    }

    public void setTargetCode(String v) {
        this.targetCode = v;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String v) {
        this.summary = v;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String v) {
        this.detailJson = v;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> v) {
        this.detail = v;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String v) {
        this.traceId = v;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String v) {
        this.taskId = v;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String v) {
        this.objectId = v;
    }

    public String getObjectVersion() {
        return objectVersion;
    }

    public void setObjectVersion(String v) {
        this.objectVersion = v;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String v) {
        this.idempotencyKey = v;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer v) {
        this.retryCount = v;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime v) {
        this.createdTime = v;
    }
}
