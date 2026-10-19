package com.jh.knowledgeos.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentMemoryGovernanceServiceTest {
    private final AgentMemoryMapper mapper = mock(AgentMemoryMapper.class);
    private final ApiKeyService apiKeys = mock(ApiKeyService.class);
    private final AgentMemoryService memories = mock(AgentMemoryService.class);
    private final AgentMemoryGovernanceService service = new AgentMemoryGovernanceService(mapper, apiKeys, memories);

    @Test
    void suspensionHidesMemoryAndRecordsOperatorReason() {
        prepareMemory("ACTIVE");
        when(mapper.updateManagedStatus("shared-ns", "M1", "SUSPENDED", "ACTIVE")).thenReturn(1);

        Map<String, Object> result = service.setStatus(41L, "M1", "SUSPENDED", "内容需要复核", 7L);

        assertEquals("SUSPENDED", result.get("status"));
        verify(mapper).insertGovernanceEvent("M1", "shared-ns", "agent-a", "PROJECT", "p1",
            "SUSPEND", "ACTIVE", "SUSPENDED", "内容需要复核", 7L);
    }

    @Test
    void physicalForgetRequiresReason() {
        prepareMemory("ACTIVE");
        assertThrows(IllegalArgumentException.class, () -> service.forget(41L, "M1", " ", 7L));
    }

    private void prepareMemory(String status) {
        ApiKey key = new ApiKey(); key.setId(41L);
        when(apiKeys.get(41L)).thenReturn(key);
        when(memories.namespace(key)).thenReturn("shared-ns");
        Map<String, Object> memory = new LinkedHashMap<String, Object>();
        memory.put("memoryCode", "M1"); memory.put("agentCode", "agent-a");
        memory.put("scopeType", "PROJECT"); memory.put("scopeCode", "p1"); memory.put("status", status);
        when(mapper.getManaged("shared-ns", "M1")).thenReturn(memory);
    }
}
