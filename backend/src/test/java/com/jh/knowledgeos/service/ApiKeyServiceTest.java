package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiKeyServiceTest {

    private final ExternalApiMapper mapper = mock(ExternalApiMapper.class);
    private final ApiKeyService service = new ApiKeyService(mapper, mock(IdGenerator.class));

    @Test
    void memoryPolicyCanDisableAndRestoreOnlyPreviouslyGrantedScopes() {
        ApiKey before = key("READ,MEMORY_READ,MEMORY_WRITE");
        ApiKey after = key("READ,MEMORY_READ,MEMORY_WRITE");
        after.setMemoryReadEnabled(Boolean.FALSE);
        after.setMemoryWriteEnabled(Boolean.TRUE);
        when(mapper.getApiKey(9L)).thenReturn(before, after);

        Map<String, Object> result = service.updateMemoryPolicy(9L, false, true);

        verify(mapper).updateApiKeyMemoryPolicy(9L, Boolean.FALSE, Boolean.TRUE);
        assertEquals(Boolean.FALSE, result.get("memoryReadEnabled"));
        assertEquals(Boolean.TRUE, result.get("memoryWriteEnabled"));
    }

    @Test
    void memoryPolicyCannotGrantScopesThatWereNotIssued() {
        when(mapper.getApiKey(9L)).thenReturn(key("READ"));

        assertThrows(IllegalArgumentException.class, () -> service.updateMemoryPolicy(9L, true, false));
        verify(mapper, never()).updateApiKeyMemoryPolicy(9L, Boolean.TRUE, Boolean.FALSE);
    }

    @Test
    void disabledMemoryScopeIsDeniedWithoutAffectingOtherGrantedCapabilities() {
        ApiKey key = key("READ,MEMORY_READ,MEMORY_WRITE");
        key.setMemoryReadEnabled(Boolean.FALSE);
        key.setMemoryWriteEnabled(Boolean.TRUE);

        assertFalse(key.hasScope(ApiKey.SCOPE_MEMORY_READ));
        assertTrue(key.hasScope(ApiKey.SCOPE_MEMORY_WRITE));
        assertTrue(key.hasScope(ApiKey.SCOPE_READ));
    }

    private ApiKey key(String scopes) {
        ApiKey key = new ApiKey();
        key.setId(9L);
        key.setKeyCode("AK_TEST");
        key.setName("test");
        key.setScopes(scopes);
        key.setStatus(ApiKey.STATUS_ACTIVE);
        return key;
    }
}
