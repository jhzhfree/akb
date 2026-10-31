package com.jh.knowledgeos.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.config.WorkloadRequestFilter;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import com.jh.knowledgeos.service.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real controller, access policy and MCP dispatcher together. */
class McpCredentialAuthenticationTest {
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final WorkloadIdentityRepository repository = mock(WorkloadIdentityRepository.class);
    private McpController controller;
    private ApiKey agentKey;

    @BeforeEach void setup() {
        agentKey = new ApiKey();
        agentKey.setIdentityMode("APPLICATION_WORKLOAD");
        agentKey.setPrincipalType("APPLICATION");
        agentKey.setPrincipalId(1L);
        agentKey.setWorkloadPolicyId(7L);
        agentKey.setScopes(ApiKey.SCOPE_LIST);
        when(keys.authenticate("agent-key")).thenReturn(agentKey);
        ApiKey serviceKey = new ApiKey();
        serviceKey.setPrincipalId(2L);
        serviceKey.setScopes(ApiKey.SCOPE_LIST);
        when(keys.authenticate("service-key")).thenReturn(serviceKey);
        Map<String, Object> policy = new HashMap<String, Object>();
        policy.put("status", "ACTIVE"); policy.put("application_principal_id", 1L);
        policy.put("proof_profile", AgentProofCrypto.V2); policy.put("authorization_mode", "SERVICE_SCOPE");
        when(repository.policy(7L)).thenReturn(policy);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.external().setEnabled(true); properties.external().setMcpEnabled(true);
        ExternalAccessService access = new ExternalAccessService(keys, mock(ExternalRateLimiter.class),
            mock(KnowledgeBaseAccessService.class), mock(EmbedTokenService.class), mock(EmbedTokenLifecycleService.class), properties);
        access.setWorkloadIdentityService(new WorkloadIdentityService(repository));
        McpServerService server = new McpServerService(access, mock(ExternalKnowledgeFacade.class), new ObjectMapper());
        controller = new McpController(access, server, mock(ExternalAuditService.class), properties);
    }

    @Test void agentKeyCanDiscoverButCannotExecuteOrReadResourcesWithoutProof() {
        for (String method : Arrays.asList("initialize", "ping", "tools/list")) {
            Map<?, ?> response = (Map<?, ?>) controller.handle(request("agent-key"), rpc(method)).getBody();
            assertTrue(response.containsKey("result"), method);
        }
        assertNull(agentKey.getWorkloadIdentity());
        assertThrows(UnauthorizedException.class, () -> controller.handle(request("agent-key"), rpc("tools/call")));
        assertThrows(UnauthorizedException.class, () -> controller.handle(request("agent-key"), rpc("resources/list")));
        assertThrows(UnauthorizedException.class, () -> controller.handle(request("agent-key"), rpc("resources/read")));
    }

    @Test void serviceKeyExecutesBusinessToolWithoutAgentProof() {
        Map<?, ?> response = (Map<?, ?>) controller.handle(request("service-key"), rpc("tools/call")).getBody();
        assertTrue(response.containsKey("result"));
        assertFalse(response.containsKey("error"));
    }

    @Test void revokedKeysSuspendedPoliciesAndInvalidProofCannotDiscover() {
        assertThrows(UnauthorizedException.class, () -> controller.handle(request("revoked"), rpc("initialize")));
        MockHttpServletRequest invalid = request("agent-key");
        invalid.addHeader(WorkloadRequestFilter.HEADER, "invalid-proof");
        assertThrows(UnauthorizedException.class, () -> controller.handle(invalid, rpc("tools/list")));
        repository.policy(7L).put("status", "SUSPENDED");
        assertThrows(UnauthorizedException.class, () -> controller.handle(request("agent-key"), rpc("tools/list")));
    }

    private MockHttpServletRequest request(String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader(ExternalAccessService.HEADER, secret);
        return request;
    }

    private Map<String, Object> rpc(String method) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("jsonrpc", "2.0"); body.put("id", 1); body.put("method", method);
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", "list_knowledge_bases"); params.put("arguments", Collections.emptyMap());
        if ("initialize".equals(method)) {
            params.clear(); params.put("protocolVersion", "2025-06-18");
            params.put("capabilities", Collections.emptyMap());
            Map<String, Object> client = new HashMap<String, Object>();
            client.put("name", "credential-test"); client.put("version", "1");
            params.put("clientInfo", client);
        }
        body.put("params", params);
        return body;
    }
}
