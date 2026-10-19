package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operator controls for reviewing, suspending, restoring, and forgetting namespace memories. */
@Service
public class AgentMemoryGovernanceService {
    private final AgentMemoryMapper mapper;
    private final ApiKeyService apiKeyService;
    private final AgentMemoryService memoryService;

    public AgentMemoryGovernanceService(AgentMemoryMapper mapper, ApiKeyService apiKeyService, AgentMemoryService memoryService) {
        this.mapper = mapper;
        this.apiKeyService = apiKeyService;
        this.memoryService = memoryService;
    }

    public Map<String, Object> list(Long keyId, String status, String scopeType, String scopeCode, Integer limit, Integer offset) {
        ApiKey key = requireKey(keyId);
        String normalizedStatus = normalizeFilter(status, "status", "ACTIVE", "SUSPENDED");
        String normalizedType = normalizeFilter(scopeType, "scopeType", "AGENT", "TEAM", "PROJECT", "TASK", "WORKFLOW", "SESSION");
        String code = trimToNull(scopeCode);
        if (code != null && (code.length() > 128 || !code.matches("[A-Za-z0-9_.:-]+"))) {
            throw new IllegalArgumentException("scopeCode 格式不合法");
        }
        int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit.intValue(), 200));
        int pageOffset = offset == null ? 0 : Math.max(0, Math.min(offset.intValue(), 1000000));
        List<Map<String, Object>> items = mapper.listManaged(memoryService.namespace(key), normalizedStatus, normalizedType, code, pageSize, pageOffset);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("namespace", memoryService.namespace(key));
        result.put("limit", Integer.valueOf(pageSize));
        result.put("offset", Integer.valueOf(pageOffset));
        result.put("items", items);
        return result;
    }

    public Map<String, Object> events(Long keyId, Integer limit, Integer offset) {
        ApiKey key = requireKey(keyId);
        int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit.intValue(), 200));
        int pageOffset = offset == null ? 0 : Math.max(0, Math.min(offset.intValue(), 1000000));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("limit", Integer.valueOf(pageSize));
        result.put("offset", Integer.valueOf(pageOffset));
        result.put("items", mapper.listGovernanceEvents(memoryService.namespace(key), pageSize, pageOffset));
        return result;
    }

    @Transactional
    public Map<String, Object> setStatus(Long keyId, String memoryCode, String status, String reason, Long operatorId) {
        ApiKey key = requireKey(keyId);
        String target = normalizeFilter(status, "status", "ACTIVE", "SUSPENDED");
        if (target == null) throw new IllegalArgumentException("status 不能为空");
        String cleanReason = requireReason(reason);
        String namespace = memoryService.namespace(key);
        String code = normalizeMemoryCode(memoryCode);
        Map<String, Object> memory = get(namespace, code);
        String current = String.valueOf(memory.get("status"));
        if (!"ACTIVE".equals(current) && !"SUSPENDED".equals(current)) {
            throw new IllegalStateException("当前记忆状态不可由该操作修改");
        }
        if (target.equals(current)) return statusResult(code, target, false);
        int changed = mapper.updateManagedStatus(namespace, code, target, current);
        if (changed != 1) throw new IllegalStateException("记忆状态已被其他操作修改，请刷新后重试");
        mapper.insertGovernanceEvent(code, namespace, String.valueOf(memory.get("agentCode")),
            String.valueOf(memory.get("scopeType")), String.valueOf(memory.get("scopeCode")),
            "SUSPENDED".equals(target) ? "SUSPEND" : "RESTORE", current, target, cleanReason, operatorId);
        return statusResult(code, target, true);
    }

    @Transactional
    public Map<String, Object> forget(Long keyId, String memoryCode, String reason, Long operatorId) {
        ApiKey key = requireKey(keyId);
        String cleanReason = requireReason(reason);
        String namespace = memoryService.namespace(key);
        String code = normalizeMemoryCode(memoryCode);
        Map<String, Object> memory = get(namespace, code);
        mapper.insertGovernanceEvent(code, namespace, String.valueOf(memory.get("agentCode")),
            String.valueOf(memory.get("scopeType")), String.valueOf(memory.get("scopeCode")),
            "FORGET", String.valueOf(memory.get("status")), null, cleanReason, operatorId);
        if (mapper.deleteManaged(namespace, code) != 1) {
            throw new IllegalStateException("记忆已被其他操作删除，请刷新后重试");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("forgotten", Boolean.TRUE);
        result.put("memoryCode", code);
        return result;
    }

    private ApiKey requireKey(Long keyId) {
        ApiKey key = keyId == null ? null : apiKeyService.get(keyId);
        if (key == null) throw new IllegalArgumentException("API Key 不存在");
        return key;
    }

    private Map<String, Object> get(String namespace, String memoryCode) {
        String code = normalizeMemoryCode(memoryCode);
        Map<String, Object> memory = mapper.getManaged(namespace, code);
        if (memory == null) throw new IllegalArgumentException("记忆不存在或不属于该 API Key 的 namespace");
        return memory;
    }

    private String normalizeMemoryCode(String memoryCode) {
        if (memoryCode == null || memoryCode.trim().isEmpty() || memoryCode.trim().length() > 64) {
            throw new IllegalArgumentException("memoryCode 格式不合法");
        }
        return memoryCode.trim();
    }

    private String normalizeFilter(String value, String field, String... allowed) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        for (String item : allowed) if (item.equals(normalized)) return normalized;
        throw new IllegalArgumentException(field + " 不合法");
    }

    private String requireReason(String value) {
        String reason = trimToNull(value);
        if (reason == null || reason.length() > 500) throw new IllegalArgumentException("操作原因不能为空且最多 500 字符");
        return reason;
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private Map<String, Object> statusResult(String code, String status, boolean changed) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("memoryCode", code); result.put("status", status); result.put("changed", Boolean.valueOf(changed));
        return result;
    }
}
