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
import org.mockito.InOrder;

class SimpleAgentConnectionServiceTest {
    WorkloadIdentityRepository repo;
    WorkloadIdentityAdminService workload;
    ApiKeyService keys;
    SimpleAgentConnectionService service;
    ExternalApiMapper principals;
    Map<String,Object> policy;
    static final String AGENT="e21cce9c-1b16-4ab8-a76a-bb761df919b8";
    @BeforeEach void setup() {
        repo=mock(WorkloadIdentityRepository.class);keys=mock(ApiKeyService.class);
        principals=mock(ExternalApiMapper.class);
        workload=mock(WorkloadIdentityAdminService.class);
        service=new SimpleAgentConnectionService(repo,workload,keys,principals,mock(ExternalRateLimiter.class));
        policy=new LinkedHashMap<>();policy.put("authorization_mode","SERVICE_SCOPE");policy.put("simple_mode",true);policy.put("proof_profile",AgentProofCrypto.V2);policy.put("status","ACTIVE");
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
    @Test void advancedPolicyIsVisibleWithoutChangingItsAuthorizationMode() {
        policy.put("simple_mode",false);policy.put("authorization_mode","AGENT_INTERSECTION");
        when(repo.policies()).thenReturn(Collections.singletonList(policy));
        assertSame(policy, service.require(17));
        assertEquals("AGENT_INTERSECTION",service.list().get(0).get("authorization_mode"));
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
    @Test void checklistRequiresAKeyOnTheSameActiveAgentAndHonorsValidity() {
        com.jh.knowledgeos.domain.ExternalPrincipal agent = new com.jh.knowledgeos.domain.ExternalPrincipal(); agent.setStatus("ACTIVE");
        when(principals.getPrincipal(anyLong())).thenReturn(agent);
        Map<String,Object> a=new LinkedHashMap<>();a.put("id",21L);a.put("agent_principal_id",10L);a.put("status","ACTIVE");
        Map<String,Object> b=new LinkedHashMap<>();b.put("id",22L);b.put("agent_principal_id",11L);b.put("status","SUSPENDED");
        when(repo.bindings(17)).thenReturn(Arrays.asList(a,b));
        when(repo.agentKeys(17,22)).thenReturn(Collections.singletonList(Collections.singletonMap("status","ACTIVE")));
        assertFalse((Boolean)service.checklist(17).get(4).get("ok"));
        Map<String,Object> key=new LinkedHashMap<>();key.put("status","ACTIVE");key.put("valid_until",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        when(repo.agentKeys(17,21)).thenReturn(Collections.singletonList(key));
        assertFalse((Boolean)service.checklist(17).get(4).get("ok"));
        key.put("valid_until",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(120)));
        assertTrue((Boolean)service.checklist(17).get(4).get("ok"));
        key.put("valid_from",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(60)));
        assertFalse((Boolean)service.checklist(17).get(4).get("ok"));
    }
    @Test void authorizationReviewIsExplicitAuditedAndNeverAutomaticallyEnables() {
        policy.put("authorization_mode","REVIEW_REQUIRED");
        Map<String,Object> body=new LinkedHashMap<>();body.put("authorizationMode","AGENT_INTERSECTION");body.put("reason","Verified pre-upgrade grants");body.put("platformType","octop");
        when(repo.reviewAuthorization(17,"AGENT_INTERSECTION","octop")).thenReturn(1);
        service.reviewAuthorization(17,body,9);
        verify(repo).audit(eq(17L),eq("REVIEW_AUTHORIZATION"),eq(9L),contains("AGENT_INTERSECTION"));
        verify(repo,never()).policyStatus(anyLong(),anyString());
        when(repo.reviewAuthorization(17,"AGENT_INTERSECTION","octop")).thenReturn(0);
        assertThrows(IllegalArgumentException.class,()->service.reviewAuthorization(17,body,9));
    }

    @Test void updateRenamesThePrincipalAndRevalidatesTheOrigin() {
        when(workload.canonicalResourceOrigin("https://new.example")).thenReturn("https://new.example");
        Map<String,Object> body=new LinkedHashMap<>();body.put("name","Renamed");body.put("resourceOrigin","https://new.example");
        service.update(17,body,9);
        verify(keys).updatePrincipal(123L,"Renamed",null,null);
        verify(repo).updateResourceOrigin(17L,"https://new.example");
        verify(repo).audit(eq(17L),eq("UPDATE_CONNECTION"),eq(9L),contains("https://new.example"));
    }
    /** 撤销不是删除：策略进终态，共享 Key 全部吊销，但记录都还在。 */
    @Test void revokeTakesTheConnectionTerminalAndRevokesItsSharedKeys() {
        Map<String,Object> key=new LinkedHashMap<>();key.put("id",55L);key.put("status","ACTIVE");
        Map<String,Object> already=new LinkedHashMap<>();already.put("id",56L);already.put("status","REVOKED");
        when(keys.listPrincipalKeys(123L)).thenReturn(Arrays.asList(key,already));
        service.revoke(17,9);
        verify(workload).status(eq(17L),isNull(),anyMap(),eq(9L));
        verify(keys).revoke(55L);
        verify(keys,never()).revoke(56L);
        verify(repo).audit(eq(17L),eq("REVOKE_CONNECTION"),eq(9L),anyString());
    }
    /** 彻底删除只对已撤销的接入生效，且审计先落盘——顺序反了就没有留痕了。 */
    @Test void purgeOnlyDeletesWhatHasAlreadyBeenRevokedAndAuditsFirst() {
        assertThrows(IllegalArgumentException.class,()->service.purge(17,9));
        verify(repo,never()).purgePolicy(anyLong());
        policy.put("status","REVOKED");
        service.purge(17,9);
        InOrder order=inOrder(repo);
        order.verify(repo).audit(eq(17L),eq("PURGE_CONNECTION"),eq(9L),anyString());
        order.verify(repo).detachKeys(17L);
        order.verify(repo).purgePolicy(17L);
        verify(keys).updatePrincipal(123L,null,"DISABLED",null);
    }
    /** 日志按这条接入的应用主体筛；条数上限在服务端钳住，免得前端传个很大的数把库拖住。 */
    @Test void callsAreScopedToTheConnectionsPrincipalAndCapped() {
        when(principals.listApiCallAudits(any(),anyLong(),anyInt())).thenReturn(Collections.emptyList());
        service.calls(17,500);
        verify(principals).listApiCallAudits(null,123L,200);
    }
}
