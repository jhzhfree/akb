package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/** One call to the external interface-service layer, recorded for metering and traceability. */
public class ApiCallAudit {
    public static final String CHANNEL_REST = "REST";
    public static final String CHANNEL_MCP = "MCP";
    public static final String CHANNEL_SSE = "SSE";

    private Long id;
    private Long keyId;
    private String keyCode;
    private String channel;
    private String method;
    private Long knowledgeBaseId;
    private Integer statusCode;
    private Integer durationMs;
    private String traceId;
    private String message;
    private LocalDateTime createdTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKeyId() { return keyId; }
    public void setKeyId(Long v) { keyId = v; }
    public String getKeyCode() { return keyCode; }
    public void setKeyCode(String v) { keyCode = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { channel = v; }
    public String getMethod() { return method; }
    public void setMethod(String v) { method = v; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long v) { knowledgeBaseId = v; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer v) { statusCode = v; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer v) { durationMs = v; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String v) { traceId = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime v) { createdTime = v; }
}
