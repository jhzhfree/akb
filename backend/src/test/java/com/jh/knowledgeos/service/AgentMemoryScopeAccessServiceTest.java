package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
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

        service.replace(41L, Collections.singletonList(grant("PROJECT", "release-2026", true, false)), 7L);

        verify(mapper).deleteScopeAccess(41L);
        verify(mapper).insertScopeAccess(41L, "octop-prod", "PROJECT", "release-2026", true, false, 7L);
    }

    @Test
    void cannotGrantReadBeyondTheKeyCapability() {
        ApiKey key = key("MEMORY_WRITE");
        when(apiKeys.get(41L)).thenReturn(key);

        assertThrows(IllegalArgumentException.class,
            () -> service.replace(41L, Collections.singletonList(grant("PROJECT", "p1", true, false)), 7L));
        verify(mapper, never()).deleteScopeAccess(41L);
    }

    private ApiKey key(String scopes) {
        ApiKey key = new ApiKey(); key.setId(41L); key.setScopes(scopes); return key;
    }

    private AgentMemoryScopeGrantRequest grant(String type, String code, boolean read, boolean write) {
        AgentMemoryScopeGrantRequest grant = new AgentMemoryScopeGrantRequest();
        grant.setScopeType(type); grant.setScopeCode(code);
        grant.setCanRead(Boolean.valueOf(read)); grant.setCanWrite(Boolean.valueOf(write));
        return grant;
    }
}
