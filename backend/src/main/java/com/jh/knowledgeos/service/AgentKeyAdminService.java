package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrative key approval. Service credentials never authorize these operations. */
@Service
public class AgentKeyAdminService {
    private final WorkloadIdentityRepository repository;
    private final ObjectMapper json;
    private static final SecureRandom RANDOM = new SecureRandom();
    public AgentKeyAdminService(WorkloadIdentityRepository repository, ObjectMapper json) { this.repository = repository; this.json = json; }

    public List<Map<String, Object>> keys(long policy, long binding) {
        requireBinding(policy, binding, false, false); return repository.agentKeys(policy, binding);
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> challenge(long policy, long binding, Map<String, Object> body, long operator) {
        Map<String, Object> registered = requireBinding(policy, binding, true, true);
        if (!(body.get("publicJwk") instanceof Map)) throw AgentProofCrypto.invalid();
        RSAKey key = AgentProofCrypto.publicKey((Map<String, Object>) body.get("publicJwk"));
        if (repository.pendingChallenges(binding) >= 5 || repository.activeKeyCount(policy, binding) >= 2) throw new IllegalArgumentException("待处理挑战或活动密钥已达上限");
        byte[] nonceBytes = new byte[32]; RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes), id = UUID.randomUUID().toString();
        Instant expires = Instant.now().plusSeconds(300);
        repository.createChallenge(id, policy, binding, key.toJSONString(), key.getKeyID(), sha(nonce), expires, operator);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("challengeId", id); result.put("nonce", nonce); result.put("policyId", String.valueOf(policy));
        result.put("bindingId", String.valueOf(binding)); result.put("externalAgentId", registered.get("external_agent_id"));
        result.put("jkt", key.getKeyID()); result.put("purpose", "akb.agent-key.register");
        result.put("audience", "akb-agent-key-registration"); result.put("expiresAt", expires.getEpochSecond());
        audit(policy, "KEY_CHALLENGE", operator, id, key.getKeyID()); return result;
    }
    @Transactional
    public Map<String, Object> register(long policy, long binding, Map<String, Object> body, long operator) {
        Map<String, Object> registered = requireBinding(policy, binding, true, true);
        String id = AgentProofCrypto.uuid(AgentProofCrypto.text(body, "challengeId", 36));
        Map<String, Object> challenge = repository.challenge(id);
        if (challenge == null || !Long.valueOf(policy).equals(number(challenge, "policy_id")) || !Long.valueOf(binding).equals(number(challenge, "binding_id"))
            || !Long.valueOf(operator).equals(number(challenge, "created_by")) || challenge.get("consumed_at") != null
            || !AgentProofCrypto.instant(challenge.get("expires_at")).isAfter(Instant.now())) throw AgentProofCrypto.invalid();
        AgentProofCrypto.Proof proof = AgentProofCrypto.parse(AgentProofCrypto.text(body, "registrationProof", 8192), "akb-key-registration+jwt");
        RSAKey key = AgentProofCrypto.publicKey(AgentProofCrypto.object(String.valueOf(challenge.get("public_jwk"))));
        proof.verify(key);
        Map<String, Object> c = proof.claims;
        if (AgentProofCrypto.integer(c, "ver") != 2 || !"akb.agent-key.register".equals(c.get("purpose")) || !"akb-agent-key-registration".equals(c.get("aud"))
            || !id.equals(c.get("challenge_id")) || !String.valueOf(policy).equals(c.get("policy_id")) || !String.valueOf(binding).equals(c.get("binding_id"))
            || !("agent:" + registered.get("external_agent_id")).equals(c.get("sub")) || !key.getKeyID().equals(c.get("jkt"))
            || !AgentProofCrypto.equal(sha(AgentProofCrypto.text(c, "nonce", 128)), String.valueOf(challenge.get("nonce_hash")))) throw AgentProofCrypto.invalid();
        Instant expires = AgentProofCrypto.expiry(c, 300);
        if (expires.isAfter(AgentProofCrypto.instant(challenge.get("expires_at"))) || repository.activeKeyCount(policy, binding) >= 2) throw AgentProofCrypto.invalid();
        if (repository.consumeChallenge(id) != 1) throw AgentProofCrypto.invalid();
        Map<String, Object> result = repository.registerKey(policy, binding, key.getKeyID(), key.toJSONString(), operator);
        audit(policy, "REGISTER_AGENT_KEY", operator, id, key.getKeyID()); return result;
    }
    @Transactional
    public void status(long policy, long binding, String kid, Map<String, Object> body, long operator) {
        requireBinding(policy, binding, true, false);
        String status = AgentProofCrypto.text(body, "status", 16);
        if (!kid.matches("[A-Za-z0-9_-]{43}") || !("RETIRING".equals(status) || "REVOKED".equals(status))) throw AgentProofCrypto.invalid();
        Instant until = null;
        if ("RETIRING".equals(status)) {
            until = Instant.parse(AgentProofCrypto.text(body, "validUntil", 64));
            if (!until.isAfter(Instant.now()) || until.isAfter(Instant.now().plusSeconds(86400))) throw new IllegalArgumentException("退休截止时间必须在未来一天内");
        }
        String reason = body.get("reason") == null ? null : AgentProofCrypto.text(body, "reason", 500);
        if (repository.keyStatus(policy, binding, kid, status, until, operator, reason) != 1) throw new IllegalArgumentException("密钥不存在、已撤销或状态转换无效");
        audit(policy, "AGENT_KEY_" + status, operator, null, kid);
    }
    private Map<String, Object> requireBinding(long policy, long binding, boolean lock, boolean active) {
        Map<String, Object> configured = repository.policy(policy);
        if (configured == null || !AgentProofCrypto.V2.equals(configured.get("proof_profile")) || (active && !"ACTIVE".equals(configured.get("status")))) throw AgentProofCrypto.invalid();
        Map<String, Object> registered = repository.bindingById(policy, binding, lock);
        if (registered == null || (active && repository.binding(policy, String.valueOf(registered.get("external_agent_id")), String.valueOf(registered.get("runtime_agent_key"))) == null)) throw AgentProofCrypto.invalid();
        return registered;
    }
    private void audit(long policy, String action, long operator, String challenge, String kid) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>(); detail.put("challengeId", challenge); detail.put("kid", kid);
        try { repository.audit(policy, action, operator, json.writeValueAsString(detail)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("管理审计序列化失败"); }
    }
    private static Long number(Map<String, Object> value, String name) { return ((Number) value.get(name)).longValue(); }
    private static String sha(String value) { return WorkloadIdentityService.sha256(value.getBytes(StandardCharsets.UTF_8)); }
}
