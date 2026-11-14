package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real scope/service/facade authorization; no database, network or AI connections. */
class ExternalMemoryScopeBoundaryTest {
    private final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ExternalAccessService access = new ExternalAccessService(null, null, null, null, null, new KnowledgeProperties());
    private final AgentMemoryService memory = new AgentMemoryService(mapper, access, new ObjectMapper());
    private final ExternalKnowledgeFacade facade = new ExternalKnowledgeFacade(knowledge, mock(KnowledgeBaseService.class),
        new ObjectMapper(), mock(RetrievalAuthorizationService.class), memory);

    private ApiKey key() {
        ApiKey k = new ApiKey(); k.setId(1L); k.setKeyCode("agent-a"); k.setPrincipalId(10L);
        k.setStatus("ACTIVE"); k.setPrincipalStatus("ACTIVE"); k.setCredentialType("SERVICE_KEY");
        k.setScopes("READ,MEMORY_READ"); return k;
    }
    @Test void knowledgeScopesAndManageAloneDoNotGrantMemoryRead() {
        for (String scopes : new String[]{null, "READ", "WRITE", "MEMORY_MANAGE", "MEMORY_WRITE"}) {
            ApiKey k = key(); k.setScopes(scopes);
            assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        }
        verifyNoInteractions(mapper, knowledge);
    }
    @Test void disabledPolicyAndWorkloadDenyBeforeAnyMemoryLookup() {
        ApiKey k = key(); k.setMemoryReadEnabled(false);
        assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        k.setMemoryReadEnabled(true); k.setIdentityMode("APPLICATION_WORKLOAD");
        assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        // Even a verified workload proof cannot add a capability forbidden by hasScope.
        k.setWorkloadIdentity(new com.jh.knowledgeos.domain.WorkloadIdentity(99L, "s", "i", "proof", "kid", 1L, "m", "p", "a", "ACTIVE"));
        assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        assertFalse(k.hasScope(ApiKey.SCOPE_MEMORY_READ));
        verifyNoInteractions(mapper, knowledge);
    }
    @Test void invalidOrMissingPrincipalAndPatOwnerDeny() {
        assertThrows(SecurityException.class, () -> facade.memoryContext(null, "q"));
        for (String status : new String[]{null, "DISABLED"}) {
            ApiKey k = key(); k.setPrincipalStatus(status);
            assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        }
        ApiKey k = key(); k.setCredentialType("USER_PAT"); k.setOwnerUserId(20L); k.setOwnerUserStatus("DISABLED");
        assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        verifyNoInteractions(mapper, knowledge);
    }
    @Test void missingExactAgentReadGrantCannotReadOrAnswer() {
        when(mapper.hasPrincipalScopeAccess(10L, "key:1", "AGENT", "other-agent", "READ")).thenReturn(true);
        ApiKey k = key();
        assertThrows(SecurityException.class, () -> facade.queryWithMemory(k, 1L, "q", 2, null, null, null, "full"));
        assertThrows(SecurityException.class, () -> facade.retrieval(k, 1L, "q", 2, null, "REST", null, false, true));
        verify(mapper, times(2)).hasPrincipalScopeAccess(10L, "key:1", "AGENT", "agent-a", "READ");
        verify(mapper, never()).hasScopeAccess(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(mapper, never()).recall(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        verifyNoInteractions(knowledge);
    }
    @Test void explicitPrincipalReadGrantSupportsServiceAndPersonalCredentials() {
        when(mapper.hasPrincipalScopeAccess(10L, "key:1", "AGENT", "agent-a", "READ")).thenReturn(true);
        when(mapper.recall("key:1", "agent-a", "AGENT", "agent-a", "q", 8))
            .thenReturn(Collections.singletonList(Collections.<String,Object>singletonMap("content", "safe memory")));
        ApiKey k = key(); assertTrue(facade.memoryContext(k, "q").contains("safe memory"));
        k.setCredentialType("USER_PAT"); k.setOwnerUserId(20L); k.setOwnerUserStatus("ACTIVE");
        assertTrue(facade.memoryContext(k, "q").contains("safe memory"));
        verify(mapper, never()).hasScopeAccess(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
    @Test void principalRevocationCannotBeUndoneByResidualLegacyKeyGrant() {
        when(mapper.hasScopeAccess(1L, "key:1", "AGENT", "agent-a", "READ")).thenReturn(true);
        when(mapper.hasPrincipalScopeAccess(10L, "key:1", "AGENT", "agent-a", "READ")).thenReturn(true);
        ApiKey k = key(); assertEquals("", facade.memoryContext(k, "q"));
        when(mapper.hasPrincipalScopeAccess(10L, "key:1", "AGENT", "agent-a", "READ")).thenReturn(false);
        assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        verify(mapper, never()).hasScopeAccess(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
    @Test void directRecallCannotResurrectRevokedSharedGrantFromLegacyRows() {
        ApiKey k = key();
        com.jh.knowledgeos.dto.MemoryRecallRequest request = new com.jh.knowledgeos.dto.MemoryRecallRequest();
        request.setQuery("q"); request.setScopeType("TEAM"); request.setScopeCode("team-a");
        when(mapper.hasPrincipalScopeAccess(10L, "key:1", "TEAM", "team-a", "READ")).thenReturn(true);
        when(mapper.hasScopeAccess(1L, "key:1", "TEAM", "team-a", "READ")).thenReturn(true);
        memory.recall(k, request);
        when(mapper.hasPrincipalScopeAccess(10L, "key:1", "TEAM", "team-a", "READ")).thenReturn(false);
        assertThrows(SecurityException.class, () -> memory.recall(k, request));
        verify(mapper, never()).hasScopeAccess(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(mapper, times(1)).recall("key:1", "agent-a", "TEAM", "team-a", "q", 10);
    }

    @Test void unboundLegacyDirectRecallRemainsExplicitlyScopedButCannotInjectContext() {
        ApiKey k = key(); k.setPrincipalId(null); k.setPrincipalStatus(null);
        com.jh.knowledgeos.dto.MemoryRecallRequest request = new com.jh.knowledgeos.dto.MemoryRecallRequest();
        request.setQuery("q"); request.setScopeType("TEAM"); request.setScopeCode("team-a");
        when(mapper.hasScopeAccess(1L, "key:1", "TEAM", "team-a", "READ")).thenReturn(true);
        when(mapper.recall("key:1", "agent-a", "TEAM", "team-a", "q", 10))
            .thenReturn(Collections.singletonList(Collections.<String,Object>singletonMap("content", "legacy memory")));
        assertEquals(1, memory.recall(k, request).get("count"));
        verify(mapper).hasScopeAccess(1L, "key:1", "TEAM", "team-a", "READ");
        assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        when(mapper.hasScopeAccess(1L, "key:1", "TEAM", "team-a", "READ")).thenReturn(false);
        assertThrows(SecurityException.class, () -> memory.recall(k, request));
        verify(mapper, times(2)).hasScopeAccess(1L, "key:1", "TEAM", "team-a", "READ");
        verify(mapper, never()).hasPrincipalScopeAccess(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(mapper, times(1)).recall("key:1", "agent-a", "TEAM", "team-a", "q", 10);
        verifyNoInteractions(knowledge);
    }

    @Test void delegatedAndBackendFailuresNeverBecomeEmptySuccess() {
        ApiKey k = key(); k.setDelegatedPrincipalId(20L);
        assertThrows(SecurityException.class, () -> facade.memoryContext(k, "q"));
        verifyNoInteractions(mapper);
        k.setDelegatedPrincipalId(null);
        when(mapper.hasPrincipalScopeAccess(10L, "key:1", "AGENT", "agent-a", "READ")).thenThrow(new IllegalStateException("offline failure"));
        assertThrows(IllegalStateException.class, () -> facade.memoryContext(k, "q"));
        verifyNoInteractions(knowledge);
    }
}
