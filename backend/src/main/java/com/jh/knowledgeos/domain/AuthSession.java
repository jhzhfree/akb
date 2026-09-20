package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/** Server-side login session; the token is the only credential the client stores. */
public class AuthSession {
    private Long id;
    private String token;
    private Long userId;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime lastAccessedAt;
    private String status;
    private String userAgent;
    private LocalDateTime createdTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getToken() { return token; }
    public void setToken(String v) { token = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { userId = v; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime v) { issuedAt = v; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime v) { expiresAt = v; }
    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(LocalDateTime v) { lastAccessedAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String v) { userAgent = v; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime v) { createdTime = v; }
}
