package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.WorkloadRequestFilter;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.exception.WorkloadProofException;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;

/** Agent-bound public keys, with no remote key discovery or downgrade path. */
public final class AgentKeyProofVerifier {
    private final WorkloadIdentityRepository repository;
    private final AtomicLong cleanup = new AtomicLong();
    public AgentKeyProofVerifier(WorkloadIdentityRepository repository) { this.repository = repository; }

    @SuppressWarnings("unchecked")
    public WorkloadIdentity authenticate(HttpServletRequest request, ApiKey key, Map<String, Object> policy) {
        try {
            AgentProofCrypto.Proof proof = AgentProofCrypto.parse(request.getHeader(WorkloadRequestFilter.HEADER), "akb-agent-proof+jwt");
            Map<String, Object> c = proof.claims;
            String subject = AgentProofCrypto.text(c, "sub", 128);
            if (!subject.startsWith("agent:")) throw AgentProofCrypto.invalid();
            String external = AgentProofCrypto.uuid(subject.substring(6));
            String runtime = AgentProofCrypto.text(c, "runtime_agent", 128);
            Map<String, Object> binding = repository.binding(key.getWorkloadPolicyId(), external, runtime);
            if (binding == null) throw AgentProofCrypto.invalid();
            String kid = AgentProofCrypto.text(proof.header, "kid", 43);
            Map<String, Object> registered = repository.agentKey(key.getWorkloadPolicyId(), ((Number) binding.get("id")).longValue(), kid);
            if (registered == null) throw AgentProofCrypto.invalid();
            proof.verify(AgentProofCrypto.publicKey(AgentProofCrypto.object(String.valueOf(registered.get("public_jwk")))));
            Instant expires = AgentProofCrypto.expiry(c, 60), now = Instant.now();
            if (!"ACTIVE".equals(registered.get("status")) && !"RETIRING".equals(registered.get("status"))) throw AgentProofCrypto.invalid();
            if (registered.get("valid_from") != null && AgentProofCrypto.instant(registered.get("valid_from")).isAfter(now)) throw AgentProofCrypto.invalid();
            if ("RETIRING".equals(registered.get("status")) && registered.get("valid_until") == null) throw AgentProofCrypto.invalid();
            if (registered.get("valid_until") != null && (!AgentProofCrypto.instant(registered.get("valid_until")).isAfter(now)
                || expires.isAfter(AgentProofCrypto.instant(registered.get("valid_until"))))) throw AgentProofCrypto.invalid();
            if (AgentProofCrypto.integer(c, "ver") != 2 || !String.valueOf(key.getWorkloadPolicyId()).equals(c.get("policy_id"))
                || !String.valueOf(key.getPrincipalId()).equals(c.get("azp"))) throw AgentProofCrypto.invalid();
            String[][] fields = {{"iss", "issuer"}, {"aud", "audience"}, {"resource", "resource_origin"},
                {"platform_id", "platform_id"}, {"installation_id", "installation_id"}, {"workspace_id", "workspace_id"}};
            for (String[] field : fields) if (!(c.get(field[0]) instanceof String) || !c.get(field[0]).equals(policy.get(field[1]))) throw AgentProofCrypto.invalid();
            String serviceKey = request.getHeader(ExternalAccessService.HEADER);
            if (serviceKey == null || !serviceKey.matches("[A-Za-z0-9_-]+") || !AgentProofCrypto.equal(WorkloadIdentityService.sha256(serviceKey.getBytes(StandardCharsets.UTF_8)),
                AgentProofCrypto.text(c, "credential_sha256", 64))) throw AgentProofCrypto.invalid();
            if (!(c.get("request_binding") instanceof Map)) throw AgentProofCrypto.invalid();
            Map<String, Object> bound = (Map<String, Object>) c.get("request_binding");
            String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            Object raw = request.getAttribute(WorkloadRequestFilter.BODY);
            if (!(raw instanceof byte[]) || target.length() > 2048 || !("GET".equals(request.getMethod()) || "POST".equals(request.getMethod()))
                || !request.getMethod().equals(bound.get("method")) || !target.equals(bound.get("target"))
                || !AgentProofCrypto.equal(WorkloadIdentityService.sha256((byte[]) raw), AgentProofCrypto.text(bound, "body_sha256", 64))) throw AgentProofCrypto.invalid();
            String session = AgentProofCrypto.text(c, "session_id", 128);
            String invocation = AgentProofCrypto.uuid(AgentProofCrypto.text(c, "invocation_id", 36));
            String message = c.containsKey("message_id") ? AgentProofCrypto.text(c, "message_id", 128) : null;
            String jti = AgentProofCrypto.uuid(AgentProofCrypto.text(c, "jti", 36));
            repository.consumeV2(key.getWorkloadPolicyId(), kid, jti, expires);
            long previous = cleanup.get(), millis = System.currentTimeMillis();
            if (millis - previous > 60000 && cleanup.compareAndSet(previous, millis)) repository.cleanV2();
            return new WorkloadIdentity(((Number) binding.get("agent_principal_id")).longValue(), session, invocation,
                AgentProofCrypto.V2, kid, ((Number) registered.get("id")).longValue(), message, (String) c.get("platform_id"), external, String.valueOf(registered.get("status")));
        } catch (IllegalArgumentException | DuplicateKeyException ex) { throw new WorkloadProofException(); }
    }
}
