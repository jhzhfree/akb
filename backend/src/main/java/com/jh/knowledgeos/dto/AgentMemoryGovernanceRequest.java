package com.jh.knowledgeos.dto;

/** Reason required for an operator memory status change or deletion. */
public class AgentMemoryGovernanceRequest {
    private String reason;
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
