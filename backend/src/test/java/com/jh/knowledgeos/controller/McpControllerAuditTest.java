package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.McpServerService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.mockito.ArgumentCaptor;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计口径用例：JSON-RPC 的成败要能在审计行里分辨出来，而不是一律记成 HTTP 200。
 *
 * <p>下面两个 verify 都不断言第 4 个参数（knowledgeBaseId）——本用例关心的是 RPC 成败
 * 有没有被标出来（rpcSuccess / rpcErrorCode / response），kb 的解析由专门的用例覆盖，
 * 混在这里只会让“改了 kb 解析”误伤“审计口径”的回归信号。</p>
 */
class McpControllerAuditTest {
    @Test
    void jsonRpcFailureRemainsHttp200AndIsAuditedAsRpcFailure() {
        ExternalAccessService access = mock(ExternalAccessService.class);
        McpServerService server = mock(McpServerService.class);
        ExternalAuditService audit = mock(ExternalAuditService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.external().setEnabled(true);
        properties.external().setMcpEnabled(true);
        ApiKey key = new ApiKey();
        when(access.authenticate(any())).thenReturn(key);
        Map<String, Object> error = row("code", -32601, "message", "unknown method");
        when(server.handle(any(), eq(key))).thenReturn(row("jsonrpc", "2.0", "id", 1, "error", error));
        McpController controller = new McpController(access, server, audit, properties);

        ResponseEntity<?> response = controller.handle(new MockHttpServletRequest(), row("jsonrpc", "2.0", "id", 1, "method", "unknown"));
        assertEquals(200, response.getStatusCodeValue());
        ArgumentCaptor<Object> auditedResponse = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(key), eq(ApiCallAudit.CHANNEL_MCP), eq("unknown"), any(), anyInt(), anyLong(), eq("unknown method"),
            any(), auditedResponse.capture());
        Map<?, ?> details = (Map<?, ?>) auditedResponse.getValue();
        assertEquals(Boolean.FALSE, details.get("rpcSuccess"));
        assertEquals(-32601, details.get("rpcErrorCode"));
        assertTrue(details.containsKey("response"));
    }

    @Test
    void successfulJsonRpcAuditExplicitlyRecordsSuccess() {
        ExternalAccessService access = mock(ExternalAccessService.class);
        McpServerService server = mock(McpServerService.class);
        ExternalAuditService audit = mock(ExternalAuditService.class);
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.external().setEnabled(true);
        properties.external().setMcpEnabled(true);
        ApiKey key = new ApiKey();
        when(access.authenticate(any())).thenReturn(key);
        when(server.handle(any(), eq(key))).thenReturn(row("jsonrpc", "2.0", "id", 1, "result", row("pong", true)));
        McpController controller = new McpController(access, server, audit, properties);

        controller.handle(new MockHttpServletRequest(), row("jsonrpc", "2.0", "id", 1, "method", "ping"));
        ArgumentCaptor<Object> auditedResponse = ArgumentCaptor.forClass(Object.class);
        verify(audit).record(eq(key), eq(ApiCallAudit.CHANNEL_MCP), eq("ping"), any(), anyInt(), anyLong(), isNull(), any(),
            auditedResponse.capture());
        assertTrue((Boolean) ((Map<?, ?>) auditedResponse.getValue()).get("rpcSuccess"));
        assertFalse(((Map<?, ?>) auditedResponse.getValue()).containsKey("rpcErrorMessage"));
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) { result.put((String) values[i], values[i + 1]); }
        return result;
    }
}
