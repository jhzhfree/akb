package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.McpServerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Server endpoint: knowledge capabilities as tools and resources for external agents.
 *
 * <p>Speaks JSON-RPC 2.0 over a single POST endpoint, so an MCP client only needs the URL and
 * an API key header. Business failures travel as JSON-RPC errors, never as HTML error pages.</p>
 */
@RestController
@RequestMapping("/api/mcp")
@CrossOrigin
@Tag(name = "External MCP Server", description = "MCP 协议服务端：Tools / Resources（API Key 鉴权）")
public class McpController {

    private final ExternalAccessService accessService;

    private final McpServerService mcpServerService;

    private final ExternalAuditService auditService;

    private final KnowledgeProperties.External properties;

    public McpController(ExternalAccessService accessService, McpServerService mcpServerService, ExternalAuditService auditService,
        KnowledgeProperties properties) {
        this.accessService = accessService;
        this.mcpServerService = mcpServerService;
        this.auditService = auditService;
        this.properties = properties.external();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "JSON-RPC 2.0 入口：initialize / tools/list / tools/call / resources/*")
    public Map<String, Object> handle(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        if (!properties.isEnabled() || !properties.isMcpEnabled()) {
            throw new SecurityException("MCP 服务已关闭");
        }
        long start = System.currentTimeMillis();
        ApiKey key = null;
        // A tool call is audited as the tool that ran, with its arguments and its business result —
        // not as an opaque JSON-RPC envelope. "tools/call" repeated twenty times down a log tells an
        // operator nothing; "tools/call:knowledge_query" with the question and the answer does.
        String method = body == null ? "" : String.valueOf(body.get("method"));
        String auditedMethod = "tools/call".equals(method) ? "tools/call:" + toolName(body) : method;
        Object params = auditService.mcpRequestParams(body);
        Long knowledgeBaseId = auditService.mcpKnowledgeBaseId(body);
        try {
            key = accessService.authenticate(request);
            Map<String, Object> response = mcpServerService.handle(body == null ? new java.util.LinkedHashMap<String, Object>() : body, key);
            // 协议层错误（鉴权、限流）由全局异常处理，这里只审计进入协议之后的结果。
            auditService.record(key, ApiCallAudit.CHANNEL_MCP, auditedMethod, knowledgeBaseId, 200, System.currentTimeMillis() - start, null, params,
                auditService.mcpResponseData(response));
            // 通知类消息（无 id）协议上不返回结果，这里回空对象保持 HTTP 200。
            return response == null ? new LinkedHashMap<String, Object>() : response;
        } catch (UnauthorizedException e) {
            // 凭证无效时调用者也应当出现在日志里：否则"谁在拿错 key 打我们的 MCP"根本无从查起。
            auditService.record(key, ApiCallAudit.CHANNEL_MCP, auditedMethod, knowledgeBaseId, 401, System.currentTimeMillis() - start, e.getMessage(),
                params, errorBody(401, e));
            throw e;
        } catch (SecurityException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_MCP, auditedMethod, knowledgeBaseId, 403, System.currentTimeMillis() - start, e.getMessage(),
                params, errorBody(403, e));
            throw e;
        } catch (RuntimeException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_MCP, auditedMethod, knowledgeBaseId, 500, System.currentTimeMillis() - start, e.getMessage(),
                params, errorBody(500, e));
            throw e;
        }
    }

    /**
     * Which tool the call named, so the row can be told apart from the others.
     */
    private String toolName(Map<String, Object> body) {
        Object params = body == null ? null : body.get("params");
        if (params instanceof Map) {
            Object name = ((Map<?, ?>) params).get("name");
            if (name != null) {
                String text = String.valueOf(name).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "unknown";
    }

    /**
     * The error object the agent received, so the row answers "what did we send back".
     */
    private Map<String, Object> errorBody(int status, Exception e) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", status);
        payload.put("message", e.getMessage());
        return payload;
    }
    /**
     * Console-facing views of this server live in ExternalServiceAdminController: this path is
     * deliberately exempt from console-session auth because MCP clients authenticate with an API key.
     */
}
