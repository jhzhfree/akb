package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
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
        ApiKey key = accessService.authenticate(request);
        String method = body == null ? "" : String.valueOf(body.get("method"));
        Map<String, Object> response;
        try {
            response = mcpServerService.handle(body == null ? new java.util.LinkedHashMap<String, Object>() : body, key);
        } catch (RuntimeException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_MCP, method, null, 500, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
        // 协议层错误（鉴权、限流）由全局异常处理，这里只审计进入协议之后的结果。
        auditService.record(key, ApiCallAudit.CHANNEL_MCP, method, null, 200, System.currentTimeMillis() - start, null);
        // 通知类消息（无 id）协议上不返回结果，这里回空对象保持 HTTP 200。
        return response == null ? new LinkedHashMap<String, Object>() : response;
    }
    /**
     * Console-facing views of this server live in ExternalServiceAdminController: this path is
     * deliberately exempt from console-session auth because MCP clients authenticate with an API key.
     */
}
