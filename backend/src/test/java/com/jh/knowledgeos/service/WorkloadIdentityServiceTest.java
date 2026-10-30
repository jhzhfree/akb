package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jh.knowledgeos.config.WorkloadRequestFilter;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.exception.WorkloadUnavailableException;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 服务层的前置校验：这些判断发生在把证明交给 V2 校验器之前，与具体签名无关，所以不需要真密钥。
 *
 * <p>原先这里的用例测的是 V1（本地起一个 JWKS 服务、按 V1 的 claim 拼 JWT），随 V1 一起删除；
 * 签名、重放、撤销那部分由 {@code AgentKeyProofVerifierTest} 覆盖。
 */
class WorkloadIdentityServiceTest {
    private WorkloadIdentityRepository repository;
    private WorkloadIdentityService service;
    private ApiKey key;

    @BeforeEach void setup() {
        repository = mock(WorkloadIdentityRepository.class); service = new WorkloadIdentityService(repository);
        Map<String, Object> policy = new HashMap<String, Object>();
        policy.put("id", 7L); policy.put("application_principal_id", 1L); policy.put("status", "ACTIVE");
        policy.put("proof_profile", AgentProofCrypto.V2);
        when(repository.policy(7L)).thenReturn(policy);
        key = new ApiKey(); key.setPrincipalId(1L); key.setPrincipalType("APPLICATION");
        key.setIdentityMode("APPLICATION_WORKLOAD"); key.setWorkloadPolicyId(7L);
    }
    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/external/search");
        request.addHeader(ExternalAccessService.HEADER, "synthetic-test-service-key");
        request.addHeader(WorkloadRequestFilter.HEADER, "proof");
        return request;
    }
    @Test void missingOrConflictingAuthenticationFailsClosed() {
        assertThrows(UnauthorizedException.class, () -> service.authenticate(new MockHttpServletRequest(), key));
        MockHttpServletRequest mixed = request(); mixed.addHeader("Authorization", "Bearer other");
        assertThrows(UnauthorizedException.class, () -> service.authenticate(mixed, key));
        MockHttpServletRequest delegated = request();
        delegated.addHeader(ExternalAccessService.OCTOP_ASSERTION_HEADER, "assertion");
        assertThrows(UnauthorizedException.class, () -> service.authenticate(delegated, key));
    }
    /** V1 已下线：策略上还写着 V1 的一律拒绝，而不是退回远程 JWKS 去验一遍。 */
    @Test void legacyV1PolicyIsRejectedInsteadOfFallingBackToRemoteJwks() {
        Map<String, Object> legacy = new HashMap<String, Object>(repository.policy(7L));
        legacy.put("proof_profile", "INSTALLATION_RS256_V1");
        when(repository.policy(7L)).thenReturn(legacy);
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(), key));
    }
    @Test void suspendedPolicyOrForeignApplicationIsRejected() {
        Map<String, Object> paused = new HashMap<String, Object>(repository.policy(7L));
        paused.put("status", "SUSPENDED");
        when(repository.policy(7L)).thenReturn(paused);
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(), key));
        ApiKey other = new ApiKey(); other.setPrincipalId(9L); other.setPrincipalType("APPLICATION");
        other.setIdentityMode("APPLICATION_WORKLOAD"); other.setWorkloadPolicyId(7L);
        assertThrows(UnauthorizedException.class, () -> service.authenticate(request(), other));
    }
    @Test void databaseFailureIsReportedAsUnavailable() {
        when(repository.policy(7L)).thenThrow(new DataAccessResourceFailureException("down"));
        assertThrows(WorkloadUnavailableException.class, () -> service.authenticate(request(), key));
    }
}
