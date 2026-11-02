package com.jh.knowledgeos.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, secret-free snapshot built only from the authenticated credential. */
public final class VerifiedCaller {
    private final Long servicePrincipalId;
    private final Long credentialId;
    private final WorkloadIdentity agent;
    private final boolean agentVerificationRequired;
    private final String traceId;

    private VerifiedCaller(ApiKey key, String traceId) {
        this.servicePrincipalId = key.getPrincipalId();
        this.credentialId = key.getId();
        this.agent = key.isWorkloadCredential() ? key.getWorkloadIdentity() : null;
        this.agentVerificationRequired = key.isWorkloadCredential();
        this.traceId = traceId != null && traceId.matches("[A-Za-z0-9_-]{1,128}") ? traceId : null;
    }
    public static VerifiedCaller fromAuthenticated(ApiKey key, String traceId) {
        if (key == null) throw new SecurityException("Authenticated credential required");
        return new VerifiedCaller(key, traceId);
    }
    public String getActorType() { return agent == null ? "service" : "agent"; }
    public WorkloadIdentity getAgent() { return agent; }
    public Long getServicePrincipalId() { return servicePrincipalId; }
    public String getTraceId() { return traceId; }
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actorType", getActorType()); out.put("servicePrincipalId", servicePrincipalId);
        out.put("credentialId", credentialId); out.put("agentVerificationRequired", agentVerificationRequired);
        out.put("traceId", traceId);
        if (agent != null) {
            out.put("agentId", agent.getExternalAgentId()); out.put("agentPrincipalId", agent.getAgentPrincipalId());
            out.put("kid", agent.getSigningKid()); out.put("proofProfile", agent.getProofProfile());
            out.put("runtimeAgentKey", agent.getRuntimeAgentKey()); out.put("terminalAgentName", agent.getTerminalAgentName());
            out.put("sessionId", agent.getSessionId()); out.put("invocationId", agent.getInvocationId());
        }
        return Collections.unmodifiableMap(out);
    }
}
