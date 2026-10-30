package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.WorkloadRequestFilter;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.WorkloadIdentity;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.exception.WorkloadUnavailableException;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.security.MessageDigest;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Per-request platform proof. Only administrator-registered policy keys are trusted.
 *
 * <p>只认 V2：安装级证明（V1，远程 JWKS + 安装密钥签名）已下线。简化接入与历史的高级接入走的
 * 都是同一个 V2 校验器，这里不再保留分支——留着分支意味着每条请求都要先判断「这次是哪套」，
 * 而答案永远是 V2。
 */
@Service
public class WorkloadIdentityService {
    private final WorkloadIdentityRepository repository;
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
        if (!AgentProofCrypto.V2.equals(policy.get("proof_profile"))) throw unauthorized();
        return v2.authenticate(request, key, policy);
    }
    public static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static Long number(Object value) { return ((Number) value).longValue(); }
    private static UnauthorizedException unauthorized() { return new UnauthorizedException("无效、缺失或重放的工作负载身份证明"); }
}
