package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.dto.AgentMemoryScopeGrantRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class AgentMemoryScopeAccessServiceTest {
    private final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    private final ApiKeyService apiKeys = mock(ApiKeyService.class);
    private final AgentMemoryService memories = mock(AgentMemoryService.class);
    private final AgentMemoryScopeAccessService service = new AgentMemoryScopeAccessService(mapper, apiKeys, memories);

    @Test
    void replacementStoresExactScopeGrantForKeyNamespace() {
        ApiKey key = key("MEMORY_READ,MEMORY_WRITE");
        when(apiKeys.get(41L)).thenReturn(key);
        when(memories.namespace(key)).thenReturn("octop-prod");
        when(memories.agentCode(key)).thenReturn("agent-ops");

        service.replace(41L, Collections.singletonList(grant("PROJECT", "release-2026", true, false)), 7L);

        // 主体化后授权落在 principal 上；主体自身的 AGENT 授权不在清除范围内。
        verify(mapper).deletePrincipalScopeAccess(901L, "agent-ops");
        verify(mapper).insertPrincipalScopeAccess(901L, "octop-prod", "PROJECT", "release-2026", true, false, 7L);
    }

    @Test
    void cannotGrantReadBeyondTheKeyCapability() {
        ApiKey key = key("MEMORY_WRITE");
        when(apiKeys.get(41L)).thenReturn(key);

        assertThrows(IllegalArgumentException.class,
            () -> service.replace(41L, Collections.singletonList(grant("PROJECT", "p1", true, false)), 7L));
        verify(mapper, never()).deletePrincipalScopeAccess(any(Long.class), any(String.class));
    }

    /** 主体化后每条授权都挂在 principal 上，未绑定主体的 Key 不允许配置授权。 */
    private ApiKey key(String scopes) {
        ApiKey key = new ApiKey(); key.setId(41L); key.setScopes(scopes); key.setPrincipalId(901L); return key;
    }

    private AgentMemoryScopeGrantRequest grant(String type, String code, boolean read, boolean write) {
        AgentMemoryScopeGrantRequest grant = new AgentMemoryScopeGrantRequest();
        grant.setScopeType(type); grant.setScopeCode(code);
        grant.setCanRead(Boolean.valueOf(read)); grant.setCanWrite(Boolean.valueOf(write));
        return grant;
    }
}
