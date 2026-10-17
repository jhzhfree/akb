package com.jh.knowledgeos.dto;

/**
 * Request to revoke one embed token.
 *
 * <p>Only the token id is strictly required; the rest is what the console already knows from the
 * call log row the operator clicked, and carrying it into the revocation row is what lets the list
 * stay readable weeks later, when the audit window has moved on.</p>
 */
public class EmbedTokenRevokeRequest {

    private String jti;

    private Long keyId;

    private String keyCode;

    private Long knowledgeBaseId;

    private String origin;

    private String reason;

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
}
