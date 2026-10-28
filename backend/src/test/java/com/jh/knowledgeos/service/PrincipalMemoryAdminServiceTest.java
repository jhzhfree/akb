package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.dto.AgentMemoryScopeGrantRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 主体级记忆身份与授权：设计 7.4 的 memory-profile / memory-grants。 */
class PrincipalMemoryAdminServiceTest {

    private final ExternalApiMapper external = mock(ExternalApiMapper.class);
    private final AgentMemoryMapper memory = mock(AgentMemoryMapper.class);
    private final PrincipalMemoryAdminService service = new PrincipalMemoryAdminService(external, memory);

    @Test
    void profileReportsUnconfiguredWhenNoRowExists() {
        when(external.getPrincipal(901L)).thenReturn(principal());
        when(external.getPrincipalMemoryProfile(901L)).thenReturn(null);

        Map<String, Object> result = service.profile(901L);

        assertEquals(Long.valueOf(901L), result.get("principalId"));
        assertEquals(Boolean.FALSE, result.get("configured"), "未配置时运行时会退回 Key 旧列兜底，控制台要能看出来");
    }

    @Test
    void savingProfileSeedsThePrincipalsOwnAgentGrant() {
        when(external.getPrincipal(901L)).thenReturn(principal());
        when(external.getPrincipalMemoryProfile(901L)).thenReturn(profileRow());

        service.saveProfile(901L, "octop-prod", "agent-ops", 7L);

        verify(external).upsertPrincipalMemoryProfile(901L, "octop-prod", "agent-ops", 7L);
        // 少了这条播种，主体即便拿到 MEMORY_MANAGE 也读不到自己的私有记忆。
        verify(external).insertDefaultAgentMemoryGrant(901L, "octop-prod", "agent-ops", 7L);
    }

    @Test
    void grantsRequireAConfiguredProfile() {
        when(external.getPrincipal(901L)).thenReturn(principal());
        when(external.getPrincipalMemoryProfile(901L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.grants(901L));
        assertThrows(IllegalArgumentException.class,
            () -> service.replaceGrants(901L, Collections.<AgentMemoryScopeGrantRequest>emptyList(), 7L),
            "没有 namespace 就没有授权可落的维度");
    }

    @Test
    void replacingGrantsKeepsThePrincipalsOwnAgentScope() {
        when(external.getPrincipal(901L)).thenReturn(principal());
        when(external.getPrincipalMemoryProfile(901L)).thenReturn(profileRow());

        service.replaceGrants(901L, Collections.singletonList(grant("PROJECT", "release-2026", true, false)), 7L);

        verify(memory).deletePrincipalScopeAccess(901L, "agent-ops");
        verify(memory).insertPrincipalScopeAccess(901L, "octop-prod", "PROJECT", "release-2026", true, false, 7L);
    }

    @Test
    void rejectsInvalidGrants() {
        when(external.getPrincipal(901L)).thenReturn(principal());
        when(external.getPrincipalMemoryProfile(901L)).thenReturn(profileRow());

        assertThrows(IllegalArgumentException.class,
            () -> service.replaceGrants(901L, Collections.singletonList(grant("ORG", "x", true, false)), 7L),
            "作用域类型不在允许集合内");
        assertThrows(IllegalArgumentException.class,
            () -> service.replaceGrants(901L, Collections.singletonList(grant("PROJECT", "", true, false)), 7L),
            "scopeCode 不能为空");
        assertThrows(IllegalArgumentException.class,
            () -> service.replaceGrants(901L, Collections.singletonList(grant("PROJECT", "p", false, false)), 7L),
            "每条授权至少要开一项读写");
    }

    @Test
    void rejectsInvalidIdentity() {
        when(external.getPrincipal(901L)).thenReturn(principal());

        assertThrows(IllegalArgumentException.class, () -> service.saveProfile(901L, "bad namespace", "agent-ops", 7L));
        assertThrows(IllegalArgumentException.class, () -> service.saveProfile(901L, "octop-prod", null, 7L));
        assertThrows(IllegalArgumentException.class, () -> service.saveProfile(999L, "octop-prod", "agent-ops", 7L),
            "主体不存在");
    }

    private ExternalPrincipal principal() {
        ExternalPrincipal principal = new ExternalPrincipal();
        principal.setId(901L);
        principal.setPrincipalCode("AGT_OPS");
        principal.setPrincipalType("AGENT");
        principal.setName("ops-agent");
        principal.setStatus("ACTIVE");
        return principal;
    }

    private Map<String, Object> profileRow() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("namespace", "octop-prod");
        row.put("agentCode", "agent-ops");
        row.put("status", "ACTIVE");
        return row;
    }

    private AgentMemoryScopeGrantRequest grant(String type, String code, boolean read, boolean write) {
        AgentMemoryScopeGrantRequest request = new AgentMemoryScopeGrantRequest();
        request.setScopeType(type);
        request.setScopeCode(code);
        request.setCanRead(Boolean.valueOf(read));
        request.setCanWrite(Boolean.valueOf(write));
        return request;
    }
}
