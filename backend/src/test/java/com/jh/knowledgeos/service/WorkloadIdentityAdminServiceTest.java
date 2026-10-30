package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class WorkloadIdentityAdminServiceTest {
    private final WorkloadIdentityRepository repository = mock(WorkloadIdentityRepository.class);
    private final ExternalApiMapper principals = mock(ExternalApiMapper.class);
    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final WorkloadIdentityAdminService service = new WorkloadIdentityAdminService(repository, principals, keys, new ObjectMapper(), false, true);
    private ExternalPrincipal principal(long id, String type) {
        ExternalPrincipal p = new ExternalPrincipal(); p.setId(id); p.setPrincipalType(type); p.setStatus("ACTIVE"); return p;
    }
    private Map<String,Object> policy() {
        Map<String,Object> p = new HashMap<String,Object>(); p.put("application_principal_id", 1L); p.put("status", "ACTIVE"); p.put("installation_id", "install");
        when(repository.policy(7L)).thenReturn(p); when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION")); return p;
    }
    @Test void issuingStrictKeySetsPolicyAtomicallyAndDoesNotAuditSecret() {
        policy(); Map<String,Object> issued = new HashMap<String,Object>(); issued.put("id", 2L); issued.put("secret", "only-once-secret");
        when(keys.createServiceKey(anyLong(), any(), anyList(), any(), any(), any(), anyLong())).thenReturn(issued);
        ExternalKeyRequest request = new ExternalKeyRequest(); request.setExpiresAt(LocalDateTime.now().plusDays(1).toString());
        Map<String,Object> result = service.createKey(7L, request, 9);
        assertEquals("APPLICATION_WORKLOAD", result.get("identityMode")); assertEquals(7L, result.get("workloadPolicyId"));
        verify(repository).configureKey(2L, 7L);
        verify(repository).audit(eq(7L), eq("CREATE_KEY"), eq(9L), eq("{\"keyId\":2}"));
    }
    @Test void rejectsUnboundedExpiryAndWriteScopesBeforeCreatingKey() {
        policy(); ExternalKeyRequest request = new ExternalKeyRequest();
        assertThrows(IllegalArgumentException.class, () -> service.createKey(7, request, 9));
        request.setExpiresAt(LocalDateTime.now().plusDays(1).toString()); request.setScopes(Collections.singletonList("MEMORY_MANAGE"));
        assertThrows(IllegalArgumentException.class, () -> service.createKey(7, request, 9));
        verifyNoInteractions(keys);
    }
    @Test void productionPoliciesRejectInsecureJwksAndAgentIdentityMustMatch() {
        when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION"));
        Map<String,Object> body = new HashMap<String,Object>(); body.put("applicationPrincipalId", 1L); body.put("issuer", "https://identity.example"); body.put("jwksUri", "http://127.0.0.1/jwks");
        assertThrows(IllegalArgumentException.class, () -> service.createPolicy(body, 9));
        policy(); body.clear(); body.put("externalAgentId", "external-a"); body.put("runtimeAgentKey", "build"); body.put("agentPrincipalId", 3L);
        when(principals.getPrincipal(3L)).thenReturn(principal(3, "AGENT"));
        assertThrows(IllegalArgumentException.class, () -> service.bind(7, body, 9));
        verify(repository, never()).createBinding(anyLong(), anyString(), anyString(), anyLong());
    }
    /**
     * 内网部署用明文 HTTP 是常态（没有公网证书，也不该为此开一个），所以私有地址段与内网
     * 命名放行；公网地址仍必须 HTTPS——工作负载证明在明文链路上可被中间人替换。
     */
    @Test void intranetHttpOriginsAreAcceptedWhilePublicOnesStillRequireHttps() {
        when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION"));
        List<String> accepted = Arrays.asList(
            "http://192.168.1.50:8080", "http://10.20.30.40", "http://172.16.5.9:18080",
            "http://akb.internal", "http://akb.corp:8080", "http://akb-gateway");
        List<String> rejected = Arrays.asList(
            "http://203.0.113.10", "http://8.8.8.8", "http://akb.example.com", "ftp://192.168.1.50");
        for (String url : accepted) {
            service.createPolicy(policyBody(url), 9);
        }
        for (String url : rejected) {
            assertThrows(IllegalArgumentException.class, () -> service.createPolicy(policyBody(url), 9), url);
        }
    }

    /** 本机明文仍由独立开关管：只开内网开关时不该顺带放开 127.0.0.1。 */
    @Test void loopbackHttpStillNeedsItsOwnSwitch() {
        WorkloadIdentityAdminService strict = new WorkloadIdentityAdminService(repository, principals, keys, new ObjectMapper(), false, false);
        when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION"));
        assertThrows(IllegalArgumentException.class, () -> strict.createPolicy(policyBody("http://192.168.1.50"), 9));
    }

    /**
     * 报错要能定位：这三个地址都是管理员手填的，只说「地址必须使用 HTTPS」等于让人猜是哪个
     * 框、哪个地址、为什么。所以错误里带上字段名、完整地址与被判定为公网的主机名。
     */
    @Test void rejectedHttpOriginNamesTheFieldAndTheHost() {
        when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPolicy(policyBody("http://203.0.113.10/agents"), 9));
        assertTrue(ex.getMessage().contains("issuer"), ex.getMessage());
        assertTrue(ex.getMessage().contains("203.0.113.10"), ex.getMessage());
    }

    /**
     * 下划线主机名（容器里常见）会让 URI.getHost() 返回 null。以前报的是「必须使用无凭据、
     * 查询和片段的地址」，跟真正的原因毫无关系；现在直说主机名解析不出来。
     */
    @Test void unresolvableHostSaysSoInsteadOfBlamingCredentials() {
        when(principals.getPrincipal(1L)).thenReturn(principal(1, "APPLICATION"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.createPolicy(policyBody("http://akb_gateway/agents"), 9));
        assertTrue(ex.getMessage().contains("无法解析"), ex.getMessage());
    }

    /** 只变待校验的地址，其余字段给合法值，否则先撞上的是「缺字段」而不是 URL 规则。 */
    private Map<String,Object> policyBody(String issuer) {
        Map<String,Object> body = new HashMap<String,Object>();
        body.put("applicationPrincipalId", 1L);
        body.put("issuer", issuer);
        body.put("jwksUri", "https://identity.example/jwks");
        body.put("audience", "https://akb.example.com");
        body.put("installationId", "install-1");
        body.put("workspaceId", "ws-1");
        return body;
    }

    @Test void pausedPolicyBindingsRemainVisibleForManagement() {
        Map<String,Object> p = policy(); p.put("status", "PAUSED");
        when(repository.bindings(7L)).thenReturn(Collections.singletonList(Collections.<String,Object>singletonMap("id", 3L)));
        assertEquals(1, service.bindings(7L).size());
        when(repository.policy(999L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.bindings(999L));
    }
}
