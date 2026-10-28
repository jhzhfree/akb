package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.dto.MemoryCaptureRequest;
import com.jh.knowledgeos.dto.MemoryRecallRequest;
import com.jh.knowledgeos.mapper.AgentMemoryMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** Platform memory engine shared by the REST and MCP integration surfaces. */
@Service
public class AgentMemoryService {

    private final AgentMemoryMapper mapper;
    private final ExternalAccessService access;
    private final ObjectMapper objectMapper;

    private final boolean enabled;

    @Autowired
    public AgentMemoryService(AgentMemoryMapper mapper, ExternalAccessService access, ObjectMapper objectMapper, KnowledgeProperties properties) {
        this.mapper = mapper;
        this.access = access;
        this.objectMapper = objectMapper;
        this.enabled = properties.external().isAgentMemoryEnabled();
    }

    /** Compatibility constructor for focused unit tests; production uses the configured constructor. */
    public AgentMemoryService(AgentMemoryMapper mapper, ExternalAccessService access, ObjectMapper objectMapper) {
        this(mapper, access, objectMapper, new KnowledgeProperties());
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void assertEnabled() {
        if (!enabled) {
            throw new SecurityException("Agent 记忆服务已关闭");
        }
    }

    @Transactional
    public Map<String, Object> capture(ApiKey key, MemoryCaptureRequest request) {
        assertEnabled();
        access.assertScope(key, ApiKey.SCOPE_MEMORY_WRITE);
        String namespace = namespace(key);
        String agent = agentCode(key);
        String scopeType = normalize(request.getScopeType());
        validateScopeType(scopeType);
        String scopeCode = resolveScopeCode(scopeType, request.getScopeCode(), agent);
        return insert(namespace, agent, scopeType, scopeCode, request, key);
    }

    /**
     * 按身份写入记忆，不经过 API Key：控制台用户对自己答案的纠正，本来就属于他自己的记忆，
     * 不需要"某个 Key 有没有 MEMORY_WRITE"这道检查——那是给外部调用方准备的。
     *
     * <p>只写 AGENT 作用域：用户反馈是个人的，不该被存进共享空间（那需要额外授权，
     * 而"提交个反馈"里没有给用户选择共享空间的余地）。</p>
     */
    public Map<String, Object> captureForIdentity(String namespace, String agent, MemoryCaptureRequest request) {
        assertEnabled();
        // 传 null 凭证：没有 Key 就没有"共享作用域授权"这回事，而这里只允许 AGENT，本来就不需要。
        return insert(namespace, agent, "AGENT", agent, request, null);
    }

    private Map<String, Object> insert(String namespace, String agent, String scopeType, String scopeCode,
        MemoryCaptureRequest request, ApiKey key) {
        String level = normalize(request.getMemoryLevel());
        validateLevel(level, scopeType);
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty() || content.length() > 4000) {
            throw new IllegalArgumentException("记忆内容不能为空且最多 4000 字符");
        }
        // 顺序不能动：作用域与级别本身不合法时（比如 L3 写进 PROJECT），要先报参数错，
        // 而不是等走到共享授权那里报"未获准访问"——后者会把真正的参数问题盖掉。
        if (key != null) {
            requireSharedScopeAccess(key, namespace, scopeType, scopeCode, "WRITE");
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
        String payloadFingerprint = payloadFingerprint(namespace, agent, scopeType, scopeCode, level, content,
            tags, sourceType, sourceRef, confidence, importance, ttl);
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(ttl);
        String memoryCode = UUID.randomUUID().toString();
        try {
            mapper.insert(memoryCode, namespace, agent, scopeType, scopeCode, level, content,
                objectMapper.writeValueAsString(tags), sourceType, sourceRef, confidence, importance, expiresAt,
                key == null ? null : key.getId(), idempotencyKey, payloadFingerprint);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记忆标签序列化失败", e);
        }
        Object storedExpiresAt = null;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accepted", Boolean.TRUE);
        if (idempotencyKey != null) {
            Map<String, Object> stored = mapper.findByIdempotency(namespace, agent, idempotencyKey);
            if (stored != null) {
                String storedFingerprint = stored.get("payloadFingerprint") == null ? null
                    : String.valueOf(stored.get("payloadFingerprint"));
                boolean payloadMismatch = storedFingerprint != null
                    ? !payloadFingerprint.equals(storedFingerprint)
                    : !scopeType.equals(String.valueOf(stored.get("scopeType")))
                        || !scopeCode.equals(String.valueOf(stored.get("scopeCode")))
                        || !level.equals(String.valueOf(stored.get("memoryLevel")))
                        || !content.equals(String.valueOf(stored.get("content")));
                if (payloadMismatch) {
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

    private String payloadFingerprint(String namespace, String agent, String scopeType, String scopeCode, String level,
        String content, List<String> tags, String sourceType, String sourceRef, Double confidence, Integer importance, int ttl) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("namespace", namespace);
        payload.put("agent", agent);
        payload.put("scopeType", scopeType);
        payload.put("scopeCode", scopeCode);
        payload.put("level", level);
        payload.put("content", content);
        payload.put("tags", tags);
        payload.put("sourceType", sourceType);
        payload.put("sourceRef", sourceRef);
        payload.put("confidence", confidence);
        payload.put("importance", importance);
        payload.put("ttlDays", Integer.valueOf(ttl));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(payload));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", Integer.valueOf(value & 0xff)));
            }
            return hex.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("记忆幂等指纹计算失败", e);
        }
    }

    public Map<String, Object> recall(ApiKey key, MemoryRecallRequest request) {
        assertEnabled();
        access.assertScope(key, ApiKey.SCOPE_MEMORY_READ);
        String namespace = namespace(key);
        String agent = agentCode(key);
        String scopeType = request.getScopeType() == null || request.getScopeType().trim().isEmpty()
            ? "AGENT" : normalize(request.getScopeType());
        validateScopeType(scopeType);
        String scopeCode = resolveScopeCode(scopeType, request.getScopeCode(), agent);
        requireSharedScopeAccess(key, namespace, scopeType, scopeCode, "READ");
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

    /**
     * 按身份直接召回，供问答链路注入记忆用（控制台与嵌入挂件都没有 API Key）。
     *
     * <p>{@link #recall(ApiKey, MemoryRecallRequest)} 那些校验回答的是"这个 Key 能不能读"，
     * 这里没有 Key：授权已由调用方保证——控制台有登录态，嵌入令牌在网关处已换成它背后的 Key。
     * 只取 AGENT 作用域，也就是这个身份自己的记忆。</p>
     *
     * <p>召回先用 MySQL ngram 全文相关度匹配正文，并兼容短语和标签匹配。整句问题仍可能
     * 因措辞差异命中不到，所以分两步：先按问题检索，一个都没命中就退回"最近的若干条"。
     * 后者是有意的——宁可让模型看到几条可能相关的记忆自己判断，也不要因为措辞不同就
     * 一条都不给，那样"开了记忆"和没开看起来一样。</p>
     */
    public List<Map<String, Object>> recallByIdentity(String namespace, String agent, String query, int limit) {
        assertEnabled();
        String keyword = query == null ? "" : query.trim();
        if (keyword.length() > 300) {
            keyword = keyword.substring(0, 300);
        }
        int capped = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = mapper.recall(namespace, agent, "AGENT", agent, keyword,
            Integer.valueOf(capped));
        if ((rows == null || rows.isEmpty()) && !keyword.isEmpty()) {
            rows = mapper.recall(namespace, agent, "AGENT", agent, "", Integer.valueOf(capped));
        }
        return rows == null ? Collections.<Map<String, Object>>emptyList() : rows;
    }

    /**
     * 把记忆渲染成喂给模型的文本。
     *
     * <p>单条截断：一条写坏的巨长记忆不该把整个上下文预算吃掉，那样答案反而丢了证据。</p>
     */
    public String renderContext(List<Map<String, Object>> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> memory : memories) {
            Object raw = memory == null ? null : memory.get("content");
            String content = raw == null ? "" : String.valueOf(raw).trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > 500) {
                content = content.substring(0, 500) + "…";
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("- ").append(content);
        }
        return text.toString();
    }

    @Transactional
    public Map<String, Object> forget(ApiKey key, String memoryCode) {
        assertEnabled();
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
        requireSharedScopeAccess(key, namespace, String.valueOf(existing.get("scopeType")),
            String.valueOf(existing.get("scopeCode")), "WRITE");
        int changed = mapper.forget(namespace, memoryCode.trim(), agent);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("forgotten", Boolean.valueOf(changed > 0));
        result.put("memoryCode", memoryCode.trim());
        return result;
    }

    private void requireSharedScopeAccess(ApiKey key, String namespace, String scopeType, String scopeCode, String accessMode) {
        boolean agentGrant = key.getPrincipalId() != null
            && mapper.hasPrincipalScopeAccess(key.getPrincipalId(), namespace, scopeType, scopeCode, accessMode);
        // Transition fallback for grants that were not safe to merge because sibling keys had
        // different scope grants. New and migrated common grants are principal-owned.
        boolean legacyKeyGrant = !agentGrant && key.getDelegatedPrincipalId() == null
            && mapper.hasScopeAccess(key.getId(), namespace, scopeType, scopeCode, accessMode);
        if (!agentGrant && !legacyKeyGrant) {
            throw new SecurityException("当前 Agent 主体未获准访问该记忆作用域");
        }
        if (key.getDelegatedPrincipalId() != null
            && !mapper.hasPrincipalScopeAccess(key.getDelegatedPrincipalId(), namespace, scopeType, scopeCode, accessMode)) {
            throw new SecurityException("委托用户主体未获准访问该记忆作用域");
        }
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
