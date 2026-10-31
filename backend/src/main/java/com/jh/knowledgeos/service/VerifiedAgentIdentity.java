package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import java.util.LinkedHashMap;
import java.util.Map;

/** Public view of the verified request identity. Never derive identity from tool arguments. */
public final class VerifiedAgentIdentity {
    private VerifiedAgentIdentity() { }

    public static Map<String, Object> describe(ApiKey key) {
        WorkloadIdentity identity = key == null ? null : key.getWorkloadIdentity();
        if (identity == null || !AgentProofCrypto.V2.equals(identity.getProofProfile())) {
            throw new SecurityException("仅支持已验签的 V2 Agent 凭据");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("agentId", identity.getExternalAgentId());
        result.put("agentPrincipalId", identity.getAgentPrincipalId());
        result.put("applicationPrincipalId", key.getPrincipalId());
        result.put("policyId", key.getWorkloadPolicyId());
        result.put("runtimeAgentKey", identity.getRuntimeAgentKey());
        // Signed host assertion for diagnostics; authorization uses the registered binding above.
        result.put("terminalAgentName", identity.getTerminalAgentName());
        result.put("kid", identity.getSigningKid());
        result.put("proofProfile", identity.getProofProfile());
        result.put("keyStatus", identity.getKeyStatus());
        result.put("platformId", identity.getPlatformId());
        result.put("sessionId", identity.getSessionId());
        result.put("invocationId", identity.getInvocationId());
        return result;
    }
}
