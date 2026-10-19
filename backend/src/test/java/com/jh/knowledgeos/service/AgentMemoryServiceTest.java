package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.dto.MemoryCaptureRequest;
import com.jh.knowledgeos.dto.MemoryRecallRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentMemoryServiceTest {

    private final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    private final ExternalAccessService access = mock(ExternalAccessService.class);
    private final AgentMemoryService service = new AgentMemoryService(mapper, access, new ObjectMapper());

    @Test
    void captureDerivesAgentIdentityAndNamespaceFromCredential() {
        ApiKey key = key();
        MemoryCaptureRequest request = new MemoryCaptureRequest();
        request.setMemoryLevel("L3");
        request.setScopeType("AGENT");
        request.setScopeCode("spoofed-agent");
        request.setContent("Agent should cite verified sources");
        request.setSourceType("USER_CONFIRMED");
        request.setTags(Arrays.asList("preference", "workflow"));
        request.setIdempotencyKey("profile-capture-1");
        Map<String, Object> saved = new LinkedHashMap<String, Object>();
        saved.put("memoryCode", "MEMORY_EXISTING");
        saved.put("scopeType", "AGENT");
        saved.put("scopeCode", "agent-ops");
        saved.put("memoryLevel", "L3");
        saved.put("content", "Agent should cite verified sources");
        when(mapper.findByIdempotency("octop-prod", "agent-ops", "profile-capture-1")).thenReturn(saved);

        Map<String, Object> result = service.capture(key, request);

        assertEquals("agent-ops", result.get("scopeCode"));
        assertEquals("MEMORY_EXISTING", result.get("memoryCode"));
        verify(access).assertScope(key, ApiKey.SCOPE_MEMORY_WRITE);
        verify(mapper).insert(any(String.class), eq("octop-prod"), eq("agent-ops"), eq("AGENT"), eq("agent-ops"), eq("L3"),
            eq("Agent should cite verified sources"), eq("[\"preference\",\"workflow\"]"), eq("USER_CONFIRMED"), eq(null), eq(0.7d), eq(3),
            any(java.time.LocalDateTime.class), eq(41L), eq("profile-capture-1"));
        verify(mapper).findByIdempotency("octop-prod", "agent-ops", "profile-capture-1");
    }

    @Test
    void projectRecallIncludesNamespaceAndAuthenticatedAgentScope() {
        ApiKey key = key();
        MemoryRecallRequest request = new MemoryRecallRequest();
        request.setQuery("deployment rollback");
        request.setScopeType("PROJECT");
        request.setScopeCode("release-2026");
        request.setLimit(12);
        when(mapper.hasScopeAccess(41L, "octop-prod", "PROJECT", "release-2026", "READ")).thenReturn(true);
        when(mapper.recall("octop-prod", "agent-ops", "PROJECT", "release-2026", "deployment rollback", 12))
            .thenReturn(Collections.<Map<String, Object>>singletonList(new LinkedHashMap<String, Object>()));

        Map<String, Object> result = service.recall(key, request);

        verify(access).assertScope(key, ApiKey.SCOPE_MEMORY_READ);
        assertEquals(1, result.get("count"));
    }

    @Test
    void sharedCaptureAndRecallRequireExactCredentialGrant() {
        ApiKey key = key();
        MemoryCaptureRequest capture = new MemoryCaptureRequest();
        capture.setMemoryLevel("L2"); capture.setScopeType("PROJECT"); capture.setScopeCode("release-2026");
        capture.setContent("Deployment rollback verified"); capture.setSourceType("TASK_RESULT"); capture.setSourceRef("run-44");
        assertThrows(SecurityException.class, () -> service.capture(key, capture));
        verify(mapper).hasScopeAccess(41L, "octop-prod", "PROJECT", "release-2026", "WRITE");
        verify(mapper, never()).insert(any(String.class), any(String.class), any(String.class), any(String.class), any(String.class),
            any(String.class), any(String.class), any(String.class), any(String.class), any(String.class), any(Double.class),
            any(Integer.class), any(java.time.LocalDateTime.class), any(Long.class), any(String.class));

        MemoryRecallRequest recall = new MemoryRecallRequest();
        recall.setScopeType("PROJECT"); recall.setScopeCode("other-project"); recall.setQuery("rollback");
        assertThrows(SecurityException.class, () -> service.recall(key, recall));
        verify(mapper).hasScopeAccess(41L, "octop-prod", "PROJECT", "other-project", "READ");
    }

    @Test
    void sharedForgetRequiresWriteGrant() {
        ApiKey key = key();
        Map<String, Object> existing = new LinkedHashMap<String, Object>();
        existing.put("scopeType", "PROJECT"); existing.put("scopeCode", "other-project"); existing.put("agentCode", "another-agent");
        when(mapper.get("octop-prod", "MEM_SHARED")).thenReturn(existing);
        assertThrows(SecurityException.class, () -> service.forget(key, "MEM_SHARED"));
        verify(mapper, never()).forget("octop-prod", "MEM_SHARED", "agent-ops");
    }

    @Test
    void profileMemoryCannotBeWrittenToProjectScope() {
        ApiKey key = key();
        MemoryCaptureRequest request = new MemoryCaptureRequest();
        request.setMemoryLevel("L3");
        request.setScopeType("PROJECT");
        request.setScopeCode("release-2026");
        request.setContent("private preference");

        assertThrows(IllegalArgumentException.class, () -> service.capture(key, request));
        verify(mapper, never()).insert(any(String.class), any(String.class), any(String.class), any(String.class), any(String.class),
            any(String.class), any(String.class), any(String.class), any(String.class), any(String.class), any(Double.class),
            any(Integer.class), any(java.time.LocalDateTime.class), any(Long.class), any(String.class));
    }

    @Test
    void broadKnowledgeWriteScopeDoesNotGrantMemoryWrite() {
        ApiKey key = new ApiKey();
        key.setScopes(ApiKey.SCOPE_WRITE);

        assertEquals(false, key.hasScope(ApiKey.SCOPE_MEMORY_WRITE));
        assertEquals(true, key.hasScope(ApiKey.SCOPE_READ));
        assertEquals(true, key.hasScope(ApiKey.SCOPE_INGEST));
    }

    @Test
    void unconfiguredMemoryNamespaceIsCredentialIsolated() {
        ApiKey key = key();
        key.setMemoryNamespace(null);

        assertEquals("key:41", service.namespace(key));
    }

    @Test
    void agentCannotForgetAnotherAgentsPrivateMemoryInSharedNamespace() {
        ApiKey key = key();
        Map<String, Object> existing = new LinkedHashMap<String, Object>();
        existing.put("scopeType", "AGENT");
        existing.put("agentCode", "agent-finance");
        when(mapper.get("octop-prod", "MEMORY_PRIVATE")).thenReturn(existing);

        assertThrows(SecurityException.class, () -> service.forget(key, "MEMORY_PRIVATE"));
        verify(mapper, never()).forget("octop-prod", "MEMORY_PRIVATE", "agent-ops");
    }

    private ApiKey key() {
        ApiKey key = new ApiKey();
        key.setId(41L);
        key.setKeyCode("AK_OCTOP_AGENT_OPS");
        key.setScopes("MEMORY_READ,MEMORY_WRITE");
        key.setMemoryNamespace("octop-prod");
        key.setMemoryAgentCode("agent-ops");
        return key;
    }
}
