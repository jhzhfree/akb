package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/**
 * One call to the external interface-service layer, recorded for metering and traceability.
 */
public class ApiCallAudit {

    public static final String CHANNEL_REST = "REST";

    public static final String CHANNEL_MCP = "MCP";

    public static final String CHANNEL_SSE = "SSE";

    private Long id;

    private Long keyId;

    private String keyCode;

    /**
     * Name the caller gave its own credential: what an operator recognises in the call log.
     */
    private String callerName;

    /**
     * Id of the embed token that made the call: which page, not just which key.
     */
    private String tokenJti;

    /**
     * Site the call came from, as recorded on the token or read from the request.
     */
    private String origin;

    /**
     * 调用方 IP。凭证只说到"哪个 Key 在调用"，IP 才说到"从哪台机器来"——同一枚 Key 被
     * 复制到多处、或被非预期环境使用时，这是唯一能把两者区分开的字段。
     *
     * <p>取自 {@code X-Forwarded-For} / {@code X-Real-IP}，都没有才用 socket 地址。前者可被
     * 伪造，所以这个值只在"请求确实经过了可信网关/反向代理"时才等同于真实来源。</p>
     */
    private String callerIp;

    private String channel;

    private String method;

    private Long knowledgeBaseId;

    /**
     * Sanitised, bounded copy of what the caller sent: query string, JSON body, or both.
     */
    private String requestParams;

    private Integer statusCode;

    /**
     * Sanitised, bounded copy of what we answered. Streams are not captured.
     */
    private String responseData;

    private Integer durationMs;

    private String traceId;

    private String message;

    private LocalDateTime createdTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKeyId() {
        return keyId;
    }

    public void setKeyId(Long v) {
        keyId = v;
    }

    public String getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(String v) {
        keyCode = v;
    }

    public String getCallerName() {
        return callerName;
    }

    public void setCallerName(String v) {
        callerName = v;
    }

    public String getTokenJti() {
        return tokenJti;
    }

    public void setTokenJti(String tokenJti) {
        this.tokenJti = tokenJti;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getCallerIp() {
        return callerIp;
    }

    public void setCallerIp(String callerIp) {
        this.callerIp = callerIp;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String v) {
        channel = v;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String v) {
        method = v;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long v) {
        knowledgeBaseId = v;
    }

    public String getRequestParams() {
        return requestParams;
    }

    public void setRequestParams(String v) {
        requestParams = v;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer v) {
        statusCode = v;
    }

    public String getResponseData() {
        return responseData;
    }

    public void setResponseData(String v) {
        responseData = v;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer v) {
        durationMs = v;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String v) {
        traceId = v;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String v) {
        message = v;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime v) {
        createdTime = v;
    }
}
