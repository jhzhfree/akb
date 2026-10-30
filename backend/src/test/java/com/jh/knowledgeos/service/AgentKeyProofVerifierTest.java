package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.config.WorkloadRequestFilter;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.exception.WorkloadProofException;
import com.jh.knowledgeos.exception.WorkloadUnavailableException;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletRequest;

class AgentKeyProofVerifierTest {
    static final String A = "e21cce9c-1b16-4ab8-a76a-bb761df919b8", B = "01965897-0976-471c-8d64-27eb682a227b";
    static final ObjectMapper JSON = new ObjectMapper();
    static RSAKey a, b;
    WorkloadIdentityRepository repository;
    WorkloadIdentityService service;
    ApiKey credential;
    Map<String, Object> keyA;
    final byte[] body = "{\"query\":\"规则🚀\"}".getBytes(StandardCharsets.UTF_8);

    @BeforeAll static void generateKeys() throws Exception { a = key(); b = key(); }
    static RSAKey key() throws Exception {
        RSAKey key = new RSAKeyGenerator(3072).algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).generate();
        Map<String, Object> value = key.toJSONObject(); value.put("kid", key.computeThumbprint().toString()); return RSAKey.parse(value);
    }
    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); return result;
    }
    @BeforeEach void setup() {
        repository = mock(WorkloadIdentityRepository.class); service = new WorkloadIdentityService(repository);
        credential = new ApiKey(); credential.setPrincipalId(123L); credential.setPrincipalType("APPLICATION");
        credential.setIdentityMode("APPLICATION_WORKLOAD"); credential.setWorkloadPolicyId(17L);
        when(repository.policy(17L)).thenReturn(map("id",17L,"application_principal_id",123L,"status","ACTIVE","proof_profile",AgentProofCrypto.V2,
            "issuer","https://issuer.example/test","audience","akb","platform_id","test-platform","installation_id","install","workspace_id","workspace","resource_origin","https://akb.example"));
        when(repository.binding(17L,A,"build")).thenReturn(map("id",21L,"agent_principal_id",4L));
        when(repository.binding(17L,B,"review")).thenReturn(map("id",22L,"agent_principal_id",5L));
        keyA = map("id",31L,"status","ACTIVE","valid_from",Timestamp.from(Instant.now().minusSeconds(5)),"public_jwk",a.toPublicJWK().toJSONString());
        when(repository.agentKey(17L,21L,a.getKeyID())).thenReturn(keyA);
        when(repository.agentKey(17L,22L,b.getKeyID())).thenReturn(map("id",32L,"status","ACTIVE","public_jwk",b.toPublicJWK().toJSONString()));
    }
    Map<String, Object> claims(String agent) {
        long now = Instant.now().getEpochSecond();
        return map("ver",2,"iss","https://issuer.example/test","aud","akb","resource","https://akb.example","policy_id","17","azp","123",
            "sub","agent:"+agent,"platform_id","test-platform","installation_id","install","workspace_id","workspace","runtime_agent",A.equals(agent)?"build":"review",
            "session_id","session","message_id","message","invocation_id",UUID.randomUUID().toString(),"iat",now,"exp",now+60,"jti",UUID.randomUUID().toString(),
            "credential_sha256",WorkloadIdentityService.sha256("shared-test-key".getBytes(StandardCharsets.UTF_8)),
            "request_binding",map("method","POST","target","/api/mcp","body_sha256",WorkloadIdentityService.sha256(body)));
    }
    static String sign(Map<String, Object> claims, RSAKey key) throws Exception { return signRaw(JSON.writeValueAsString(claims),key); }
    static String signRaw(String claims, RSAKey key) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = JSON.writeValueAsString(map("alg","RS256","typ","akb-agent-proof+jwt","kid",key.getKeyID()));
        String input = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8))+"."+encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key.toRSAPrivateKey()); signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input+"."+encoder.encodeToString(signer.sign());
    }
    MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST","/api/mcp");
        request.addHeader("X-API-Key","shared-test-key"); request.addHeader(WorkloadRequestFilter.HEADER,token);
        request.setAttribute(WorkloadRequestFilter.BODY,body); return request;
    }
    @Test void sameCredentialAuthenticatesTwoIndependentAgentKeys() throws Exception {
        WorkloadIdentity one = service.authenticate(request(sign(claims(A),a)),credential);
        WorkloadIdentity two = service.authenticate(request(sign(claims(B),b)),credential);
        assertEquals(4L,one.getAgentPrincipalId()); assertEquals(5L,two.getAgentPrincipalId());
        assertEquals(a.getKeyID(),one.getSigningKid()); assertEquals(AgentProofCrypto.V2,one.getProofProfile());
        assertEquals("message",one.getMessageId()); assertEquals(123L,credential.getPrincipalId());
    }
    @Test void onlyServerStoredSimpleModeEnablesInheritance() throws Exception {
        assertFalse(service.authenticate(request(sign(claims(A),a)),credential).isServiceGrantsInherited());
        repository.policy(17L).put("simple_mode",true);
        assertTrue(service.authenticate(request(sign(claims(A),a)),credential).isServiceGrantsInherited());
        Map<String,Object> forged=claims(B); forged.put("simple_mode",true);
        assertThrows(WorkloadProofException.class,()->service.authenticate(request(sign(forged,a)),credential));
    }
    @Test void validASignatureCannotImpersonateBAndDoesNotConsumeANonce() throws Exception {
        assertThrows(WorkloadProofException.class,()->service.authenticate(request(sign(claims(B),a)),credential));
        verify(repository,never()).consumeV2(anyLong(),anyString(),anyString(),any());
    }
    @Test void rejectsRequestResourceCredentialAndClaimTampering() throws Exception {
        for (Map<String,Object> overrides : Arrays.asList(map("resource","https://other.example"),map("credential_sha256","0"),map("azp","999"),
            map("ver",1),map("aud",Arrays.asList("akb")),map("platform_id","other"),map("installation_id","other"),map("exp",Instant.now().getEpochSecond()-1),
            map("exp",Instant.now().getEpochSecond()+300),map("iat",Instant.now().getEpochSecond()+300),map("iat",true))) {
            Map<String,Object> c = claims(A); c.putAll(overrides);
            assertThrows(WorkloadProofException.class,()->service.authenticate(request(sign(c,a)),credential));
        }
        MockHttpServletRequest changed = request(sign(claims(A),a)); changed.setAttribute(WorkloadRequestFilter.BODY,new byte[0]);
        assertThrows(WorkloadProofException.class,()->service.authenticate(changed,credential));
        MockHttpServletRequest query = request(sign(claims(A),a)); query.setQueryString("changed=1");
        assertThrows(WorkloadProofException.class,()->service.authenticate(query,credential));
        assertThrows(WorkloadProofException.class,()->service.authenticate(request(signRaw(JSON.writeValueAsString(claims(A)).replace("\"ver\":2","\"ver\":2,\"ver\":2"),a)),credential));
    }
    @Test void rejectsReplaysAndDatabaseFailureIsUnavailable() throws Exception {
        doThrow(new DuplicateKeyException("duplicate")).when(repository).consumeV2(anyLong(),anyString(),anyString(),any());
        assertThrows(WorkloadProofException.class,()->service.authenticate(request(sign(claims(A),a)),credential));
        when(repository.policy(17L)).thenThrow(new DataAccessResourceFailureException("private database details"));
        assertThrows(WorkloadUnavailableException.class,()->service.authenticate(request(sign(claims(A),a)),credential));
    }
    @Test void checksLiveRevocationAndRetirementDeadline() throws Exception {
        keyA.put("status","REVOKED");
        assertThrows(WorkloadProofException.class,()->service.authenticate(request(sign(claims(A),a)),credential));
        keyA.put("status","RETIRING"); keyA.put("valid_until",Timestamp.from(Instant.now().plusSeconds(20)));
        assertThrows(WorkloadProofException.class,()->service.authenticate(request(sign(claims(A),a)),credential));
        Map<String,Object> c = claims(A); c.put("exp",Instant.now().getEpochSecond()+10);
        assertEquals(4L,service.authenticate(request(sign(c,a)),credential).getAgentPrincipalId());
    }
    @Test void v2DoesNotUseJwksOrAcceptRegistrationProofs() throws Exception {
        String token = sign(claims(A),a);
        String[] parts = token.split("\\.");
        String header = new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8).replace("akb-agent-proof+jwt","akb-key-registration+jwt");
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(header.getBytes(StandardCharsets.UTF_8))+"."+parts[1]+"."+parts[2];
        assertThrows(WorkloadProofException.class,()->service.authenticate(request(changed),credential));
    }
}
