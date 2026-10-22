package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.dto.MemoryRecallRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServerServiceTest {

    @Test
    void toolsExposePermissionTagFiltersForSearchAndQuery() {
        McpServerService service = new McpServerService(null, null, new ObjectMapper());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList().get("tools");

        Map<String, Object> search = tools.stream()
            .filter(tool -> "knowledge_search".equals(tool.get("name")))
            .findFirst().orElseThrow(AssertionError::new);
        Map<String, Object> query = tools.stream()
            .filter(tool -> "knowledge_query".equals(tool.get("name")))
            .findFirst().orElseThrow(AssertionError::new);

        assertNotNull(((Map<?, ?>) ((Map<?, ?>) search.get("inputSchema")).get("properties")).get("permissionTags"));
        assertNotNull(((Map<?, ?>) ((Map<?, ?>) query.get("inputSchema")).get("properties")).get("permissionTags"));
        assertFalse(((List<?>) ((Map<?, ?>) search.get("inputSchema")).get("required")).contains("knowledgeBaseId"));
        assertNotNull(((Map<?, ?>) ((Map<?, ?>) search.get("inputSchema")).get("properties")).get("knowledgeBaseCode"));
    }

    @Test
    void toolListContainsOnlyTheGrantedKnowledgeAction() {
        ApiKey key = new ApiKey();
        key.setScopes(ApiKey.SCOPE_QUERY);
        McpServerService service = new McpServerService(null, null, new ObjectMapper());

        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList(key).get("tools");

        assertEquals(1, tools.size());
        assertEquals("knowledge_query", tools.get(0).get("name"));
    }

    @Test
    void readOnlyKeyDoesNotAdvertiseIngestionTool() {
        ApiKey key = mock(ApiKey.class);
        when(key.hasScope(ApiKey.SCOPE_INGEST)).thenReturn(false);
        McpServerService service = new McpServerService(null, null, new ObjectMapper());

        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList(key).get("tools");

        assertFalse(tools.stream().anyMatch(tool -> "ingest_text".equals(tool.get("name"))));
    }

    @Test
    void memoryToolsAreOnlyAdvertisedForExplicitMemoryScopes() {
        ApiKey key = mock(ApiKey.class);
        when(key.hasScope(ApiKey.SCOPE_MEMORY_READ)).thenReturn(true);
        when(key.hasScope(ApiKey.SCOPE_MEMORY_WRITE)).thenReturn(false);
        McpServerService service = new McpServerService(null, null, new ObjectMapper());

        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList(key).get("tools");

        assertEquals(true, tools.stream().anyMatch(tool -> "memory_recall".equals(tool.get("name"))));
        assertFalse(tools.stream().anyMatch(tool -> "memory_capture".equals(tool.get("name"))));
        assertFalse(tools.stream().anyMatch(tool -> "memory_forget".equals(tool.get("name"))));
    }

    @Test
    void platformMemorySwitchHidesMemoryToolsEvenWhenKeyHasScopes() {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.external().setAgentMemoryEnabled(false);
        AgentMemoryService memory = new AgentMemoryService(mock(AgentMemoryMapper.class), mock(ExternalAccessService.class), new ObjectMapper(), properties);
        ApiKey key = mock(ApiKey.class);
        when(key.hasScope(ApiKey.SCOPE_MEMORY_READ)).thenReturn(true);
        when(key.hasScope(ApiKey.SCOPE_MEMORY_WRITE)).thenReturn(true);
        McpServerService service = new McpServerService(null, null, new ObjectMapper(), memory);

        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList(key).get("tools");

        assertFalse(tools.stream().anyMatch(tool -> String.valueOf(tool.get("name")).startsWith("memory_")));
    }

    @Test
    void mcpMemoryRecallDelegatesToSharedMemoryEngine() throws Exception {
        ExternalAccessService access = mock(ExternalAccessService.class);
        AgentMemoryService memory = mock(AgentMemoryService.class);
        ApiKey key = mock(ApiKey.class);
        Map<String, Object> recalled = new LinkedHashMap<String, Object>();
        recalled.put("count", 1);
        when(memory.recall(eq(key), org.mockito.ArgumentMatchers.any(MemoryRecallRequest.class))).thenReturn(recalled);
        McpServerService service = new McpServerService(access, null, new ObjectMapper(), memory);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("query", "release rollback");
        args.put("scopeType", "PROJECT");
        args.put("scopeCode", "release-2026");

        Map<String, Object> result = service.toolsCall(key, toolCall("memory_recall", args));

        assertEquals(recalled, result.get("structuredContent"));
    }

    @Test
    void searchAcceptsStableKnowledgeBaseCodeAndStillChecksApiKeyScope() throws Exception {
        ExternalAccessService access = mock(ExternalAccessService.class);
        ExternalKnowledgeFacade facade = mock(ExternalKnowledgeFacade.class);
        ApiKey key = mock(ApiKey.class);
        when(key.getKnowledgeBaseId()).thenReturn(23L);
        when(facade.bases(23L)).thenReturn(Arrays.asList(knowledgeBaseMetadata()));
        when(access.resolveKnowledgeBase(key, 23L)).thenReturn(23L);
        when(facade.search(eq(key), eq(23L), eq("leave policy"), eq((Integer) null), eq(Collections.emptyList()), eq(PlatformEventPublisher.SOURCE_MCP),
            eq((Double) null))).thenReturn(Collections.<String, Object>singletonMap("queryId", "q-code"));

        McpServerService service = new McpServerService(access, facade, new ObjectMapper());
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("knowledgeBaseCode", "ops-runbook");
        args.put("query", "leave policy");

        Map<String, Object> result = service.toolsCall(key, toolCall("knowledge_search", args));

        verify(access).resolveKnowledgeBase(key, 23L);
        verify(access).assertScope(key, ApiKey.SCOPE_SEARCH);
        assertEquals("q-code", ((Map<?, ?>) result.get("structuredContent")).get("queryId"));
    }

    @Test
    void rejectsMismatchedNumericIdAndStableCode() {
        ExternalAccessService access = mock(ExternalAccessService.class);
        ExternalKnowledgeFacade facade = mock(ExternalKnowledgeFacade.class);
        ApiKey key = mock(ApiKey.class);
        when(key.getKnowledgeBaseId()).thenReturn(23L);
        when(facade.bases(23L)).thenReturn(Arrays.asList(knowledgeBaseMetadata()));
        McpServerService service = new McpServerService(access, facade, new ObjectMapper());
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("knowledgeBaseId", 24L);
        args.put("knowledgeBaseCode", "ops-runbook");
        args.put("query", "leave policy");

        assertThrows(
            IllegalArgumentException.class,
            () -> service.toolsCall(key, toolCall("knowledge_search", args))
        );
    }

    @Test
    void searchPassesTagsAndMcpAttributionThroughTheGovernedFacade() throws Exception {
        ExternalAccessService access = mock(ExternalAccessService.class);
        ExternalKnowledgeFacade facade = mock(ExternalKnowledgeFacade.class);
        ApiKey key = mock(ApiKey.class);
        when(access.resolveKnowledgeBase(key, 23L)).thenReturn(23L);
        Map<String, Object> expected = new LinkedHashMap<String, Object>();
        expected.put("queryId", "q-1");
        when(facade.search(eq(key), eq(23L), eq("leave policy"), eq(5), eq(Arrays.asList("hr")), eq(PlatformEventPublisher.SOURCE_MCP),
            eq(0.65d))).thenReturn(expected);

        McpServerService service = new McpServerService(access, facade, new ObjectMapper());
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("knowledgeBaseId", 23);
        args.put("query", "leave policy");
        args.put("topK", 5);
        args.put("permissionTags", Arrays.asList(" hr ", " "));
        args.put("minScore", 0.65d);

        Map<String, Object> result = service.toolsCall(key, toolCall("knowledge_search", args));

        verify(access).assertScope(key, ApiKey.SCOPE_SEARCH);
        verify(facade).search(eq(key), eq(23L), eq("leave policy"), eq(5), eq(Arrays.asList("hr")), eq(PlatformEventPublisher.SOURCE_MCP),
            eq(0.65d));
        assertEquals(Boolean.FALSE, result.get("isError"));
        assertEquals(expected, result.get("structuredContent"));
    }

    private Map<String, Object> toolCall(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", name);
        params.put("arguments", arguments);
        return params;
    }

    private Map<String, Object> knowledgeBaseMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("id", 23L);
        metadata.put("baseCode", "ops-runbook");
        return metadata;
    }
}
