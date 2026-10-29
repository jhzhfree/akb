package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class AgentKeyAdminServiceTest {
    static RSAKey rsa;
    static final ObjectMapper JSON = new ObjectMapper();
    WorkloadIdentityRepository repository;
    AgentKeyAdminService service;
    AtomicReference<Map<String,Object>> saved;
    @BeforeAll static void key() throws Exception { rsa = AgentKeyProofVerifierTest.key(); }
    @BeforeEach void setup() {
        repository = mock(WorkloadIdentityRepository.class); service = new AgentKeyAdminService(repository,JSON);
        saved = new AtomicReference<Map<String,Object>>();
        when(repository.policy(17L)).thenReturn(AgentKeyProofVerifierTest.map("status","ACTIVE","proof_profile",AgentProofCrypto.V2));
        when(repository.bindingById(eq(17L),eq(21L),anyBoolean())).thenReturn(AgentKeyProofVerifierTest.map("external_agent_id",AgentKeyProofVerifierTest.A,"runtime_agent_key","build"));
        when(repository.binding(17L,AgentKeyProofVerifierTest.A,"build")).thenReturn(Collections.<String,Object>emptyMap());
        doAnswer(call -> {
            saved.set(AgentKeyProofVerifierTest.map("challenge_id",call.getArgument(0),"policy_id",17L,"binding_id",21L,"public_jwk",call.getArgument(3),
                "nonce_hash",call.getArgument(5),"expires_at",Timestamp.from(call.getArgument(6)),"created_by",5L)); return null;
        }).when(repository).createChallenge(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString(),any(),anyLong());
        when(repository.challenge(anyString())).thenAnswer(call -> saved.get());
        when(repository.consumeChallenge(anyString())).thenReturn(1);
        when(repository.registerKey(eq(17L),eq(21L),anyString(),anyString(),eq(5L))).thenReturn(AgentKeyProofVerifierTest.map("kid",rsa.getKeyID(),"status","ACTIVE"));
    }
    Map<String,Object> create() { return service.challenge(17L,21L,AgentKeyProofVerifierTest.map("publicJwk",rsa.toPublicJWK().toJSONObject()),5L); }
    String proof(Map<String,Object> challenge) throws Exception {
        long now = Instant.now().getEpochSecond();
        Map<String,Object> claims = AgentKeyProofVerifierTest.map("ver",2,"purpose","akb.agent-key.register","aud","akb-agent-key-registration",
            "challenge_id",challenge.get("challengeId"),"nonce",challenge.get("nonce"),"policy_id","17","binding_id","21",
            "sub","agent:"+AgentKeyProofVerifierTest.A,"jkt",rsa.getKeyID(),"iat",now,"exp",Math.min(now+300,((Number)challenge.get("expiresAt")).longValue()));
        Base64.Encoder encoder=Base64.getUrlEncoder().withoutPadding();
        String header=JSON.writeValueAsString(AgentKeyProofVerifierTest.map("alg","RS256","typ","akb-key-registration+jwt","kid",rsa.getKeyID()));
        String input=encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))+"."+encoder.encodeToString(JSON.writeValueAsBytes(claims));
        Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(rsa.toRSAPrivateKey()); signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input+"."+encoder.encodeToString(signer.sign());
    }
    @Test void challengeAndProofRegisterOnlyTheApprovedPublicKey() throws Exception {
        Map<String,Object> challenge=create();
        assertEquals(43,((String)challenge.get("nonce")).length());
        Map<String,Object> result=service.register(17L,21L,AgentKeyProofVerifierTest.map("challengeId",challenge.get("challengeId"),"registrationProof",proof(challenge)),5L);
        assertEquals(rsa.getKeyID(),result.get("kid"));
        org.mockito.InOrder order=inOrder(repository);
        order.verify(repository).consumeChallenge((String)challenge.get("challengeId"));
        order.verify(repository).registerKey(eq(17L),eq(21L),eq(rsa.getKeyID()),anyString(),eq(5L));
        verify(repository,atLeastOnce()).audit(eq(17L),anyString(),eq(5L),argThat(detail -> !detail.contains("nonce") && !detail.contains("PRIVATE") && !detail.contains("registrationProof")));
    }
    @Test void rejectsWrongOperatorExpiredConsumedAndMismatchedChallenges() throws Exception {
        Map<String,Object> challenge=create();
        Map<String,Object> body=AgentKeyProofVerifierTest.map("challengeId",challenge.get("challengeId"),"registrationProof",proof(challenge));
        assertThrows(IllegalArgumentException.class,()->service.register(17L,21L,body,6L));
        saved.get().put("expires_at",Timestamp.from(Instant.now().minusSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->service.register(17L,21L,body,5L));
        saved.get().put("expires_at",Timestamp.from(Instant.now().plusSeconds(300))); saved.get().put("consumed_at",Timestamp.from(Instant.now()));
        assertThrows(IllegalArgumentException.class,()->service.register(17L,21L,body,5L));
        verify(repository,never()).consumeChallenge(anyString());
    }
    @Test void rejectsPrivateJwksAndEnforcesEnrollmentCaps() {
        assertThrows(IllegalArgumentException.class,()->service.challenge(17L,21L,AgentKeyProofVerifierTest.map("publicJwk",rsa.toJSONObject()),5L));
        when(repository.pendingChallenges(21L)).thenReturn(5);
        assertThrows(IllegalArgumentException.class,()->create());
        verify(repository,never()).createChallenge(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyString(),any(),anyLong());
    }
    @Test void statusCannotReactivateKeysOrUseUnboundedRetirement() {
        assertThrows(IllegalArgumentException.class,()->service.status(17L,21L,rsa.getKeyID(),AgentKeyProofVerifierTest.map("status","ACTIVE"),5L));
        assertThrows(IllegalArgumentException.class,()->service.status(17L,21L,rsa.getKeyID(),AgentKeyProofVerifierTest.map("status","RETIRING","validUntil",Instant.now().plusSeconds(90000).toString()),5L));
        when(repository.keyStatus(eq(17L),eq(21L),eq(rsa.getKeyID()),eq("REVOKED"),isNull(),eq(5L),isNull())).thenReturn(1);
        service.status(17L,21L,rsa.getKeyID(),AgentKeyProofVerifierTest.map("status","REVOKED"),5L);
    }
}
