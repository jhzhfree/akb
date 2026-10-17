package com.jh.knowledgeos.domain;

import java.time.LocalDateTime;

/**
 * One revoked embed token: the token id that must no longer be accepted, and why.
 *
 * <p>Embed tokens are self-contained and never stored when issued, so revocation is the one thing
 * that has to be written down. Keeping the row also keeps the answer to "who killed this token and
 * when", which is what an incident review asks first.</p>
 */
public class EmbedTokenRevocation {

    private Long id;

    private String jti;

    private Long keyId;

    private String keyCode;

    private Long knowledgeBaseId;

    private String origin;

    private String reason;

    private Long operatorId;

    private LocalDateTime revokedTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public Long getKeyId() {
        return keyId;
    }

    public void setKeyId(Long keyId) {
        this.keyId = keyId;
    }

    public String getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(String keyCode) {
        this.keyCode = keyCode;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(Long knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(Long operatorId) {
        this.operatorId = operatorId;
    }

    public LocalDateTime getRevokedTime() {
        return revokedTime;
    }

    public void setRevokedTime(LocalDateTime revokedTime) {
        this.revokedTime = revokedTime;
    }
}
