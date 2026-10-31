package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void unsignedAgentCredentialCanListToolsButCannotInvokeDispatcherDirectly() {
        ApiKey key = new ApiKey();
        key.setIdentityMode("APPLICATION_WORKLOAD"); key.setScopes(ApiKey.SCOPE_LIST);
        McpServerService service = new McpServerService(null, null, new ObjectMapper());
        assertFalse(((List<?>) service.toolsList(key).get("tools")).isEmpty());
        assertThrows(SecurityException.class, () -> service.toolsCall(key, toolCall("list_knowledge_bases", Collections.emptyMap())));
    }

    @Test
    void identityToolReceivesVerifiedRequestIdentityAndRejectsArgumentSpoofing() throws Exception {
        ExternalAccessService access = mock(ExternalAccessService.class);
        ApiKey key = new ApiKey(); key.setScopes(ApiKey.SCOPE_LIST); key.setPrincipalId(123L); key.setWorkloadPolicyId(17L);
        key.setWorkloadIdentity(new com.jh.knowledgeos.domain.WorkloadIdentity(4L, "session", "invocation",
            AgentProofCrypto.V2, "kid", 31L, null, "opencode", "trust-uuid", "ACTIVE")
            .withAgentNames("registered-agent", "builder").inheritServiceGrants());
        McpServerService service = new McpServerService(access, null, new ObjectMapper());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList(key).get("tools");
        assertTrue(tools.stream().anyMatch(tool -> "get_agent_identity".equals(tool.get("name"))));
        Map<String, Object> params = new LinkedHashMap<String, Object>(); params.put("name", "get_agent_identity");
        params.put("arguments", Collections.emptyMap());
        Map<?, ?> identity = (Map<?, ?>) service.toolsCall(key, params).get("structuredContent");
        assertEquals("trust-uuid", identity.get("agentId"));
        assertEquals("registered-agent", identity.get("runtimeAgentKey"));
        assertEquals("builder", identity.get("terminalAgentName"));
        assertEquals(4L, identity.get("agentPrincipalId"));
        verify(access).assertScope(key, ApiKey.SCOPE_LIST);
        params.put("arguments", Collections.singletonMap("agentId", "someone-else"));
        assertThrows(IllegalArgumentException.class, () -> service.toolsCall(key, params));
        params.put("arguments", Collections.emptyMap()); key.setWorkloadIdentity(null);
        assertThrows(SecurityException.class, () -> service.toolsCall(key, params));
        assertFalse(((List<Map<String, Object>>) service.toolsList(key).get("tools")).stream()
            .anyMatch(tool -> "get_agent_identity".equals(tool.get("name"))));
    }

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
        List<Long> grantedIds = Collections.singletonList(23L);
        when(access.grantedKnowledgeBases(key)).thenReturn(grantedIds);
        when(facade.basesFor(key, grantedIds)).thenReturn(Arrays.asList(knowledgeBaseMetadata()));
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
    void protocolContractHandlesInitializeNotificationsAndErrors() {
        McpServerService service = new McpServerService(null, null, new ObjectMapper());
        Map<String, Object> initializeParams = new LinkedHashMap<String, Object>();
        initializeParams.put("protocolVersion", McpServerService.PROTOCOL_VERSION);
        initializeParams.put("capabilities", new LinkedHashMap<String, Object>());
        initializeParams.put("clientInfo", row("name", "contract-test", "version", "1"));
        Map<String, Object> initialized = service.handle(rpc("initialize", 7, initializeParams), null);
        assertEquals(7, initialized.get("id"));
        assertEquals(McpServerService.PROTOCOL_VERSION, ((Map<?, ?>) initialized.get("result")).get("protocolVersion"));

        assertEquals(null, service.handle(rpc("notifications/initialized", null, null), null));
        assertEquals(-32600, ((Map<?, ?>) service.handle(rpc("notifications/initialized", 8, null), null).get("error")).get("code"));
        assertEquals(-32600, ((Map<?, ?>) service.handle(row("jsonrpc", "2.0", "method", "ping"), null).get("error")).get("code"));
        assertEquals(-32601, ((Map<?, ?>) service.handle(rpc("unknown/method", 9, null), null).get("error")).get("code"));
    }

    private Map<String, Object> rpc(String method, Object id, Map<String, Object> params) {
        Map<String, Object> request = row("jsonrpc", "2.0", "method", method);
        if (id != null) { request.put("id", id); }
        if (params != null) { request.put("params", params); }
        return request;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) { result.put((String) values[i], values[i + 1]); }
        return result;
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

    /**
     * title 与 annotations 不是装饰：客户端拿 title 做展示名、拿 readOnlyHint 判断能否自动
     * 执行。缺了它们，有些平台会把工具排得很低，表现就是「发布成功却不命中」。
     */
    @Test
    void everyToolCarriesTitleAndAnnotations() {
        McpServerService service = new McpServerService(null, null, new ObjectMapper());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList().get("tools");

        assertFalse(tools.isEmpty());
        for (Map<String, Object> tool : tools) {
            String name = String.valueOf(tool.get("name"));
            assertNotNull(tool.get("title"), name + " 缺 title");
            Map<?, ?> annotations = (Map<?, ?>) tool.get("annotations");
            assertNotNull(annotations, name + " 缺 annotations");
            assertNotNull(annotations.get("readOnlyHint"), name + " 缺 readOnlyHint");
        }
    }

    /**
     * 卡片 / 断言 / 证据原本是六个工具，职责高度重叠（都是「按编码读对象」），模型在它们之间
     * 选错比选不中还常见，所以合成两个。旧名字从清单里撤下，但调用仍然接受（见下一个用例）。
     */
    @Test
    void fineGrainedObjectToolsAreMerged() {
        ApiKey key = mock(ApiKey.class);
        when(key.hasScope(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        McpServerService service = new McpServerService(null, null, new ObjectMapper());

        List<String> names = new java.util.ArrayList<String>();
        for (Map<String, Object> tool : (List<Map<String, Object>>) service.toolsList(key).get("tools")) {
            names.add(String.valueOf(tool.get("name")));
        }

        assertTrue(names.contains("list_knowledge_objects"));
        assertTrue(names.contains("get_knowledge_object"));
        for (String legacy : Arrays.asList("list_knowledge_cards", "get_knowledge_card", "list_claims", "get_claim", "get_evidence")) {
            assertFalse(names.contains(legacy), legacy + " 已合并，不该再出现在工具清单里");
        }
    }

    /** 合并前的旧工具名仍要能调：已经接好的客户端不该因为一次改名就打挂。 */
    @Test
    void legacyObjectToolNamesStillResolve() throws Exception {
        ExternalAccessService access = mock(ExternalAccessService.class);
        ExternalKnowledgeFacade facade = mock(ExternalKnowledgeFacade.class);
        ApiKey key = mock(ApiKey.class);
        List<String> tags = Collections.emptyList();
        when(access.resolveKnowledgeBase(key, 23L)).thenReturn(23L);
        when(facade.tagsFor(key, 23L)).thenReturn(tags);
        McpServerService service = new McpServerService(access, facade, new ObjectMapper());

        Map<String, Object> card = new LinkedHashMap<String, Object>();
        card.put("knowledgeBaseId", 23);
        card.put("code", "SOP-001");
        service.toolsCall(key, toolCall("get_knowledge_card", card));

        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("knowledgeBaseId", 23);
        evidence.put("evidenceCode", "EV-001");
        service.toolsCall(key, toolCall("get_evidence", evidence));

        verify(facade).objectDetail(eq(23L), eq("CARD"), eq("SOP-001"), eq(tags));
        verify(facade).objectDetail(eq(23L), eq("EVIDENCE"), eq("EV-001"), eq(tags));
    }

    /** 合并后的工具按 objectType 路由，大小写不敏感，并把旧字段 evidenceCode 当 code 收。 */
    @Test
    void mergedObjectToolRoutesByType() throws Exception {
        ExternalAccessService access = mock(ExternalAccessService.class);
        ExternalKnowledgeFacade facade = mock(ExternalKnowledgeFacade.class);
        ApiKey key = mock(ApiKey.class);
        List<String> tags = Collections.emptyList();
        when(access.resolveKnowledgeBase(key, 23L)).thenReturn(23L);
        when(facade.tagsFor(key, 23L)).thenReturn(tags);
        McpServerService service = new McpServerService(access, facade, new ObjectMapper());

        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("knowledgeBaseId", 23);
        args.put("objectType", "claim");
        args.put("code", "CL-001");
        service.toolsCall(key, toolCall("get_knowledge_object", args));

        verify(facade).objectDetail(eq(23L), eq("CLAIM"), eq("CL-001"), eq(tags));

        // 证据没有列表接口，列出来会误导模型以为有
        Map<String, Object> listing = new LinkedHashMap<String, Object>();
        listing.put("knowledgeBaseId", 23);
        listing.put("objectType", "EVIDENCE");
        assertThrows(IllegalArgumentException.class,
            () -> service.toolsCall(key, toolCall("list_knowledge_objects", listing)));
    }

    /** knowledgeBaseId 不再必填：模型通常不知道 ID，必填会让它干脆不调这个工具。 */
    @Test
    void knowledgeBaseIdIsNoLongerRequired() {
        McpServerService service = new McpServerService(null, null, new ObjectMapper());
        List<Map<String, Object>> tools = (List<Map<String, Object>>) service.toolsList().get("tools");

        assertFalse(tools.isEmpty());
        for (Map<String, Object> tool : tools) {
            List<?> required = (List<?>) ((Map<?, ?>) tool.get("inputSchema")).get("required");
            assertFalse(required.contains("knowledgeBaseId"),
                String.valueOf(tool.get("name")) + " 仍把 knowledgeBaseId 设为必填");
        }
    }

    /** instructions 是模型决定「用不用工具」的第一道输入，要写路由规则而不是只介绍能力。 */
    @Test
    void instructionsStateWhenToUseWhichTool() {
        McpServerService service = new McpServerService(null, null, new ObjectMapper());
        String instructions = String.valueOf(service.initialize().get("instructions"));

        assertTrue(instructions.contains("knowledge_query"));
        assertTrue(instructions.contains("knowledge_search"));
        assertTrue(instructions.contains("list_knowledge_bases"));
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
