package com.jh.knowledgeos.domain;

/** Explicit link between an external system user ID and an AKB USER principal. */
public class ExternalIdentityBinding {
    private Long id;
    private String provider;
    private String providerInstanceId;
    private String externalUserId;
    private Long principalId;
    private Long operatorId;
    private String reason;

    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getProvider() { return provider; }
    public void setProvider(String value) { provider = value; }
    public String getProviderInstanceId() { return providerInstanceId; }
    public void setProviderInstanceId(String value) { providerInstanceId = value; }
    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String value) { externalUserId = value; }
    public Long getPrincipalId() { return principalId; }
    public void setPrincipalId(Long value) { principalId = value; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long value) { operatorId = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
}
