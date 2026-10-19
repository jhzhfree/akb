package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.dto.MemoryCaptureRequest;
import com.jh.knowledgeos.dto.MemoryRecallRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform memory engine shared by the REST and MCP integration surfaces. */
@Service
public class AgentMemoryService {

    private final AgentMemoryMapper mapper;
    private final ExternalAccessService access;
    private final ObjectMapper objectMapper;

    public AgentMemoryService(AgentMemoryMapper mapper, ExternalAccessService access, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.access = access;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> capture(ApiKey key, MemoryCaptureRequest request) {
        access.assertScope(key, ApiKey.SCOPE_MEMORY_WRITE);
        String namespace = namespace(key);
        String agent = agentCode(key);
        String scopeType = normalize(request.getScopeType());
        validateScopeType(scopeType);
        String scopeCode = resolveScopeCode(scopeType, request.getScopeCode(), agent);
        String level = normalize(request.getMemoryLevel());
        validateLevel(level, scopeType);
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty() || content.length() > 4000) {
            throw new IllegalArgumentException("记忆内容不能为空且最多 4000 字符");
        }
        List<String> tags = normalizeTags(request.getTags());
        Double confidence = request.getConfidence() == null ? Double.valueOf(0.7) : request.getConfidence();
        Integer importance = request.getImportance() == null ? Integer.valueOf(3) : request.getImportance();
        int ttl = request.getExpiresInDays() == null ? defaultTtl(level) : request.getExpiresInDays().intValue();
        if (Double.isNaN(confidence.doubleValue()) || confidence.doubleValue() < 0.0d || confidence.doubleValue() > 1.0d) {
            throw new IllegalArgumentException("confidence 必须在 0~1 之间");
        }
        if (importance.intValue() < 1 || importance.intValue() > 5) {
            throw new IllegalArgumentException("importance 必须在 1~5 之间");
        }
        if (ttl < 1 || ttl > 3650) {
            throw new IllegalArgumentException("expiresInDays 必须在 1~3650 天之间");
        }
        if ("L0".equals(level) && ttl > 30) {
            throw new IllegalArgumentException("L0 会话记忆有效期最长为 30 天");
        }
        String sourceType = trimToNull(request.getSourceType());
        if (sourceType != null) {
            sourceType = normalize(sourceType);
            if (!("TASK_RESULT".equals(sourceType) || "USER_CONFIRMED".equals(sourceType) || "WORKFLOW".equals(sourceType)
                || "MANUAL".equals(sourceType) || "OTHER".equals(sourceType))) {
                throw new IllegalArgumentException("sourceType 不合法");
            }
        }
        String sourceRef = trimToNull(request.getSourceRef());
        if (sourceRef != null && sourceRef.length() > 128) {
            throw new IllegalArgumentException("sourceRef 最多 128 字符");
        }
        if (("TASK_RESULT".equals(sourceType) || "WORKFLOW".equals(sourceType)) && sourceRef == null) {
            throw new IllegalArgumentException("TASK_RESULT / WORKFLOW 记忆必须填写 sourceRef");
        }
        if ("L3".equals(level) && !("USER_CONFIRMED".equals(sourceType) || "MANUAL".equals(sourceType))) {
            throw new IllegalArgumentException("L3 Agent 画像只能由用户确认或管理员手动录入");
        }
        String idempotencyKey = trimToNull(request.getIdempotencyKey());
        if (idempotencyKey != null && !idempotencyKey.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("idempotencyKey 格式不合法");
        }
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(ttl);
        String memoryCode = UUID.randomUUID().toString();
        try {
            mapper.insert(memoryCode, namespace, agent, scopeType, scopeCode, level, content,
                objectMapper.writeValueAsString(tags), sourceType, sourceRef, confidence, importance, expiresAt, key.getId(), idempotencyKey);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记忆标签序列化失败", e);
        }
        Object storedExpiresAt = null;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accepted", Boolean.TRUE);
        if (idempotencyKey != null) {
            Map<String, Object> stored = mapper.findByIdempotency(namespace, agent, idempotencyKey);
            if (stored != null) {
                if (!scopeType.equals(String.valueOf(stored.get("scopeType")))
                    || !scopeCode.equals(String.valueOf(stored.get("scopeCode")))
                    || !level.equals(String.valueOf(stored.get("memoryLevel")))
                    || !content.equals(String.valueOf(stored.get("content")))) {
                    throw new IllegalArgumentException("idempotencyKey 已被不同记忆内容使用");
                }
                memoryCode = String.valueOf(stored.get("memoryCode"));
                storedExpiresAt = stored.get("expiresAt");
            }
        }
        result.put("memoryCode", memoryCode);
        result.put("scopeType", scopeType);
        result.put("scopeCode", scopeCode);
        result.put("memoryLevel", level);
        result.put("expiresAt", storedExpiresAt == null ? expiresAt : storedExpiresAt);
        return result;
    }

    public Map<String, Object> recall(ApiKey key, MemoryRecallRequest request) {
        access.assertScope(key, ApiKey.SCOPE_MEMORY_READ);
        String namespace = namespace(key);
        String agent = agentCode(key);
        String scopeType = request.getScopeType() == null || request.getScopeType().trim().isEmpty()
            ? "AGENT" : normalize(request.getScopeType());
        validateScopeType(scopeType);
        String scopeCode = resolveScopeCode(scopeType, request.getScopeCode(), agent);
        String query = request.getQuery() == null ? "" : request.getQuery().trim();
        if (query.isEmpty() || query.length() > 300) {
            throw new IllegalArgumentException("召回关键词不能为空且最多 300 字符");
        }
        int limit = request.getLimit() == null ? 10 : Math.max(1, Math.min(request.getLimit().intValue(), 50));
        List<Map<String, Object>> memories = mapper.recall(namespace, agent, scopeType, scopeCode, query, Integer.valueOf(limit));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("query", query);
        result.put("scopeType", scopeType);
        result.put("scopeCode", scopeCode);
        result.put("items", memories);
        result.put("count", memories == null ? 0 : memories.size());
        return result;
    }

    @Transactional
    public Map<String, Object> forget(ApiKey key, String memoryCode) {
        access.assertScope(key, ApiKey.SCOPE_MEMORY_WRITE);
        if (memoryCode == null || memoryCode.trim().isEmpty()) {
            throw new IllegalArgumentException("memoryCode 不能为空");
        }
        if (memoryCode.trim().length() > 64) {
            throw new IllegalArgumentException("memoryCode 最多 64 字符");
        }
        String namespace = namespace(key);
        String agent = agentCode(key);
        Map<String, Object> existing = mapper.get(namespace, memoryCode.trim());
        if (existing == null) {
            throw new IllegalArgumentException("记忆不存在或不在当前命名空间");
        }
        if ("AGENT".equals(String.valueOf(existing.get("scopeType"))) && !agent.equals(String.valueOf(existing.get("agentCode")))) {
            throw new SecurityException("只能删除当前 Agent 自己的私有记忆");
        }
        int changed = mapper.forget(namespace, memoryCode.trim(), agent);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("forgotten", Boolean.valueOf(changed > 0));
        result.put("memoryCode", memoryCode.trim());
        return result;
    }

    public String namespace(ApiKey key) {
        String configured = trimToNull(key.getMemoryNamespace());
        return configured == null ? "key:" + key.getId() : configured;
    }

    public String agentCode(ApiKey key) {
        String configured = trimToNull(key.getMemoryAgentCode());
        return configured == null ? key.getKeyCode() : configured;
    }

    private String resolveScopeCode(String scopeType, String requested, String agent) {
        if ("AGENT".equals(scopeType)) {
            return agent;
        }
        String value = trimToNull(requested);
        if (value == null) {
            throw new IllegalArgumentException("非 AGENT 作用域记忆必须提供 scopeCode");
        }
        if (value.length() > 128 || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException("scopeCode 仅允许 1-128 位字母、数字、点、下划线、冒号或连字符");
        }
        return value;
    }

    private void validateLevel(String level, String scopeType) {
        if (!("L0".equals(level) || "L1".equals(level) || "L2".equals(level) || "L3".equals(level))) {
            throw new IllegalArgumentException("记忆层级只能为 L0 / L1 / L2 / L3");
        }
        if ("L0".equals(level) && !"SESSION".equals(scopeType)) {
            throw new IllegalArgumentException("L0 会话片段只能保存在 SESSION 作用域");
        }
        if ("SESSION".equals(scopeType) && !"L0".equals(level)) {
            throw new IllegalArgumentException("SESSION 作用域仅用于短期 L0 会话记忆");
        }
        if ("L3".equals(level) && !"AGENT".equals(scopeType)) {
            throw new IllegalArgumentException("L3 稳定画像只能保存在 AGENT 作用域");
        }
        if ("L2".equals(level) && "AGENT".equals(scopeType)) {
            throw new IllegalArgumentException("L2 场景记忆必须属于 TEAM / PROJECT / TASK / WORKFLOW 作用域");
        }
    }

    private void validateScopeType(String scopeType) {
        if (!("AGENT".equals(scopeType) || "TEAM".equals(scopeType) || "PROJECT".equals(scopeType)
            || "TASK".equals(scopeType) || "WORKFLOW".equals(scopeType) || "SESSION".equals(scopeType))) {
            throw new IllegalArgumentException("scopeType 不合法");
        }
    }

    private List<String> normalizeTags(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (values != null) {
            for (String raw : values) {
                String value = trimToNull(raw);
                if (value != null && value.length() <= 64 && unique.size() < 20) {
                    unique.add(value);
                }
            }
        }
        return new ArrayList<String>(unique);
    }

    private int defaultTtl(String level) {
        if ("L0".equals(level)) { return 7; }
        if ("L3".equals(level)) { return 365; }
        if ("L2".equals(level)) { return 180; }
        return 30;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
