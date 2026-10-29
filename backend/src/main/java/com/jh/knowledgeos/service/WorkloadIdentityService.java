package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.WorkloadRequestFilter;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import com.jh.knowledgeos.exception.WorkloadUnavailableException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

/** Per-request platform proof. Only administrator-registered policy keys are trusted. */
@Service
public class WorkloadIdentityService {
    private final WorkloadIdentityRepository repository;
    // Bounded cache; every request still checks current policy/binding/principal status.
    private final Map<String, JwtDecoder> decoders = new LinkedHashMap<String, JwtDecoder>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, JwtDecoder> eldest) { return size() > 64; }
    };
    private final AtomicLong lastCleanup = new AtomicLong();
    private final AgentKeyProofVerifier v2;
    public WorkloadIdentityService(WorkloadIdentityRepository repository) { this.repository = repository; this.v2 = new AgentKeyProofVerifier(repository); }

    public WorkloadIdentity authenticate(HttpServletRequest request, ApiKey key) {
        try { return authenticateStored(request, key); }
        catch (DataAccessException ex) { throw new WorkloadUnavailableException(); }
    }
    private WorkloadIdentity authenticateStored(HttpServletRequest request, ApiKey key) {
        String proof = request.getHeader(WorkloadRequestFilter.HEADER);
        if (proof == null || proof.length() > 8192 || request.getHeader(ExternalAccessService.HEADER) == null
            || request.getHeader(ExternalAccessService.HEADER).trim().isEmpty() || !"APPLICATION".equals(key.getPrincipalType())
            || key.getWorkloadPolicyId() == null || key.getPrincipalId() == null || request.getHeader("Authorization") != null
            || request.getHeader(ExternalAccessService.OCTOP_ASSERTION_HEADER) != null) {
            throw unauthorized();
        }
        Map<String, Object> policy = repository.policy(key.getWorkloadPolicyId());
        if (policy == null || !"ACTIVE".equals(policy.get("status"))
            || !key.getPrincipalId().equals(number(policy.get("application_principal_id")))) throw unauthorized();
        if (AgentProofCrypto.V2.equals(policy.get("proof_profile"))) return v2.authenticate(request, key, policy);
        if (policy.get("proof_profile") != null && !AgentProofCrypto.V1.equals(policy.get("proof_profile"))) throw unauthorized();
        try {
            Jwt jwt = decoder(policy).decode(proof);
            Instant now = Instant.now();
            if (jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                || jwt.getIssuedAt().isAfter(now.plusSeconds(30)) || !jwt.getExpiresAt().isAfter(now)
                || Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds() <= 0
                || Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds() > 60
                || jwt.getAudience() == null || !jwt.getAudience().contains(text(policy, "audience"))) throw unauthorized();
            String jti = required(jwt.getId());
            if (!jti.matches("[A-Za-z0-9_-]{16,128}")) throw unauthorized();
            if (!String.valueOf(key.getPrincipalId()).equals(jwt.getClaimAsString("azp"))
                || !text(policy, "installation_id").equals(jwt.getClaimAsString("installation_id"))
                || !text(policy, "workspace_id").equals(jwt.getClaimAsString("workspace_id"))) throw unauthorized();
            String subject = required(jwt.getSubject());
            if (!subject.startsWith("agent:")) throw unauthorized();
            Map<String, Object> binding = repository.binding(key.getWorkloadPolicyId(), subject.substring(6),
                required(jwt.getClaimAsString("runtime_agent")));
            if (binding == null || !(binding.get("agent_principal_id") instanceof Number)) throw new SecurityException("Agent 未登记、已停用或不允许由此应用代理");
            Map<String, Object> bound = jwt.getClaimAsMap("request_binding");
            Object raw = request.getAttribute(WorkloadRequestFilter.BODY);
            if (bound == null || !(raw instanceof byte[])) throw unauthorized();
            String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            if (!request.getMethod().equals(bound.get("method")) || !target.equals(bound.get("target"))
                || !sha256((byte[]) raw).equals(bound.get("body_sha256"))) throw unauthorized();
            WorkloadIdentity identity = new WorkloadIdentity(number(binding.get("agent_principal_id")),
                required(jwt.getClaimAsString("session_id")), required(jwt.getClaimAsString("invocation_id")));
            repository.consume(sha256(text(policy, "issuer").getBytes(StandardCharsets.UTF_8)), jti, jwt.getExpiresAt());
            long previous = lastCleanup.get();
            if (System.currentTimeMillis() - previous > 60000 && lastCleanup.compareAndSet(previous, System.currentTimeMillis())) repository.cleanExpired();
            return identity;
        } catch (JwtException | IllegalArgumentException | DuplicateKeyException ex) {
            throw unauthorized();
        }
    }
    private synchronized JwtDecoder decoder(Map<String, Object> policy) {
        String cacheKey = policy.get("id") + "\0" + policy.get("issuer") + "\0" + policy.get("jwks_uri");
        JwtDecoder current = decoders.get(cacheKey);
        if (current == null) {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(text(policy, "jwks_uri")).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(text(policy, "issuer")));
            current = decoder; decoders.put(cacheKey, current);
        }
        return current;
    }
    public static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static Long number(Object value) { return ((Number) value).longValue(); }
    private static String text(Map<String, Object> map, String name) { return String.valueOf(map.get(name)); }
    private static String required(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) throw unauthorized();
        return value;
    }
    private static UnauthorizedException unauthorized() { return new UnauthorizedException("无效、缺失或重放的工作负载身份证明"); }
}
