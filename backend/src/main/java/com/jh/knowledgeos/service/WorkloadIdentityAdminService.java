package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.IntranetAddressRules;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkloadIdentityAdminService {
    private static final Logger LOG = LoggerFactory.getLogger(WorkloadIdentityAdminService.class);
    private final WorkloadIdentityRepository repository;
    private final ExternalApiMapper principals;
    private final ApiKeyService keys;
    private final ObjectMapper json;
    private final boolean allowLoopback;
    private final boolean allowIntranetHttp;
    public WorkloadIdentityAdminService(WorkloadIdentityRepository repository, ExternalApiMapper principals,
        ApiKeyService keys, ObjectMapper json, @Value("${WORKLOAD_ALLOW_LOOPBACK_HTTP:false}") boolean allowLoopback,
        @Value("${WORKLOAD_ALLOW_INTRANET_HTTP:true}") boolean allowIntranetHttp) {
        this.repository = repository; this.principals = principals; this.keys = keys; this.json = json;
        this.allowLoopback = allowLoopback; this.allowIntranetHttp = allowIntranetHttp;
    }

    @Transactional
    public Map<String, Object> createPolicy(Map<String, Object> body, long operator) {
        long application = number(body, "applicationPrincipalId");
        requirePrincipal(application, "APPLICATION");
        // 只认 V2：V1（远程 JWKS + 安装级证明）已下线。以前不传 proofProfile 时默认建的是 V1，
        // 那意味着「不指定」会得到一个根本认证不了的策略——现在不指定就是 V2。
        String profile = body.containsKey("proofProfile") ? text(body, "proofProfile", 32) : AgentProofCrypto.V2;
        if (!AgentProofCrypto.V2.equals(profile)) throw new IllegalArgumentException("只支持 " + AgentProofCrypto.V2 + " 身份证明模式");
        if (body.get("jwksUri") != null) throw new IllegalArgumentException("V2 不使用远程 JWKS");
        String issuer = text(body, "issuer", 255);
        validateUrl("身份签发者地址（issuer）", issuer);
        long id = repository.createPolicy(application, issuer, null, text(body, "audience", 255),
            text(body, "installationId", 128), text(body, "workspaceId", 128), operator);
        String platform = text(body, "platformId", 128);
        if (!platform.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("无效的平台标识");
        repository.configureV2(id, platform, canonicalResourceOrigin(text(body, "resourceOrigin", 255)));
        audit(id, "CREATE_POLICY", operator, body);
        return repository.policy(id);
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
        if ("ACTIVE".equals(status) && "REVIEW_REQUIRED".equals(requirePolicyExists(policy).get("authorization_mode")))
            throw new IllegalArgumentException("请先确认历史接入的授权方式");
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
    /**
     * 地址校验。这三个地址都是管理员在控制台里手填的，报错必须说清是哪个字段、哪个地址、
     * 为什么被拒——一句「地址必须使用 HTTPS」定位不了，只能靠猜。
     */
    private void validateUrl(String field, String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(field + " 不是一个合法地址：" + value);
        }
        if (uri.getHost() == null) {
            // 下划线等 URI 不接受的主机会让 getHost() 返回 null：这不是「带了凭据」，得说清楚
            throw new IllegalArgumentException(field + " 的主机名无法解析：" + value
                + "（URI 不接受下划线等字符，请检查拼写）");
        }
        if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
            throw new IllegalArgumentException(field + " 必须是不含凭据、查询和片段的地址，当前：" + value);
        }
        if ("https".equals(uri.getScheme())) {
            return;
        }
        if (!"http".equals(uri.getScheme())) {
            throw new IllegalArgumentException(field + " 必须使用 HTTPS 地址，当前：" + value);
        }
        // 明文 HTTP 只放开两类场景：本机调试与内网部署。公网一律要求 HTTPS——工作负载身份
        // 证明在明文链路上可被中间人直接替换，这不是「多一层加密」的偏好问题。
        // 判定规则不在这里：见 contracts/intranet-http.json，前端读同一份
        if (allowLoopback && IntranetAddressRules.isLoopback(uri.getHost())) return;
        if (allowIntranetHttp && IntranetAddressRules.isIntranetHost(uri.getHost())) return;
        LOG.warn("拒绝明文 HTTP：{}={}，主机 {} 既不是本机也不是内网（loopback={}, intranet={}）",
            field, value, uri.getHost(), allowLoopback, allowIntranetHttp);
        throw new IllegalArgumentException(field + " 必须使用 HTTPS：明文 HTTP 只允许本机调试"
            + "（WORKLOAD_ALLOW_LOOPBACK_HTTP=" + allowLoopback + "）与内网地址"
            + "（WORKLOAD_ALLOW_INTRANET_HTTP=" + allowIntranetHttp + "）。"
            + "当前 " + value + " 的主机 " + uri.getHost() + " 被判定为公网。");
    }
    /**
     * AKB 对外地址的校验与规范化，新建和修改共用。抽出来是因为改地址时必须走同一套判定——
     * 复制一份就会出现「新建拒绝、修改放行」的口子。
     */
    public String canonicalResourceOrigin(String value) {
        validateUrl("AKB 对外访问地址（resourceOrigin）", value);
        URI origin = URI.create(value);
        try {
            String canonical = new URI(origin.getScheme(), null, origin.getHost().toLowerCase(java.util.Locale.ROOT),
                ("https".equals(origin.getScheme()) && origin.getPort() == 443) ? -1 : origin.getPort(), null, null, null).toASCIIString();
            if (!value.equals(canonical)) throw new IllegalArgumentException("resourceOrigin 必须为规范 origin，无路径及默认端口");
            return canonical;
        } catch (java.net.URISyntaxException ex) { throw new IllegalArgumentException("无效的资源 origin"); }
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
