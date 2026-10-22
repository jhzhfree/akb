package com.jh.knowledgeos.dto;

/** Assign an API Key to an existing principal, or create and assign a new principal. */
public class ExternalPrincipalAssignmentRequest {

    private Long principalId;
    private String name;
    private String reason;

    public Long getPrincipalId() { return principalId; }
    public void setPrincipalId(Long principalId) { this.principalId = principalId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
