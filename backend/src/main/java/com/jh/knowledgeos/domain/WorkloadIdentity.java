package com.jh.knowledgeos.domain;

/** Verified, immutable request identity; credential ownership never changes. */
public final class WorkloadIdentity {
    private final Long agentPrincipalId;
    private final String sessionId;
    private final String invocationId;
    private final String proofProfile, signingKid, messageId, platformId, externalAgentId;
    private final Long keyRegistrationId;
    private final String keyStatus;
    private final boolean serviceGrantsInherited;
    public boolean isServiceGrantsInherited() { return serviceGrantsInherited; }
    public WorkloadIdentity inheritServiceGrants() {
        return new WorkloadIdentity(agentPrincipalId, sessionId, invocationId, proofProfile, signingKid,
            keyRegistrationId, messageId, platformId, externalAgentId, keyStatus, true);
    }

    public WorkloadIdentity(Long agentPrincipalId, String sessionId, String invocationId, String profile, String kid,
        Long registration, String message, String platform, String external, String keyStatus) {
        this(agentPrincipalId, sessionId, invocationId, profile, kid, registration, message, platform, external, keyStatus, false);
    }
    private WorkloadIdentity(Long agentPrincipalId, String sessionId, String invocationId, String profile, String kid,
        Long registration, String message, String platform, String external, String keyStatus, boolean inherited) {
        this.serviceGrantsInherited = inherited;
        this.agentPrincipalId = agentPrincipalId;
        this.sessionId = sessionId;
        this.invocationId = invocationId;
        this.proofProfile = profile; this.signingKid = kid; this.keyRegistrationId = registration;
        this.messageId = message; this.platformId = platform; this.externalAgentId = external;
        this.keyStatus = keyStatus;
    }
    public Long getAgentPrincipalId() { return agentPrincipalId; }
    public String getSessionId() { return sessionId; }
    public String getInvocationId() { return invocationId; }
    public String getProofProfile() { return proofProfile; }
    public String getSigningKid() { return signingKid; }
    public Long getKeyRegistrationId() { return keyRegistrationId; }
    public String getMessageId() { return messageId; }
    public String getPlatformId() { return platformId; }
    public String getExternalAgentId() { return externalAgentId; }
    public String getKeyStatus() { return keyStatus; }
}
