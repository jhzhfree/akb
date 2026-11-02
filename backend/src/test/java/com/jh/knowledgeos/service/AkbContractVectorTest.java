package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AkbContractVectorTest {
    @Test @SuppressWarnings("unchecked") void publicExpiredVectorVerifiesWithoutWeakeningTheLiveExpiryCheck() throws Exception {
        Map<String,Object> vector = new ObjectMapper().readValue(getClass().getResourceAsStream("/akb-v2-vector.json"), Map.class);
        AgentProofCrypto.Proof proof = AgentProofCrypto.parse((String)vector.get("proof"), "akb-agent-proof+jwt");
        proof.verify(AgentProofCrypto.publicKey((Map<String,Object>)vector.get("publicJwk")));
        assertEquals(2, ((Number)proof.claims.get("ver")).intValue());
        Map<String,Object> binding=(Map<String,Object>)proof.claims.get("request_binding");
        assertEquals(vector.get("target"), binding.get("target"));
        assertEquals(vector.get("bodySha256"), WorkloadIdentityService.sha256(((String)vector.get("body")).getBytes(StandardCharsets.UTF_8)));
        assertEquals(vector.get("bodySha256"), binding.get("body_sha256"));
        assertThrows(IllegalArgumentException.class, () -> AgentProofCrypto.expiry(proof.claims,60));
        assertThrows(IllegalArgumentException.class, () -> AgentProofCrypto.parse((String)vector.get("proof"), "mcp-agent-proof+jwt"));
    }
}
