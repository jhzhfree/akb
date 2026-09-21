package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.ApiKeyService;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.McpServerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console-side governance for the external interface-service layer.
 *
 * <p>Credentials are signed here, and the same screen reports which channels are live and what
 * external callers have actually been doing, so "opening an interface" stays an auditable act.</p>
 */
@RestController
@RequestMapping("/api/external/admin")
@Tag(name = "External Service Admin", description = "对外服务治理：API Key、通道开关与调用审计")
public class ExternalServiceAdminController {

    private final ApiKeyService apiKeyService;

    private final ExternalAuditService auditService;

    private final McpServerService mcpServerService;

    private final KnowledgeProperties.External properties;

    public ExternalServiceAdminController(ApiKeyService apiKeyService, ExternalAuditService auditService, McpServerService mcpServerService, KnowledgeProperties properties) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.mcpServerService = mcpServerService;
        this.properties = properties.external();
    }

    /**
     * Live contract of the three channels, used by the console to render examples.
     */
    @GetMapping("/services")
    @Operation(summary = "对外服务总览：RESTful / MCP / SSE 端点与开关状态")
    public ApiResponse<Object> services() {
        requireManager();
        Map<String, Object> rest = new LinkedHashMap<String, Object>();
        rest.put("enabled", properties.isEnabled());
        rest.put("basePath", "/api/v1/external");
        rest.put("auth", "X-API-Key 或 Authorization: Bearer");
        rest.put("endpoints", Arrays.asList(endpoint("GET", "/bases", "可见知识库列表"), endpoint("POST", "/query", "可追溯问答"), endpoint("POST", "/search", "检索（不生成答案）"), endpoint("GET", "/cards", "知识卡片清单"), endpoint("GET", "/cards/{code}", "知识卡片详情"), endpoint("GET", "/claims", "断言清单"), endpoint("GET", "/claims/{code}", "断言详情"), endpoint("GET", "/evidence/{code}", "证据原文"), endpoint("GET", "/graph/{code}", "图谱邻域"), endpoint("GET", "/queries/{queryId}", "问答链路回放"), endpoint("GET", "/overview", "知识库概览"), endpoint("POST", "/documents/text", "文本接入（INGEST）")));
        Map<String, Object> mcp = new LinkedHashMap<String, Object>();
        mcp.put("enabled", properties.isEnabled() && properties.isMcpEnabled());
        mcp.put("endpoint", "/api/mcp");
        mcp.put("protocolVersion", McpServerService.PROTOCOL_VERSION);
        mcp.put("methods", Arrays.asList("initialize", "tools/list", "tools/call", "resources/list", "resources/read", "ping"));
        mcp.put("tools", toolNames());
        Map<String, Object> sse = new LinkedHashMap<String, Object>();
        sse.put("enabled", properties.isEnabled() && properties.isSseEnabled());
        sse.put("heartbeatSeconds", properties.getSseHeartbeatSeconds());
        sse.put("maxMinutes", properties.getSseMaxMinutes());
        sse.put("endpoints", Arrays.asList(endpoint("GET", "/api/v1/external/stream/query", "流式问答：stage / delta / citation / done"), endpoint("GET", "/api/v1/external/stream/events", "事件订阅：知识库 / 卡片 / 检索 / 权限等事件，支持 types 过滤与 Last-Event-ID 续传"), endpoint("GET", "/api/v1/external/stream/events?legacy=true", "旧版计数轮询订阅：ingestion / knowledge / heartbeat")));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("rest", rest);
        payload.put("mcp", mcp);
        payload.put("sse", sse);
        payload.put("activeKeys", apiKeyService.countActive());
        Map<String, Object> docs = new LinkedHashMap<String, Object>();
        docs.put("openapi", "/v3/api-docs");
        docs.put("swaggerUi", "/swagger-ui.html");
        payload.put("docs", docs);
        return ApiResponse.ok(payload);
    }

    @GetMapping("/keys")
    @Operation(summary = "API Key 列表（不含明文）")
    public ApiResponse<Object> listKeys() {
        requireManager();
        return ApiResponse.ok(apiKeyService.list());
    }

    @PostMapping("/keys")
    @Operation(summary = "签发 API Key，明文只在本次响应中出现一次")
    public ApiResponse<Object> createKey(@RequestBody(required = false) ExternalKeyRequest body) {
        User operator = requireManager();
        ExternalKeyRequest request = body == null ? new ExternalKeyRequest() : body;
        LocalDateTime expiresAt = null;
        if (request.getExpiresAt() != null && !request.getExpiresAt().trim().isEmpty()) {
            try {
                expiresAt = LocalDateTime.parse(request.getExpiresAt().trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("expiresAt 需为 ISO 时间，如 2026-12-31T23:59:59");
            }
        }
        Integer limit = request.getRateLimitPerMinute() == null ? properties.getDefaultRateLimitPerMinute() : request.getRateLimitPerMinute();
        return ApiResponse.ok(apiKeyService.create(request.getName(), request.getKnowledgeBaseId(), request.getScopes(), limit, expiresAt, operator.getId()));
    }

    @PostMapping("/keys/{id}/revoke")
    @Operation(summary = "吊销 API Key，立即失效")
    public ApiResponse<Object> revokeKey(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(apiKeyService.revoke(id));
    }

    @GetMapping("/audits")
    @Operation(summary = "对外调用审计：RESTful / MCP / SSE 合并展示")
    public ApiResponse<Object> audits(@RequestParam(value = "keyId", required = false) Long keyId, @RequestParam(value = "limit", required = false) Integer limit) {
        requireManager();
        return ApiResponse.ok(auditService.recent(keyId, limit == null ? 50 : limit));
    }

    /**
     * Full MCP tool definitions, read from the same registry the protocol serves.
     * A hand-maintained copy on this side would drift, and the first person to notice would be
     * the agent calling a tool that no longer accepts those arguments.
     */
    @GetMapping("/mcp/manifest")
    @Operation(summary = "MCP 服务清单：协议版本、开关状态与工具定义（含入参 schema）")
    public ApiResponse<Object> mcpManifest() {
        requireManager();
        Map<String, Object> manifest = new LinkedHashMap<String, Object>();
        manifest.put("protocolVersion", McpServerService.PROTOCOL_VERSION);
        manifest.put("serverName", McpServerService.SERVER_NAME);
        manifest.put("serverVersion", McpServerService.SERVER_VERSION);
        manifest.put("endpoint", "/api/mcp");
        manifest.put("enabled", properties.isEnabled() && properties.isMcpEnabled());
        manifest.put("auth", "API Key（X-API-Key 或 Authorization: Bearer）");
        manifest.put("tools", mcpServerService.toolsList().get("tools"));
        manifest.put("initialize", mcpServerService.initialize());
        return ApiResponse.ok(manifest);
    }

    /**
     * Recent MCP-channel calls: the only way to tell "nobody uses it" from "it is broken".
     */
    @GetMapping("/mcp/calls")
    @Operation(summary = "MCP 通道最近调用")
    public ApiResponse<Object> mcpCalls(@RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        requireManager();
        return ApiResponse.ok(auditService.recentByChannel(ApiCallAudit.CHANNEL_MCP, limit));
    }

    private List<String> toolNames() {
        List<String> names = new ArrayList<String>();
        Object tools = mcpServerService.toolsList().get("tools");
        if (tools instanceof List) {
            for (Object tool : (List<?>) tools) {
                if (tool instanceof Map) {
                    names.add(String.valueOf(((Map<?, ?>) tool).get("name")));
                }
            }
        }
        return names;
    }

    private Map<String, Object> endpoint(String method, String path, String summary) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("method", method);
        row.put("path", path);
        row.put("summary", summary);
        return row;
    }

    /**
     * External services are signed by administrators or knowledge-base administrators.
     */
    private User requireManager() {
        User user = AuthContext.get();
        if (user == null) {
            throw new UnauthorizedException("请先登录控制台");
        }
        UserRole role = UserRole.of(user.getRole());
        if (role != UserRole.ADMIN && role != UserRole.KNOWLEDGE_ADMIN) {
            throw new SecurityException("需要系统管理员或知识库管理员角色");
        }
        return user;
    }
}
