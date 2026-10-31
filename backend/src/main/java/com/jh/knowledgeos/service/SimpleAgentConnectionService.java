package com.jh.knowledgeos.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Simple UI over the existing strict V2 request proof. No private material is accepted. */
@Service
public class SimpleAgentConnectionService {

    /** 可选注入：可读范围收知识库编码，靠它把编码换成主键。 */
    private KnowledgeBaseService knowledgeBases;

    @Autowired(required = false)
    public void setKnowledgeBases(KnowledgeBaseService service) { this.knowledgeBases = service; }
    private final WorkloadIdentityRepository repository;
    private final WorkloadIdentityAdminService workload;
    private final ApiKeyService keys;
    private final ExternalApiMapper principals;
    private final ExternalRateLimiter limiter;
    public SimpleAgentConnectionService(WorkloadIdentityRepository repository, WorkloadIdentityAdminService workload,
        ApiKeyService keys, ExternalApiMapper principals, ExternalRateLimiter limiter) {
        this.repository = repository; this.workload = workload; this.keys = keys; this.principals = principals; this.limiter = limiter;
    }
    public Map<String, Object> require(long id) {
        Map<String, Object> p = repository.policy(id);
        if (p == null || !AgentProofCrypto.V2.equals(p.get("proof_profile"))) throw new IllegalArgumentException("简化服务接入不存在");
        return p;
    }
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> p : repository.policies()) if (AgentProofCrypto.V2.equals(p.get("proof_profile"))) {
            result.add(present(p));
        }
        return result;
    }
    public Map<String, Object> detail(long id) { return present(require(id)); }
    /** 列表行与详情共用：名字在主体上，可读写范围与共享 Key 由主体推出来。 */
    private Map<String, Object> present(Map<String, Object> p) {
        Map<String, Object> row = new LinkedHashMap<>(p);
        long principal = ((Number) p.get("application_principal_id")).longValue();
        ExternalPrincipal app = principals.getPrincipal(principal);
        row.put("name", app == null ? "已停用接入" : app.getName());
        row.put("knowledgeBaseIds", keys.grantedKnowledgeBases(principal));
        row.put("keys", keys.listPrincipalKeys(principal));
        return row;
    }
    @Transactional
    public Map<String, Object> create(Map<String, Object> body, long operator) {
        String name = AgentProofCrypto.text(body, "name", 128);
        String origin = AgentProofCrypto.text(body, "resourceOrigin", 255);
        ExternalPrincipal app = keys.createServicePrincipal(null, name, "APPLICATION", null, null, null, null, operator);
        String id = UUID.randomUUID().toString();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("applicationPrincipalId", app.getId()); fields.put("proofProfile", AgentProofCrypto.V2);
        fields.put("issuer", "https://akb.local/agent-identity"); fields.put("audience", "akb-external-api");
        fields.put("installationId", id); fields.put("workspaceId", id); fields.put("platformId", "akb-service"); fields.put("resourceOrigin", origin);
        Map<String, Object> policy = workload.createPolicy(fields, operator);
        long policyId = ((Number) policy.get("id")).longValue();
        repository.enableSimple(policyId);
        repository.platformType(policyId, platform(body));
        scope(policyId, body, operator);
        ExternalKeyRequest request = new ExternalKeyRequest(); request.setName(name);
        request.setExpiresAt(LocalDateTime.now().plusDays(365).minusMinutes(1).toString());
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", policyId);
        result.put("key", workload.createKey(policyId, request, operator));
        return result;
    }
    @Transactional
    public void scope(long id, Map<String, Object> body, long operator) {
        Map<String, Object> policy = require(id);
        Object raw = body.get("knowledgeBaseIds");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) throw new IllegalArgumentException("请至少选择一个可读知识库");
        List<ExternalKnowledgeBaseGrantRequest> grants = new ArrayList<>();
        for (Object value : (List<?>) raw) {
            String code = value == null ? "" : String.valueOf(value).trim();
            if (code.isEmpty()) throw new IllegalArgumentException("无效的知识库：不能为空");
            // 认编码；认不出来就抛错，绝不静默跳过——跳过等于库没授权但页面显示已选。
            if (knowledgeBases != null) { knowledgeBases.resolveId(code); }
            else if (!code.matches("\\d+")) throw new IllegalArgumentException("未知的知识库编码：" + code);
            ExternalKnowledgeBaseGrantRequest g = new ExternalKnowledgeBaseGrantRequest();
            g.setKnowledgeBaseId(code);
            // Only untagged resources by default; never silently grant classified data.
            g.setPermissionTags(Collections.emptyList()); g.setReason("Agent 服务接入可读范围"); grants.add(g);
        }
        keys.replacePrincipalKnowledgeBaseGrantDetails(((Number)policy.get("application_principal_id")).longValue(), grants, operator);
    }
    public List<Map<String, Object>> agents(long id) {
        require(id); List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> b : repository.bindings(id)) {
            Map<String, Object> row = new LinkedHashMap<>(b);
            ExternalPrincipal p = principals.getPrincipal(((Number)b.get("agent_principal_id")).longValue());
            row.put("name", p == null ? "已停用 Agent" : p.getName());
            row.put("keys", repository.agentKeys(id, ((Number)b.get("id")).longValue())); result.add(row);
        }
        return result;
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> register(long id, Map<String, Object> body, long operator) {
        Map<String, Object> policy = require(id);
        if (!"ACTIVE".equals(policy.get("status"))) throw new IllegalArgumentException("服务接入已停用");
        String name = AgentProofCrypto.text(body, "name", 128);
        String agentId = AgentProofCrypto.uuid(AgentProofCrypto.text(body, "agentId", 36));
        if (!(body.get("publicJwk") instanceof Map)) throw new IllegalArgumentException("请导入 public.jwk；不要上传私钥");
        RSAKey key = AgentProofCrypto.publicKey((Map<String, Object>)body.get("publicJwk"));
        Map<String, Object> binding = null;
        for (Map<String, Object> b : repository.bindings(id)) if (agentId.equals(b.get("external_agent_id"))) binding = b;
        if (binding == null) {
            ExternalPrincipal p = keys.createServicePrincipal(null, name, "AGENT", String.valueOf(policy.get("installation_id")), agentId, null, null, operator);
            repository.createBinding(id, agentId, agentId, p.getId());
            binding = repository.binding(id, agentId, agentId);
        }
        long bindingId = ((Number)binding.get("id")).longValue();
        binding = repository.bindingById(id, bindingId, true);
        if (!"ACTIVE".equals(binding.get("status"))) throw new IllegalArgumentException("Agent 已停用或撤销");
        if (repository.agentKey(id, bindingId, key.getKeyID()) != null) throw new IllegalArgumentException("该公钥已经登记，已撤销的公钥不可重新启用");
        if (repository.activeKeyCount(id, bindingId) >= 2) throw new IllegalArgumentException("最多保留两把活动公钥，请先撤销旧公钥");
        Map<String, Object> result = repository.registerKey(id, bindingId, key.getKeyID(), key.toJSONString(), operator);
        repository.audit(id, "ADMIN_APPROVE_AGENT_KEY", operator, "{\"kid\":\"" + key.getKeyID() + "\"}");
        return result;
    }
    /**
     * 签发/补发共享服务 Key。原来界面走的是复杂版 /workload/policies/{id}/keys，简化版既然要
     * 独立成唯一入口，这些操作就得由自己提供——实现仍复用内核，只是换了归属。
     */
    @Transactional
    public Map<String, Object> issueKey(long id, ExternalKeyRequest request, long operator) {
        require(id);
        return workload.createKey(id, request, operator);
    }
    /** 连接或单个 Agent 的启用/停用；binding 为空表示改整个连接。 */
    @Transactional
    public void status(long id, Long binding, Map<String, Object> body, long operator) {
        require(id);
        workload.status(id, binding, body, operator);
    }
    /**
     * 撤销或「限期退休」一把公钥。规则沿用登记侧那套：kid 是 JWK 指纹，退役要在一小时内？
     * 不——是一天内，且只能由管理员发起。
     */
    @Transactional
    public void keyStatus(long id, long binding, String kid, Map<String, Object> body, long operator) {
        require(id);
        String status = AgentProofCrypto.text(body, "status", 16);
        if (!kid.matches("[A-Za-z0-9_-]{43}") || !("RETIRING".equals(status) || "REVOKED".equals(status))) throw AgentProofCrypto.invalid();
        Instant until = null;
        if ("RETIRING".equals(status)) {
            until = Instant.parse(AgentProofCrypto.text(body, "validUntil", 64));
            if (!until.isAfter(Instant.now()) || until.isAfter(Instant.now().plusSeconds(86400))) throw new IllegalArgumentException("退休截止时间必须在未来一天内");
        }
        String reason = body.get("reason") == null ? null : AgentProofCrypto.text(body, "reason", 500);
        if (repository.keyStatus(id, binding, kid, status, until, operator, reason) != 1) throw new IllegalArgumentException("密钥不存在、已撤销或状态转换无效");
        repository.audit(id, "AGENT_KEY_" + status, operator, "{\"kid\":\"" + kid + "\"}");
    }
    /** 改名与改 AKB 对外地址。可读写范围有独立端点，不在这里顺手改。 */
    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body, long operator) {
        Map<String, Object> policy = require(id);
        long principal = ((Number) policy.get("application_principal_id")).longValue();
        if (body.containsKey("name")) keys.updatePrincipal(principal, AgentProofCrypto.text(body, "name", 128), null, null);
        if (body.containsKey("resourceOrigin")) {
            String origin = workload.canonicalResourceOrigin(AgentProofCrypto.text(body, "resourceOrigin", 255));
            repository.updateResourceOrigin(id, origin);
            repository.audit(id, "UPDATE_CONNECTION", operator, "{\"resourceOrigin\":\"" + origin + "\"}");
        }
        return detail(id);
    }
    /**
     * 撤销接入：策略进入终态（不能再启用），共享 Key 一并吊销。
     * 不物理删除——删了以后这条接入做过什么就没有记录了，而且共享 Key 被记忆与审计表外键引用，
     * 物理删会撞约束。要彻底清掉用 purge，且只能对已撤销的执行。
     */
    @Transactional
    public void revoke(long id, long operator) {
        Map<String, Object> policy = require(id);
        long principal = ((Number) policy.get("application_principal_id")).longValue();
        Map<String, Object> body = new LinkedHashMap<>(); body.put("status", "REVOKED");
        workload.status(id, null, body, operator);
        for (Map<String, Object> key : keys.listPrincipalKeys(principal)) {
            if (!"REVOKED".equals(String.valueOf(key.get("status")))) keys.revoke(((Number) key.get("id")).longValue());
        }
        repository.audit(id, "REVOKE_CONNECTION", operator, "{\"principalId\":" + principal + "}");
    }
    /** 彻底删除：只接受已撤销的接入。审计先落盘，再解绑 Key、删策略及其从属，最后停用它主体。 */
    @Transactional
    public void purge(long id, long operator) {
        Map<String, Object> policy = require(id);
        if (!"REVOKED".equals(policy.get("status"))) throw new IllegalArgumentException("请先撤销该接入，再执行彻底删除");
        long principal = ((Number) policy.get("application_principal_id")).longValue();
        repository.audit(id, "PURGE_CONNECTION", operator, "{\"principalId\":" + principal + "}");
        repository.detachKeys(id);
        repository.purgePolicy(id);
        keys.updatePrincipal(principal, null, "DISABLED", null);
    }
    /** 这条接入最近的调用记录：一个接入对应一个应用主体，审计表里记的就是它。 */
    public List<ApiCallAudit> calls(long id, int limit) {
        Map<String, Object> policy = require(id);
        long principal = ((Number) policy.get("application_principal_id")).longValue();
        return principals.listApiCallAudits(null, principal, Math.min(Math.max(limit, 1), 200));
    }
    /**
     * 接入自检。不碰私钥，也不假装能验证连通——能不能调通取决于平台那边有没有配好私钥，
     * AKB 这边只能回答「配置齐不齐」，把缺哪一项说清楚。
     */
    public List<Map<String, Object>> checklist(long id) {
        Map<String, Object> policy = require(id);
        long principal = ((Number) policy.get("application_principal_id")).longValue();
        boolean agentActive = false, keyActive = false;
        Instant now = Instant.now();
        for (Map<String, Object> binding : repository.bindings(id)) {
            ExternalPrincipal agent = principals.getPrincipal(((Number) binding.get("agent_principal_id")).longValue());
            if (!"ACTIVE".equals(binding.get("status")) || agent == null || !"ACTIVE".equals(agent.getStatus())) continue;
            agentActive = true;
            for (Map<String, Object> registered : repository.agentKeys(id, ((Number) binding.get("id")).longValue())) {
                String status = String.valueOf(registered.get("status"));
                if (!("ACTIVE".equals(status) || "RETIRING".equals(status))) continue;
                if (registered.get("valid_from") != null && AgentProofCrypto.instant(registered.get("valid_from")).isAfter(now)) continue;
                if ("RETIRING".equals(status) && registered.get("valid_until") == null) continue;
                if (registered.get("valid_until") != null && !AgentProofCrypto.instant(registered.get("valid_until")).isAfter(now)) continue;
                keyActive = true;
            }
        }
        ExternalPrincipal app = principals.getPrincipal(principal);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(check("服务接入及应用主体处于启用状态", "ACTIVE".equals(policy.get("status")) && app != null && "ACTIVE".equals(app.getStatus()), "点「启用接入」"));
        items.add(check("授权方式已确认", !"REVIEW_REQUIRED".equals(policy.get("authorization_mode")), "确认历史授权方式后再启用连接"));
        items.add(check("已授权至少一个可读知识库", !keys.grantedKnowledgeBases(principal).isEmpty(), "在上方选择知识库并保存可读范围"));
        items.add(check("已登记至少一个启用的 Agent", agentActive, "登记 Agent 公钥"));
        items.add(check("至少有一把生效中的公钥", keyActive, "导入该 Agent 的 public.jwk"));
        return items;
    }
    private static Map<String, Object> check(String item, boolean ok, String hint) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("item", item); row.put("ok", Boolean.valueOf(ok)); row.put("hint", hint);
        return row;
    }
    /** Historical migration review changes authority explicitly, never as a side effect of listing. */
    @Transactional
    public void reviewAuthorization(long id, Map<String, Object> body, long operator) {
        require(id);
        String mode = AgentProofCrypto.text(body, "authorizationMode", 32);
        if (!("SERVICE_SCOPE".equals(mode) || "AGENT_INTERSECTION".equals(mode))) throw new IllegalArgumentException("无效授权方式");
        String reason = AgentProofCrypto.text(body, "reason", 500);
        if (repository.reviewAuthorization(id, mode, platform(body)) != 1) throw new IllegalArgumentException("连接已确认或已撤销，请刷新状态");
        repository.audit(id, "REVIEW_AUTHORIZATION", operator, new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
            .put("authorizationMode", mode).put("reason", reason).toString());
    }
    private static String platform(Map<String, Object> body) {
        String type = body.get("platformType") == null ? "custom" : AgentProofCrypto.text(body, "platformType", 32);
        if (!Arrays.asList("octop", "opencode", "custom").contains(type)) throw new IllegalArgumentException("无效平台类型");
        return type;
    }
    /** Key-only discovery returns public signing coordinates, never authority to read knowledge. */
    public Map<String, Object> configuration(String secret, String external) {
        ApiKey key = secret == null ? null : keys.authenticate(secret);
        if (key == null || !key.isWorkloadCredential() || key.getWorkloadPolicyId() == null) throw new UnauthorizedException("共享服务 Key 无效或已过期");
        limiter.check(key);
        Map<String, Object> p = require(key.getWorkloadPolicyId());
        if (!"ACTIVE".equals(p.get("status")) || "REVIEW_REQUIRED".equals(p.get("authorization_mode")) || !key.getPrincipalId().equals(((Number)p.get("application_principal_id")).longValue())) throw new UnauthorizedException("服务接入已停用");
        String agent = AgentProofCrypto.uuid(external);
        Map<String, Object> b = repository.binding(key.getWorkloadPolicyId(), agent, agent);
        if (b == null) {
            for (Map<String,Object> existing : repository.bindings(key.getWorkloadPolicyId())) {
                if (agent.equals(existing.get("external_agent_id"))) {
                    b = repository.binding(key.getWorkloadPolicyId(), agent, String.valueOf(existing.get("runtime_agent_key")));
                    break;
                }
            }
        }
        if (b == null) throw new SecurityException("请先在 AKB 登记该 Agent ID 和公钥");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyId", String.valueOf(key.getWorkloadPolicyId())); result.put("applicationPrincipalId", String.valueOf(key.getPrincipalId()));
        result.put("bindingId", String.valueOf(b.get("id"))); result.put("externalAgentId", agent); result.put("runtimeAgentKey", b.get("runtime_agent_key") == null ? agent : b.get("runtime_agent_key"));
        String[][] fields = {{"issuer","issuer"},{"audience","audience"},{"resource","resource_origin"},{"platformId","platform_id"},{"installationId","installation_id"},{"workspaceId","workspace_id"}};
        for (String[] field : fields) result.put(field[0], p.get(field[1]));
        return result;
    }
}
