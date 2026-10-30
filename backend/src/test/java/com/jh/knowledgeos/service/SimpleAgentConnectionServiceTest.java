package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.*;
import org.junit.jupiter.api.*;

class SimpleAgentConnectionServiceTest {
    WorkloadIdentityRepository repo;
    ApiKeyService keys;
    SimpleAgentConnectionService service;
    Map<String,Object> policy;
    static final String AGENT="e21cce9c-1b16-4ab8-a76a-bb761df919b8";
    @BeforeEach void setup() {
        repo=mock(WorkloadIdentityRepository.class);keys=mock(ApiKeyService.class);
        service=new SimpleAgentConnectionService(repo,mock(WorkloadIdentityAdminService.class),keys,mock(ExternalApiMapper.class),mock(ExternalRateLimiter.class));
        policy=new LinkedHashMap<>();policy.put("simple_mode",true);policy.put("proof_profile",AgentProofCrypto.V2);policy.put("status","ACTIVE");
        policy.put("application_principal_id",123L);policy.put("resource_origin","https://akb.example");
        policy.put("issuer","https://akb.local/agent-identity");policy.put("audience","akb");policy.put("platform_id","akb-service");policy.put("installation_id","install");policy.put("workspace_id","workspace");
        when(repo.policy(17)).thenReturn(policy);
        when(repo.binding(anyLong(),anyString(),anyString())).thenReturn(null);
        when(repo.agentKey(anyLong(),anyLong(),anyString())).thenReturn(null);
    }
    @Test void discoveryRequiresStrictUsableKeyAndActiveRegisteredAgent() {
        assertThrows(RuntimeException.class,()->service.configuration("missing",AGENT));
        ApiKey key=new ApiKey();key.setPrincipalId(123L);key.setPrincipalType("APPLICATION");key.setIdentityMode("APPLICATION_WORKLOAD");key.setWorkloadPolicyId(17L);
        when(keys.authenticate("shared")).thenReturn(key);
        assertThrows(SecurityException.class,()->service.configuration("shared",AGENT));
        when(repo.binding(17,AGENT,AGENT)).thenReturn(Collections.singletonMap("id",21L));
        Map<String,Object> result=service.configuration("shared",AGENT);
        assertEquals("17",result.get("policyId"));assertEquals(AGENT,result.get("runtimeAgentKey"));assertFalse(result.containsKey("secret"));assertFalse(result.containsKey("publicJwk"));
        policy.put("status","SUSPENDED");assertThrows(RuntimeException.class,()->service.configuration("shared",AGENT));
    }
    @Test void advancedPolicyCannotUseSimplifiedEnrollmentOrInheritance() {
        policy.put("simple_mode",false);assertThrows(IllegalArgumentException.class,()->service.require(17));
        verify(repo,never()).registerKey(anyLong(),anyLong(),anyString(),anyString(),anyLong());
    }
    @Test void administratorRegistersPublicKeyWithoutChallengeButCannotResurrectIt() throws Exception {
        RSAKey key=AgentKeyProofVerifierTest.key();
        Map<String,Object> binding=new LinkedHashMap<>();binding.put("id",21L);binding.put("external_agent_id",AGENT);binding.put("status","ACTIVE");
        when(repo.bindings(17)).thenReturn(Collections.singletonList(binding));when(repo.bindingById(17,21,true)).thenReturn(binding);
        Map<String,Object> body=new LinkedHashMap<>();body.put("name","Alpha");body.put("agentId",AGENT);body.put("publicJwk",key.toPublicJWK().toJSONObject());
        service.register(17,body,9);
        verify(repo).registerKey(eq(17L),eq(21L),eq(key.getKeyID()),anyString(),eq(9L));
        when(repo.agentKey(17,21,key.getKeyID())).thenReturn(Collections.singletonMap("status","REVOKED"));
        assertThrows(IllegalArgumentException.class,()->service.register(17,body,9));
        body.put("publicJwk",key.toJSONObject());assertThrows(RuntimeException.class,()->service.register(17,body,9));
    }
    @Test void emptyScopeIsRejectedRatherThanGrantingAllKnowledge() {
        assertThrows(IllegalArgumentException.class,()->service.scope(17,Collections.singletonMap("knowledgeBaseIds",Collections.emptyList()),9));
        verifyNoInteractions(keys);
    }
}
