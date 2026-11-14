package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real authorization/facade code; repository and AI transport are offline test doubles. */
class DelegatedRetrievalBoundaryTest {
    private ApiKey key(Long user) {
        ApiKey key = new ApiKey();
        key.setKeyCode("TEST"); key.setPrincipalId(10L); key.setPrincipalType("AGENT");
        key.setPermissionTags("[\"a\",\"b\"]"); key.setDelegatedPrincipalId(user);
        return key;
    }
    private ExternalApiMapper grants() {
        ExternalApiMapper mapper = mock(ExternalApiMapper.class);
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(10L, 1L)).thenReturn("[\"a\",\"b\"]");
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(20L, 1L)).thenReturn("[\"a\"]");
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(30L, 1L)).thenReturn("[\"b\"]");
        return mapper;
    }
    @Test void delegatedUsersRemainSeparateAndRevocationIsReadAgainInEveryMode() {
        for (String mode : Arrays.asList("CLIENT", "SHADOW", "ENFORCE")) {
            ExternalApiMapper mapper = grants();
            RetrievalAuthorizationService service = new RetrievalAuthorizationService(new ObjectMapper(), mode, mapper);
            assertEquals(Arrays.asList("a"), service.effectiveTags(key(20L), 1L, Arrays.asList("a", "b"), "REST"));
            assertEquals(Arrays.asList("b"), service.effectiveTags(key(30L), 1L, null, "MCP"));
            when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(20L, 1L)).thenReturn("[]");
            assertEquals(Collections.emptyList(), service.effectiveTags(key(20L), 1L, Arrays.asList("a", "b"), "SSE"));
            assertEquals(Arrays.asList("b"), service.effectiveTags(key(30L), 1L, null, "REST"));
            when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(20L, 1L)).thenReturn(null);
            assertEquals(Collections.emptyList(), service.effectiveTags(key(20L), 1L, null, "REST"));
        }
    }
    @Test void missingServerContextCannotInheritAnAgentCeiling() {
        assertThrows(SecurityException.class, () -> new RetrievalAuthorizationService(new ObjectMapper(), "CLIENT")
            .effectiveTags(key(20L), 1L, null, "REST"));
        ExternalApiMapper mapper = grants();
        RetrievalAuthorizationService service = new RetrievalAuthorizationService(new ObjectMapper(), "CLIENT", mapper);
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(10L, 1L)).thenReturn(null);
        assertEquals(Collections.emptyList(), service.effectiveTags(key(20L), 1L, null, "REST"));
    }
    @Test void ordinaryClientAndShadowStayCompatible() {
        for (String mode : Arrays.asList("CLIENT", "SHADOW")) {
            RetrievalAuthorizationService service = new RetrievalAuthorizationService(new ObjectMapper(), mode, grants());
            assertEquals(Arrays.asList("outside"), service.effectiveTags(key(null), 1L, Arrays.asList("outside"), "REST"));
        }
    }
    @Test void everyExternalPayloadEntryPreservesPublicOnlyAcrossJson() throws Exception {
        ExternalApiMapper mapper = grants();
        when(mapper.getPrincipalKnowledgeBaseGrantPermissionTags(20L, 1L)).thenReturn("[]");
        KnowledgeService knowledge = mock(KnowledgeService.class);
        List<Map<String,Object>> sent = new ArrayList<>();
        when(knowledge.query(anyMap())).thenAnswer(call -> {
            sent.add(new LinkedHashMap<>(call.getArgument(0)));
            return new LinkedHashMap<String,Object>();
        });
        doAnswer(call -> { sent.add(new LinkedHashMap<>(call.getArgument(0))); return null; })
            .when(knowledge).streamQuery(anyMap(), any());
        ObjectMapper json = new ObjectMapper();
        ExternalKnowledgeFacade facade = new ExternalKnowledgeFacade(knowledge, mock(KnowledgeBaseService.class), json,
            new RetrievalAuthorizationService(json, "SHADOW", mapper), mock(AgentMemoryService.class));
        ApiKey user = key(20L);
        facade.query(user, 1L, "q", 2, null, "REST");
        facade.search(user, 1L, "q", 2, null, "MCP");
        facade.retrieval(user, 1L, "q", 2, null, "REST", null, false, false);
        facade.streamQuery(user, 1L, "q", 2, null, "SSE", null, null, null,
            mock(KnowledgeQueryService.QueryStreamListener.class));
        facade.queryWithContext(user, 1L, "q", 2, null, "REST", null, null, "");
        assertEquals(5, sent.size());
        for (Map<String,Object> payload : sent) {
            Map<?,?> wire = json.readValue(json.writeValueAsBytes(payload), Map.class);
            assertTrue(wire.containsKey("permissionTags"));
            assertEquals(Collections.emptyList(), wire.get("permissionTags"));
        }
        facade.query(null, 1L, "q", 2, null, "INTERNAL");
        assertFalse(sent.get(5).containsKey("permissionTags"), "null keeps internal unconstrained semantics");
    }
    @Test void delegatedMemoryFailsBeforeReadingSharedMemoryOrCallingAi() throws Exception {
        KnowledgeService knowledge = mock(KnowledgeService.class);
        AgentMemoryService memory = mock(AgentMemoryService.class);
        ExternalKnowledgeFacade facade = new ExternalKnowledgeFacade(knowledge, mock(KnowledgeBaseService.class), new ObjectMapper(),
            new RetrievalAuthorizationService(new ObjectMapper(), "CLIENT", grants()), memory);
        ApiKey user = key(20L);
        assertThrows(SecurityException.class, () -> facade.memoryContext(user, "q"));
        assertThrows(SecurityException.class, () -> facade.retrieval(user, 1L, "q", 2, null, "REST", null, false, true));
        assertThrows(SecurityException.class, () -> facade.queryWithMemory(user, 1L, "q", 2, null, null, null, "full"));
        assertThrows(SecurityException.class, () -> facade.queryWithContext(user, 1L, "q", 2, null, "REST", null, null, "shared"));
        assertThrows(SecurityException.class, () -> facade.streamQuery(user, 1L, "q", 2, null, "SSE", null, null, "shared", null));
        verifyNoInteractions(memory, knowledge);
    }

}
