package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkloadIdentityAdminServiceTest {
    private final WorkloadIdentityRepository repository = mock(WorkloadIdentityRepository.class);
    private final ExternalApiMapper principals = mock(ExternalApiMapper.class);
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final WorkloadIdentityAdminService service = new WorkloadIdentityAdminService(repository, principals, keys, new ObjectMapper(), false);
    private ExternalPrincipal principal(long id, String type) {
        ExternalPrincipal p = new ExternalPrincipal(); p.setId(id); p.setPrincipalType(type); p.setStatus("ACTIVE"); return p;
    }
    private Map<String,Object> policy() {
        Map<String,Object> p = new HashMap<String,Object>(); p.put("application_principal_id", 1L); p.put("status", "ACTIVE"); p.put("installation_id", "install");
        when(repository.policy(7L)).thenReturn(p); when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION")); return p;
    }
    @Test void issuingStrictKeySetsPolicyAtomicallyAndDoesNotAuditSecret() {
        policy(); Map<String,Object> issued = new HashMap<String,Object>(); issued.put("id", 2L); issued.put("secret", "only-once-secret");
        when(keys.createServiceKey(anyLong(), any(), anyList(), any(), any(), any(), anyLong())).thenReturn(issued);
        ExternalKeyRequest request = new ExternalKeyRequest(); request.setExpiresAt(LocalDateTime.now().plusDays(1).toString());
        Map<String,Object> result = service.createKey(7L, request, 9);
        assertEquals("APPLICATION_WORKLOAD", result.get("identityMode")); assertEquals(7L, result.get("workloadPolicyId"));
        verify(repository).configureKey(2L, 7L);
        verify(repository).audit(eq(7L), eq("CREATE_KEY"), eq(9L), eq("{\"keyId\":2}"));
    }
    @Test void rejectsUnboundedExpiryAndWriteScopesBeforeCreatingKey() {
        policy(); ExternalKeyRequest request = new ExternalKeyRequest();
        assertThrows(IllegalArgumentException.class, () -> service.createKey(7, request, 9));
        request.setExpiresAt(LocalDateTime.now().plusDays(1).toString()); request.setScopes(Collections.singletonList("MEMORY_MANAGE"));
        assertThrows(IllegalArgumentException.class, () -> service.createKey(7, request, 9));
        verifyNoInteractions(keys);
    }
    @Test void productionPoliciesRejectInsecureJwksAndAgentIdentityMustMatch() {
        when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION"));
        Map<String,Object> body = new HashMap<String,Object>(); body.put("applicationPrincipalId", 1L); body.put("issuer", "https://identity.example"); body.put("jwksUri", "http://127.0.0.1/jwks");
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy(body, 9));
        policy(); body.clear(); body.put("externalAgentId", "external-a"); body.put("runtimeAgentKey", "build"); body.put("agentPrincipalId", 3L);
        when(principals.getPrincipal(3L)).thenReturn(principal(3, "AGENT"));
        assertThrows(IllegalArgumentException.class, () -> service.bind(7, body, 9));
        verify(repository, never()).createBinding(anyLong(), anyString(), anyString(), anyLong());
    }
}
