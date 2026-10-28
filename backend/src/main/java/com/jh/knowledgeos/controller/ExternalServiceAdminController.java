package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.EmbedTokenRevokeRequest;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest;
import com.jh.knowledgeos.dto.ExternalPrincipalAssignmentRequest;
import com.jh.knowledgeos.dto.AgentMemoryScopeGrantRequest;
import com.jh.knowledgeos.dto.AgentMemoryGovernanceRequest;
import com.jh.knowledgeos.dto.AgentMemoryKeyPolicyRequest;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.ApiKeyService;
import com.jh.knowledgeos.service.AgentMemoryScopeAccessService;
import com.jh.knowledgeos.service.PrincipalMemoryAdminService;
import com.jh.knowledgeos.service.AgentMemoryGovernanceService;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.EmbedTokenLifecycleService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.McpServerService;
import com.jh.knowledgeos.service.DelegatedIdentityService;
import com.jh.knowledgeos.service.IdGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

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

    private final EmbedTokenLifecycleService tokenLifecycle;
    private final AgentMemoryScopeAccessService memoryScopeAccessService;
    private final AgentMemoryGovernanceService memoryGovernanceService;
    private final PrincipalMemoryAdminService principalMemoryService;

    private final DelegatedIdentityService delegatedIdentityService;

    private final IdGenerator ids;

    private final KnowledgeProperties.External properties;

    public ExternalServiceAdminController(ApiKeyService apiKeyService, ExternalAuditService auditService, McpServerService mcpServerService,
        EmbedTokenLifecycleService tokenLifecycle, AgentMemoryScopeAccessService memoryScopeAccessService,
        AgentMemoryGovernanceService memoryGovernanceService, KnowledgeProperties properties,
        DelegatedIdentityService delegatedIdentityService, IdGenerator ids,
        PrincipalMemoryAdminService principalMemoryService) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.mcpServerService = mcpServerService;
        this.tokenLifecycle = tokenLifecycle;
        this.memoryScopeAccessService = memoryScopeAccessService;
        this.memoryGovernanceService = memoryGovernanceService;
        this.delegatedIdentityService = delegatedIdentityService;
        this.ids = ids;
        this.principalMemoryService = principalMemoryService;
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
        rest.put("delegatedIdentityRequired", properties.getDelegatedIdentity().isEnabled());
        rest.put("agentMemoryEnabled", properties.isAgentMemoryEnabled());
        rest.put("endpoints", Arrays.asList(endpoint("GET", "/bases", "可见知识库列表"), endpoint("POST", "/query", "可追溯问答"), endpoint("POST", "/search",
            "检索（不生成答案）"), endpoint("POST", "/kb/retrievals", "第三方检索协议：chunks[] + 可选 answer"),
            endpoint("GET", "/cards", "知识卡片清单"),
            endpoint("GET", "/cards/{code}", "知识卡片详情"), endpoint("GET", "/claims", "断言清单"),
            endpoint("GET", "/claims/{code}", "断言详情"), endpoint("GET", "/evidence/{code}", "证据原文"), endpoint("GET", "/graph/{code}", "图谱邻域"),
            endpoint("GET", "/queries/{queryId}", "问答链路回放"), endpoint("GET", "/overview", "知识库概览"),
            endpoint("POST", "/documents/text", "文本接入（INGEST）"), endpoint("POST", "/memories/capture", "记忆写入（MEMORY_MANAGE）"),
            endpoint("POST", "/memories/recall", "记忆召回（MEMORY_MANAGE）"), endpoint("DELETE", "/memories/{memoryCode}", "记忆删除（MEMORY_MANAGE）")));
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
        sse.put("endpoints", Arrays.asList(endpoint("GET", "/api/v1/external/stream/query", "流式问答：stage / delta / citation / done"), endpoint("GET",
            "/api/v1/external/stream/events", "事件订阅：知识库 / 卡片 / 检索 / 权限等事件，支持 types 过滤与 Last-Event-ID 续传"), endpoint("GET",
            "/api/v1/external/stream/events?legacy=true", "旧版计数轮询订阅：ingestion / knowledge / heartbeat")));
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

    @GetMapping("/principals")
    @Operation(summary = "共享调用主体列表及其 API Key 数量、有效知识库授权数")
    public ApiResponse<Object> listPrincipals() {
        requireManager();
        return ApiResponse.ok(apiKeyService.listPrincipals());
    }

    @PostMapping("/principals")
    @Operation(summary = "由 AKB 管理员创建 AGENT/APPLICATION 服务主体")
    public ApiResponse<Object> createServicePrincipal(@RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        ExternalPrincipal principal = apiKeyService.createServicePrincipal(value(request, "principalCode"), value(request, "name"),
            value(request, "principalType"), value(request, "providerInstanceId"), value(request, "externalSubjectId"),
            value(request, "memoryNamespace"), value(request, "memoryAgentCode"), operator.getId());
        return ApiResponse.ok(principal);
    }

    @PutMapping("/principals/{id}/owner-user")
    @Operation(summary = "经管理员核验后将外部 USER 主体关联到 AKB 登录用户")
    public ApiResponse<Object> assignPrincipalOwner(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(apiKeyService.assignPrincipalToLocalUser(id, number(request.get("userId"))));
    }

    @PutMapping("/personal-access-tokens/{id}/scopes")
    @Operation(summary = "管理员审批并更新个人访问令牌 scope；记录变更原因")
    public ApiResponse<Object> updatePersonalAccessTokenScopes(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(apiKeyService.updatePersonalAccessTokenScopes(id, stringList(request.get("scopes")),
            operator.getId(), value(request, "reason")));
    }

    @PostMapping("/principals/{id}/keys")
    @Operation(summary = "为服务主体签发独立 Service Key，明文仅返回一次")
    public ApiResponse<Object> createPrincipalKey(@PathVariable Long id, @RequestBody(required = false) ExternalKeyRequest body) {
        User operator = requireManager();
        ExternalKeyRequest request = body == null ? new ExternalKeyRequest() : body;
        LocalDateTime expiresAt = parseExpiresAt(request.getExpiresAt(), properties.isServiceKeyExpiryRequired());
        validateServiceKeyExpiry(expiresAt);
        Integer limit = request.getRateLimitPerMinute() == null ? properties.getDefaultRateLimitPerMinute() : request.getRateLimitPerMinute();
        return ApiResponse.ok(apiKeyService.createServiceKey(id, request.getName(), request.getScopes(), limit, expiresAt,
            request.getAllowedOrigins(), operator.getId()));
    }

    @PostMapping("/keys/{id}/rotate")
    @Operation(summary = "轮换 Service Key：换发新明文并立即撤销旧 Key，主体、授权与记忆身份不变")
    public ApiResponse<Object> rotateKey(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        String expiry = request.get("expiresAt") == null ? null : String.valueOf(request.get("expiresAt"));
        LocalDateTime expiresAt = parseExpiresAt(expiry, properties.isServiceKeyExpiryRequired());
        validateServiceKeyExpiry(expiresAt);
        return ApiResponse.ok(apiKeyService.rotateServiceKey(id, expiresAt, operator.getId()));
    }

    @PutMapping("/principals/{id}")
    @Operation(summary = "更新主体：改名、换负责人、停用或启用；停用后其 Key 与 Memory 调用立即失败")
    public ApiResponse<Object> updatePrincipal(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(apiKeyService.updatePrincipal(id, value(request, "name"), value(request, "status"),
            number(request.get("ownerUserId"))));
    }

    @GetMapping("/principals/{id}/memory-profile")
    @Operation(summary = "查看主体的记忆身份（namespace 与 Agent identity）")
    public ApiResponse<Object> principalMemoryProfile(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(principalMemoryService.profile(id));
    }

    @PutMapping("/principals/{id}/memory-profile")
    @Operation(summary = "设置主体的记忆身份，并播种其对自身私有空间的读写授权")
    public ApiResponse<Object> savePrincipalMemoryProfile(@PathVariable Long id,
        @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(principalMemoryService.saveProfile(id, value(request, "namespace"),
            value(request, "agentCode"), operator.getId()));
    }

    @GetMapping("/principals/{id}/memory-grants")
    @Operation(summary = "查看主体可操作的记忆作用域授权")
    public ApiResponse<Object> principalMemoryGrants(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(principalMemoryService.grants(id));
    }

    @PutMapping("/principals/{id}/memory-grants")
    @Operation(summary = "原子替换主体的记忆作用域授权；空数组表示只保留自身私有空间")
    public ApiResponse<Object> replacePrincipalMemoryGrants(@PathVariable Long id,
        @RequestBody(required = false) List<AgentMemoryScopeGrantRequest> grants) {
        User operator = requireManager();
        return ApiResponse.ok(principalMemoryService.replaceGrants(id,
            grants == null ? Collections.<AgentMemoryScopeGrantRequest>emptyList() : grants, operator.getId()));
    }

    @GetMapping("/identity-bindings/octop")
    @Operation(summary = "查询 AKB 管理的 Octop 用户 ID 绑定")
    public ApiResponse<Object> listOctopIdentityBindings(@RequestParam(required = false) String providerInstanceId,
        @RequestParam(required = false) String externalUserId) {
        requireManager();
        return ApiResponse.ok(delegatedIdentityService.listOctopBindings(providerInstanceId, externalUserId));
    }

    @PostMapping("/identity-bindings/octop")
    @Operation(summary = "将 Octop 不可变用户 ID 绑定到 AKB USER 主体")
    public ApiResponse<Object> bindOctopIdentity(@RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(delegatedIdentityService.bindOctopUser(value(request, "providerInstanceId"), value(request, "externalUserId"),
            number(request.get("principalId")), operator.getId(), value(request, "reason")));
    }

    @GetMapping("/identity-bindings/octop/{id}")
    @Operation(summary = "查看单个 Octop 身份绑定的当前状态")
    public ApiResponse<Object> octopIdentityBinding(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(delegatedIdentityService.getOctopBinding(id));
    }

    @PostMapping("/identity-bindings/octop/{id}/approve")
    @Operation(summary = "审批待审绑定使其生效；已暂停的绑定也可通过它恢复")
    public ApiResponse<Object> approveOctopIdentity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(delegatedIdentityService.approveOctopBinding(id, operator.getId(), value(request, "reason")));
    }

    @PostMapping("/identity-bindings/octop/{id}/suspend")
    @Operation(summary = "暂停绑定：后续委托身份校验立即失败，已签发断言到期即失效")
    public ApiResponse<Object> suspendOctopIdentity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(delegatedIdentityService.suspendOctopBinding(id, operator.getId(), value(request, "reason")));
    }

    @PostMapping("/identity-bindings/octop/{id}/resume")
    @Operation(summary = "恢复已暂停的绑定")
    public ApiResponse<Object> resumeOctopIdentity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        return ApiResponse.ok(delegatedIdentityService.resumeOctopBinding(id, operator.getId(), value(request, "reason")));
    }

    @DeleteMapping("/identity-bindings/octop/{id}")
    @Operation(summary = "解绑：撤销 Octop 用户 ID 绑定，阻止新的委托身份校验")
    public ApiResponse<Object> deleteOctopIdentity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        delegatedIdentityService.revokeOctopUserBinding(id, operator.getId(), value(request, "reason"));
        return ApiResponse.ok(true);
    }

    @PostMapping("/identity-bindings/octop/{id}/revoke")
    @Operation(summary = "吊销 Octop 用户 ID 绑定，阻止新的委托身份校验")
    public ApiResponse<Object> revokeOctopIdentity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        delegatedIdentityService.revokeOctopUserBinding(id, operator.getId(), body == null ? null : value(body, "reason"));
        return ApiResponse.ok(Collections.singletonMap("revoked", Boolean.TRUE));
    }

    @PostMapping("/principals/users")
    @Operation(summary = "创建供 OIDC 委托身份绑定的 USER 主体")
    public ApiResponse<Object> createUserPrincipal(@RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        Object code = request.get("principalCode");
        Object name = request.get("name");
        return ApiResponse.ok(delegatedIdentityService.createUserPrincipal(code == null ? null : String.valueOf(code),
            name == null ? null : String.valueOf(name), number(request.get("ownerUserId")), operator.getId(), ids));
    }

    @GetMapping("/principals/{id}/identities")
    @Operation(summary = "查看 USER 主体的 OIDC 身份映射")
    public ApiResponse<Object> identityLinks(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(delegatedIdentityService.listIdentityLinks(id));
    }

    @PostMapping("/principals/{id}/identities")
    @Operation(summary = "绑定受信 OIDC issuer 的 subject 到 USER 主体")
    public ApiResponse<Object> linkIdentity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Map<String, Object> request = body == null ? Collections.<String, Object>emptyMap() : body;
        Object issuer = request.get("issuer");
        Object subject = request.get("subject");
        Object reason = request.get("reason");
        return ApiResponse.ok(delegatedIdentityService.linkIdentity(id, issuer == null ? null : String.valueOf(issuer),
            subject == null ? null : String.valueOf(subject), operator.getId(), reason == null ? null : String.valueOf(reason)));
    }

    @PostMapping("/principal-identities/{id}/revoke")
    @Operation(summary = "吊销 OIDC 身份映射，立即阻止委托调用")
    public ApiResponse<Object> revokeIdentity(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User operator = requireManager();
        Object reason = body == null ? null : body.get("reason");
        delegatedIdentityService.revokeIdentity(id, operator.getId(), reason == null ? null : String.valueOf(reason));
        return ApiResponse.ok(Collections.singletonMap("revoked", Boolean.TRUE));
    }

    @GetMapping("/principals/{id}/knowledge-base-grants/config")
    @Operation(summary = "查看任意 Principal 的知识库授权配置")
    public ApiResponse<Object> principalKnowledgeBaseGrantConfig(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(apiKeyService.knowledgeBaseGrantDetails(id));
    }

    @PutMapping("/principals/{id}/knowledge-base-grants/config")
    @Operation(summary = "配置员工 USER 主体的知识库授权、标签和期限")
    public ApiResponse<Object> replacePrincipalKnowledgeBaseGrantConfig(@PathVariable Long id,
        @RequestBody(required = false) List<ExternalKnowledgeBaseGrantRequest> grants) {
        User operator = requireManager();
        return ApiResponse.ok(apiKeyService.replacePrincipalKnowledgeBaseGrantDetails(id,
            grants == null ? Collections.<ExternalKnowledgeBaseGrantRequest>emptyList() : grants, operator.getId()));
    }

    @PostMapping("/keys/{id}/principal")
    @Operation(summary = "将 API Key 加入现有共享主体，或创建新主体并保留当前有效知识库授权")
    public ApiResponse<Object> assignPrincipal(@PathVariable Long id,
        @RequestBody(required = false) ExternalPrincipalAssignmentRequest body) {
        User operator = requireManager();
        ExternalPrincipalAssignmentRequest request = body == null ? new ExternalPrincipalAssignmentRequest() : body;
        return ApiResponse.ok(apiKeyService.assignPrincipal(id, request.getPrincipalId(), request.getName(), operator.getId(), request.getReason()));
    }

    @PostMapping("/keys")
    @Operation(summary = "签发 API Key，明文只在本次响应中出现一次")
    public ApiResponse<Object> createKey(@RequestBody(required = false) ExternalKeyRequest body) {
        User operator = requireManager();
        ExternalKeyRequest request = body == null ? new ExternalKeyRequest() : body;
        LocalDateTime expiresAt = parseExpiresAt(request.getExpiresAt(), properties.isServiceKeyExpiryRequired());
        validateServiceKeyExpiry(expiresAt);
        Integer limit = request.getRateLimitPerMinute() == null ? properties.getDefaultRateLimitPerMinute() : request.getRateLimitPerMinute();
        return ApiResponse.ok(apiKeyService.create(request.getName(), request.getKnowledgeBaseId(), request.getScopes(), limit, expiresAt,
            operator.getId(), request.getAllowedOrigins(), request.getMemoryNamespace(), request.getMemoryAgentCode()));
    }

    @PostMapping("/keys/{id}/revoke")
    @Operation(summary = "吊销 API Key，立即失效")
    public ApiResponse<Object> revokeKey(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(apiKeyService.revoke(id));
    }

    @GetMapping("/keys/{id}/knowledge-base-grants")
    @Operation(summary = "查看 API Key 所属主体的知识库授权")
    public ApiResponse<Object> knowledgeBaseGrants(@PathVariable Long id) {
        requireManager();
        com.jh.knowledgeos.domain.ApiKey key = apiKeyService.get(id);
        if (key == null) {
            throw new IllegalArgumentException("API Key 不存在");
        }
        List<Map<String, Object>> grants = new ArrayList<Map<String, Object>>();
        for (Long knowledgeBaseId : apiKeyService.grantedKnowledgeBases(key)) {
            Map<String, Object> grant = new LinkedHashMap<String, Object>();
            grant.put("knowledgeBaseId", knowledgeBaseId);
            grants.add(grant);
        }
        return ApiResponse.ok(grants);
    }

    @PutMapping("/keys/{id}/knowledge-base-grants")
    @Operation(summary = "原子替换 API Key 所属主体的知识库授权；空数组表示全部撤销")
    public ApiResponse<Object> replaceKnowledgeBaseGrants(@PathVariable Long id, @RequestBody(required = false) List<Long> knowledgeBaseIds) {
        User operator = requireManager();
        return ApiResponse.ok(apiKeyService.replaceKnowledgeBaseGrants(id,
            knowledgeBaseIds == null ? Collections.<Long>emptyList() : knowledgeBaseIds, operator.getId()));
    }

    @GetMapping("/keys/{id}/knowledge-base-grants/config")
    @Operation(summary = "查看知识库授权期限与授权原因")
    public ApiResponse<Object> knowledgeBaseGrantConfig(@PathVariable Long id) {
        requireManager();
        com.jh.knowledgeos.domain.ApiKey key = apiKeyService.get(id);
        if (key == null) {
            throw new IllegalArgumentException("API Key 不存在");
        }
        return ApiResponse.ok(apiKeyService.knowledgeBaseGrantDetails(key));
    }

    @PutMapping("/keys/{id}/knowledge-base-grants/config")
    @Operation(summary = "原子替换知识库授权并设置逐库有效期与授权原因")
    public ApiResponse<Object> replaceKnowledgeBaseGrantConfig(@PathVariable Long id,
        @RequestBody(required = false) List<ExternalKnowledgeBaseGrantRequest> grants) {
        User operator = requireManager();
        return ApiResponse.ok(apiKeyService.replaceKnowledgeBaseGrantDetails(id,
            grants == null ? Collections.<ExternalKnowledgeBaseGrantRequest>emptyList() : grants, operator.getId()));
    }

    @PutMapping("/keys/{id}/memory-policy")
    @Operation(summary = "开关 API Key 的记忆读取和写入能力；不能新增未签发的 scope")
    public ApiResponse<Object> memoryPolicy(@PathVariable Long id, @RequestBody @javax.validation.Valid AgentMemoryKeyPolicyRequest body) {
        requireManager();
        if (body == null || body.getReadEnabled() == null || body.getWriteEnabled() == null) {
            throw new IllegalArgumentException("readEnabled 和 writeEnabled 必须同时提供");
        }
        return ApiResponse.ok(apiKeyService.updateMemoryPolicy(id, body.getReadEnabled().booleanValue(), body.getWriteEnabled().booleanValue()));
    }

    @GetMapping("/keys/{id}/memory-access")
    @Operation(summary = "查看 API Key 的共享记忆作用域授权")
    public ApiResponse<Object> memoryScopeAccess(@PathVariable Long id) {
        requireManager();
        return ApiResponse.ok(memoryScopeAccessService.list(id));
    }

    @PutMapping("/keys/{id}/memory-access")
    @Operation(summary = "原子替换 API Key 的共享记忆作用域授权；空数组表示全部撤销")
    public ApiResponse<Object> replaceMemoryScopeAccess(@PathVariable Long id,
        @RequestBody(required = false) List<AgentMemoryScopeGrantRequest> grants) {
        User operator = requireManager();
        return ApiResponse.ok(memoryScopeAccessService.replace(id,
            grants == null ? Collections.<AgentMemoryScopeGrantRequest>emptyList() : grants, operator.getId()));
    }

    @GetMapping("/keys/{id}/memories")
    @Operation(summary = "查看 API Key 命名空间内记忆资产，支持状态和作用域筛选")
    public ApiResponse<Object> memories(@PathVariable Long id,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "scopeType", required = false) String scopeType,
        @RequestParam(value = "scopeCode", required = false) String scopeCode,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "offset", required = false) Integer offset) {
        requireManager();
        return ApiResponse.ok(memoryGovernanceService.list(id, status, scopeType, scopeCode, limit, offset));
    }

    @PutMapping("/keys/{id}/memories/{memoryCode}/status")
    @Operation(summary = "暂停或恢复记忆召回；操作需填写原因并留痕")
    public ApiResponse<Object> memoryStatus(@PathVariable Long id, @PathVariable String memoryCode,
        @RequestParam("status") String status, @RequestBody(required = false) AgentMemoryGovernanceRequest body) {
        User operator = requireManager();
        return ApiResponse.ok(memoryGovernanceService.setStatus(id, memoryCode, status,
            body == null ? null : body.getReason(), operator.getId()));
    }

    @PostMapping("/keys/{id}/memories/{memoryCode}/forget")
    @Operation(summary = "管理员物理删除记忆；保留不含记忆正文的审计记录")
    public ApiResponse<Object> forgetMemory(@PathVariable Long id, @PathVariable String memoryCode,
        @RequestBody(required = false) AgentMemoryGovernanceRequest body) {
        User operator = requireManager();
        return ApiResponse.ok(memoryGovernanceService.forget(id, memoryCode,
            body == null ? null : body.getReason(), operator.getId()));
    }

    @GetMapping("/keys/{id}/memory-events")
    @Operation(summary = "查看该 API Key 命名空间的记忆治理审计，不包含记忆正文")
    public ApiResponse<Object> memoryEvents(@PathVariable Long id,
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "offset", required = false) Integer offset) {
        requireManager();
        return ApiResponse.ok(memoryGovernanceService.events(id, limit, offset));
    }

    @GetMapping("/audits")
    @Operation(summary = "对外调用审计：RESTful / MCP / SSE 合并展示")
    public ApiResponse<Object> audits(@RequestParam(value = "keyId", required = false) Long keyId, @RequestParam(value = "limit", required = false)
        Integer limit) {
        requireManager();
        return ApiResponse.ok(auditService.recent(keyId, limit == null ? 50 : limit));
    }

    /* ---- 嵌入令牌生命周期 ---- */

    /**
     * Tokens seen in the audit window with their traffic: the list an operator revokes from.
     */
    @GetMapping("/embed/tokens")
    @Operation(summary = "嵌入令牌：窗口内出现过的令牌、来源站点、调用量与是否已吊销")
    public ApiResponse<Object> embedTokens(@RequestParam(value = "hours", required = false) Integer hours,
        @RequestParam(value = "limit", required = false) Integer limit) {
        requireManager();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("windowHours", hours == null ? 168 : hours);
        payload.put("tokens", tokenLifecycle.recentTokens(hours == null ? 168 : hours, limit == null ? 50 : limit));
        payload.put("revocations", tokenLifecycle.revocations(50));
        return ApiResponse.ok(payload);
    }

    @GetMapping("/embed/tokens/calls")
    @Operation(summary = "嵌入令牌调用日志：可按令牌或 API Key 过滤")
    public ApiResponse<Object> embedTokenCalls(@RequestParam(value = "jti", required = false) String jti,
        @RequestParam(value = "keyId", required = false) Long keyId, @RequestParam(value = "limit", required = false) Integer limit) {
        requireManager();
        return ApiResponse.ok(tokenCalls(tokenLifecycle.calls(keyId, jti, limit == null ? 50 : limit)));
    }

    @PostMapping("/embed/tokens/revoke")
    @Operation(summary = "吊销单个嵌入令牌，立即生效（不影响同一 API Key 签发的其他令牌）")
    public ApiResponse<Object> revokeEmbedToken(@RequestBody(required = false) EmbedTokenRevokeRequest body) {
        User operator = requireManager();
        EmbedTokenRevokeRequest request = body == null ? new EmbedTokenRevokeRequest() : body;
        return ApiResponse.ok(tokenLifecycle.revoke(request.getJti(), request.getKeyId(), request.getKeyCode(), request.getKnowledgeBaseId(),
            request.getOrigin(), request.getReason(), operator.getId()));
    }

    @PostMapping("/embed/tokens/{jti}/restore")
    @Operation(summary = "撤销吊销：令牌在到期前重新可用")
    public ApiResponse<Object> restoreEmbedToken(@PathVariable String jti) {
        requireManager();
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("jti", jti);
        data.put("restored", tokenLifecycle.restore(jti));
        return ApiResponse.ok(data);
    }

    @GetMapping("/embed/tokens/revocations")
    @Operation(summary = "已吊销的嵌入令牌名单")
    public ApiResponse<Object> embedTokenRevocations(@RequestParam(value = "limit", required = false) Integer limit) {
        requireManager();
        return ApiResponse.ok(tokenLifecycle.revocations(limit == null ? 50 : limit));
    }

    /**
     * Flattens audit rows for the console: the token id is shown shortened, because the point of
     * the screen is recognising a token, not copying one.
     */
    private List<Map<String, Object>> tokenCalls(List<ApiCallAudit> rows) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (ApiCallAudit row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.getId());
            item.put("jti", row.getTokenJti());
            item.put("displayId", displayId(row.getTokenJti()));
            item.put("keyId", row.getKeyId());
            item.put("keyCode", row.getKeyCode());
            item.put("callerName", row.getCallerName());
            item.put("origin", row.getOrigin());
            item.put("channel", row.getChannel());
            item.put("method", row.getMethod());
            item.put("knowledgeBaseId", row.getKnowledgeBaseId());
            item.put("statusCode", row.getStatusCode());
            item.put("durationMs", row.getDurationMs());
            item.put("message", row.getMessage());
            item.put("requestParams", row.getRequestParams());
            item.put("traceId", row.getTraceId());
            item.put("createdTime", row.getCreatedTime() == null ? null : row.getCreatedTime().toString());
            out.add(item);
        }
        return out;
    }

    private static String displayId(String jti) {
        if (jti == null || jti.isEmpty()) {
            return "—";
        }
        return jti.length() <= 8 ? jti : jti.substring(0, 4) + "…" + jti.substring(jti.length() - 4);
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
        if (properties.isAgentMemoryEnabled()) {
            names.add("memory_capture");
            names.add("memory_recall");
            names.add("memory_forget");
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

    private static String value(Map<String, Object> body, String key) {
        Object raw = body == null ? null : body.get(key);
        return raw == null ? null : String.valueOf(raw);
    }

    private static Long number(Object raw) {
        if (raw == null) return null;
        try { return Long.valueOf(String.valueOf(raw)); } catch (NumberFormatException ex) { throw new IllegalArgumentException("principalId 必须是数字"); }
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) return Collections.<String>emptyList();
        if (!(raw instanceof List<?>)) throw new IllegalArgumentException("scopes 必须是字符串数组");
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof String)) throw new IllegalArgumentException("scopes 必须是字符串数组");
            result.add((String) item);
        }
        return result;
    }

    private static LocalDateTime parseExpiresAt(String raw, boolean optional) {
        if (raw == null || raw.trim().isEmpty()) {
            if (optional) return null;
            throw new IllegalArgumentException("expiresAt 必填");
        }
        try { return LocalDateTime.parse(raw.trim()); }
        catch (DateTimeParseException ex) { throw new IllegalArgumentException("expiresAt 需为 ISO 时间，如 2027-01-31T23:59:59"); }
    }

    private void validateServiceKeyExpiry(LocalDateTime expiresAt) {
        if (expiresAt == null) return;
        if (!expiresAt.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("Service Key 到期时间必须晚于当前时间");
        int maxDays = properties.getServiceKeyMaxLifetimeDays();
        if (maxDays > 0 && expiresAt.isAfter(LocalDateTime.now().plusDays(maxDays))) {
            throw new IllegalArgumentException("Service Key 最长有效期为 " + maxDays + " 天");
        }
    }
}
