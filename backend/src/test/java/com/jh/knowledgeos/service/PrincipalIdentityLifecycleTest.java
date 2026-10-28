package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 主体与身份绑定的生命周期：停用主体、暂停 / 审批绑定。
 *
 * <p>设计 10 章要求这两处变更后「后续调用立即失败」——前者靠 Key 可用性判定检查 principalStatus，
 * 后者靠绑定查询只认 ACTIVE，所以断言都落在状态本身有没有写对。
 */
class PrincipalIdentityLifecycleTest {

    private final ExternalApiMapper mapper = mock(ExternalApiMapper.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final ApiKeyService apiKeys = new ApiKeyService(mapper, ids);
    private final DelegatedIdentityService identity =
        new DelegatedIdentityService(mapper, new KnowledgeProperties());

    @Test
    void disablingAPrincipalBlocksItsKeysWithoutRevokingThem() {
        ExternalPrincipal active = principal("ACTIVE");
        ExternalPrincipal disabled = principal("DISABLED");
        when(mapper.getPrincipal(901L)).thenReturn(active, disabled);
        when(mapper.updatePrincipalProfile(901L, "ops-agent", "DISABLED", null)).thenReturn(1);

        ExternalPrincipal result = apiKeys.updatePrincipal(901L, null, "disabled", null);

        assertEquals("DISABLED", result.getStatus());
        verify(mapper).updatePrincipalProfile(901L, "ops-agent", "DISABLED", null);
    }

    @Test
    void rejectsUnknownPrincipalStatus() {
        when(mapper.getPrincipal(901L)).thenReturn(principal("ACTIVE"));

        assertThrows(IllegalArgumentException.class, () -> apiKeys.updatePrincipal(901L, "x", "DELETED", null));
        assertThrows(IllegalArgumentException.class, () -> apiKeys.updatePrincipal(999L, "x", "DISABLED", null),
            "主体不存在");
    }

    @Test
    void suspendingABindingStopsDelegationImmediately() {
        when(mapper.listExternalIdentityBindings("OCTOP", null, null))
            .thenReturn(Collections.singletonList(binding(11L, "ACTIVE")));
        when(mapper.updateExternalIdentityBindingStatus(eq(11L), eq("SUSPENDED"), eq(7L), anyString())).thenReturn(1);

        Map<String, Object> result = identity.suspendOctopBinding(11L, 7L, "离职");

        assertEquals("SUSPENDED", result.get("status"));
        verify(mapper).insertExternalIdentityBindingAudit(eq(11L), eq("OCTOP"), anyString(), anyString(),
            any(Long.class), eq("SUSPEND"), eq(7L), anyString());
    }

    @Test
    void approvingAPendingBindingMakesItUsable() {
        when(mapper.listExternalIdentityBindings("OCTOP", null, null))
            .thenReturn(Collections.singletonList(binding(11L, "PENDING")));
        when(mapper.updateExternalIdentityBindingStatus(eq(11L), eq("ACTIVE"), eq(7L), anyString())).thenReturn(1);

        Map<String, Object> result = identity.approveOctopBinding(11L, 7L, "已核对两侧标识");

        assertEquals("ACTIVE", result.get("status"));
    }

    @Test
    void cannotSuspendARevokedBinding() {
        when(mapper.listExternalIdentityBindings("OCTOP", null, null))
            .thenReturn(Collections.singletonList(binding(11L, "REVOKED")));

        assertThrows(IllegalArgumentException.class, () -> identity.suspendOctopBinding(11L, 7L, "x"));
        assertThrows(IllegalArgumentException.class, () -> identity.getOctopBinding(99L), "绑定不存在");
    }

    private ExternalPrincipal principal(String status) {
        ExternalPrincipal principal = new ExternalPrincipal();
        principal.setId(901L);
        principal.setPrincipalCode("AGT_OPS");
        principal.setPrincipalType("AGENT");
        principal.setName("ops-agent");
        principal.setStatus(status);
        return principal;
    }

    private Map<String, Object> binding(Long id, String status) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("status", status);
        row.put("providerInstanceId", "octop-prod-01");
        row.put("externalUserId", "usr_01J");
        row.put("principalId", 901L);
        return row;
    }
}
