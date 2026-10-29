package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkloadIdentityAdminService {
    private final WorkloadIdentityRepository repository;
    private final ExternalApiMapper principals;
    private final ApiKeyService keys;
    private final ObjectMapper json;
    private final boolean allowLoopback;
    public WorkloadIdentityAdminService(WorkloadIdentityRepository repository, ExternalApiMapper principals,
        ApiKeyService keys, ObjectMapper json, @Value("${WORKLOAD_ALLOW_LOOPBACK_HTTP:false}") boolean allowLoopback) {
        this.repository = repository; this.principals = principals; this.keys = keys; this.json = json; this.allowLoopback = allowLoopback;
    }
    public List<Map<String, Object>> policies() { return repository.policies(); }
    public List<Map<String, Object>> bindings(long policy) { requirePolicy(policy); return repository.bindings(policy); }

    @Transactional
    public Map<String, Object> createPolicy(Map<String, Object> body, long operator) {
        long application = number(body, "applicationPrincipalId");
        requirePrincipal(application, "APPLICATION");
        String profile = body.containsKey("proofProfile") ? text(body, "proofProfile", 32) : AgentProofCrypto.V1;
        if (!Arrays.asList(AgentProofCrypto.V1, AgentProofCrypto.V2).contains(profile)) throw new IllegalArgumentException("不支持的身份证明模式");
        String issuer = text(body, "issuer", 255), jwks = AgentProofCrypto.V1.equals(profile) ? text(body, "jwksUri", 1000) : null;
        validateUrl(issuer);
        if (jwks != null) validateUrl(jwks);
        if (AgentProofCrypto.V2.equals(profile) && body.get("jwksUri") != null) throw new IllegalArgumentException("V2 不使用远程 JWKS");
        long id = repository.createPolicy(application, issuer, jwks, text(body, "audience", 255),
            text(body, "installationId", 128), text(body, "workspaceId", 128), operator);
        if (AgentProofCrypto.V2.equals(profile)) {
            String platform = text(body, "platformId", 128), resource = text(body, "resourceOrigin", 255);
            if (!platform.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("无效的平台标识");
            validateUrl(resource);
            URI origin = URI.create(resource);
            String canonical;
            try { canonical = new URI(origin.getScheme(), null, origin.getHost().toLowerCase(java.util.Locale.ROOT),
                ("https".equals(origin.getScheme()) && origin.getPort() == 443) ? -1 : origin.getPort(), null, null, null).toASCIIString(); }
            catch (java.net.URISyntaxException ex) { throw new IllegalArgumentException("无效的资源 origin"); }
            if (!resource.equals(canonical)) throw new IllegalArgumentException("resourceOrigin 必须为规范 origin，无路径及默认端口");
            repository.configureV2(id, platform, resource);
        }
        audit(id, "CREATE_POLICY", operator, body);
        return repository.policy(id);
    }
    @Transactional
    public List<Map<String, Object>> bind(long policy, Map<String, Object> body, long operator) {
        Map<String, Object> configured = requirePolicy(policy);
        String external = text(body, "externalAgentId", 128), runtime = text(body, "runtimeAgentKey", 128);
        if (AgentProofCrypto.V2.equals(configured.get("proof_profile"))) {
            AgentProofCrypto.uuid(external);
            if (!runtime.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("无效的 Agent 执行标识");
        }
        ExternalPrincipal agent = requirePrincipal(number(body, "agentPrincipalId"), "AGENT");
        if (!external.equals(agent.getExternalSubjectId()) || !configured.get("installation_id").equals(agent.getExternalInstanceId())) {
            throw new IllegalArgumentException("Agent 的稳定外部 ID 和安装 ID 必须与登记一致");
        }
        repository.createBinding(policy, external, runtime, agent.getId());
        audit(policy, "CREATE_BINDING", operator, body); return repository.bindings(policy);
    }
    @Transactional
    public Map<String, Object> createKey(long policy, ExternalKeyRequest request, long operator) {
        Map<String, Object> configured = requirePolicy(policy);
        long application = ((Number) configured.get("application_principal_id")).longValue();
        requirePrincipal(application, "APPLICATION");
        List<String> scopes = request.getScopes() == null ? Arrays.asList("SEARCH", "QUERY", "LIST", "READ_OBJECT") : request.getScopes();
        if (scopes.isEmpty() || !Arrays.asList("SEARCH", "QUERY", "LIST", "READ_OBJECT").containsAll(scopes)) {
            throw new IllegalArgumentException("工作负载凭据首版仅支持只读知识库 scope");
        }
        LocalDateTime expires = request.getExpiresAt() == null ? null : LocalDateTime.parse(request.getExpiresAt());
        if (expires == null || !expires.isAfter(LocalDateTime.now()) || expires.isAfter(LocalDateTime.now().plusDays(365))) {
            throw new IllegalArgumentException("凭据必须设置未来且不超过 365 天的到期时间");
        }
        Map<String, Object> result = keys.createServiceKey(application, request.getName(), scopes,
            request.getRateLimitPerMinute(), expires, request.getAllowedOrigins(), operator);
        long keyId = ((Number) result.get("id")).longValue(); repository.configureKey(keyId, policy);
        result.put("identityMode", "APPLICATION_WORKLOAD"); result.put("workloadPolicyId", policy);
        audit(policy, "CREATE_KEY", operator, java.util.Collections.singletonMap("keyId", keyId)); return result;
    }
    @Transactional
    public void status(long policy, Long binding, Map<String, Object> body, long operator) {
        requirePolicyExists(policy);
        String status = text(body, "status", 16);
        if (!Arrays.asList("ACTIVE", "SUSPENDED", "REVOKED").contains(status)) throw new IllegalArgumentException("无效状态");
        int changed = binding == null ? repository.policyStatus(policy, status) : repository.bindingStatus(policy, binding, status);
        if (changed != 1) throw new IllegalArgumentException("登记不存在或已永久撤销");
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<String, Object>(body);
        detail.put("bindingId", binding); audit(policy, "CHANGE_STATUS", operator, detail);
    }
    private Map<String, Object> requirePolicyExists(long id) {
        Map<String, Object> policy = repository.policy(id);
        if (policy == null) throw new IllegalArgumentException("工作负载策略不存在"); return policy;
    }
    private Map<String, Object> requirePolicy(long id) {
        Map<String, Object> policy = requirePolicyExists(id);
        if (!"ACTIVE".equals(policy.get("status"))) throw new IllegalArgumentException("策略未启用"); return policy;
    }
    private ExternalPrincipal requirePrincipal(long id, String type) {
        ExternalPrincipal principal = principals.getPrincipal(id);
        if (principal == null || !type.equals(principal.getPrincipalType()) || !"ACTIVE".equals(principal.getStatus())) {
            throw new IllegalArgumentException("主体类型或状态无效");
        }
        return principal;
    }
    private void validateUrl(String text) {
        URI uri = URI.create(text);
        boolean loopback = allowLoopback && "http".equals(uri.getScheme())
            && Arrays.asList("127.0.0.1", "localhost", "::1", "[::1]").contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !loopback) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException("身份提供者必须使用无凭据、查询和片段的 HTTPS 地址");
        }
    }
    private void audit(long policy, String action, long operator, Object detail) {
        try { repository.audit(policy, action, operator, json.writeValueAsString(detail)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private static long number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number) || ((Number) value).longValue() < 1
            || ((Number) value).doubleValue() != ((Number) value).longValue()) throw new IllegalArgumentException(key + " 必须是正整数");
        return ((Number) value).longValue();
    }
    private static String text(Map<String, Object> map, String key, int limit) {
        Object raw = map.get(key);
        if (!(raw instanceof String)) throw new IllegalArgumentException(key + " 必须是字符串");
        String value = ((String) raw).trim();
        if (value.isEmpty() || value.length() > limit) throw new IllegalArgumentException(key + " 长度无效");
        return value;
    }
}
