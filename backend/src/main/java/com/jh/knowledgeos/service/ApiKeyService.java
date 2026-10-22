package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies external-service credentials.
 *
 * <p>Plaintext secrets are handed out exactly once; afterwards only the SHA-256 hash and a
 * short display prefix remain, so a leaked database dump cannot be replayed as a credential.</p>
 */
@Service
public class ApiKeyService {

    public static final String SECRET_PREFIX = "kos_ak_";

    private static final int SECRET_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExternalApiMapper mapper;

    private final IdGenerator ids;

    public ApiKeyService(ExternalApiMapper mapper, IdGenerator ids) {
        this.mapper = mapper;
        this.ids = ids;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (ApiKey key : mapper.listApiKeys()) {
            rows.add(profile(key));
        }
        return rows;
    }

    /**
     * Creates a credential and returns its profile including the one-time plaintext secret.
     */
    @Transactional
    public Map<String, Object> create(String name, Long knowledgeBaseId, List<String> scopes, Integer rateLimitPerMinute, LocalDateTime expiresAt,
        Long createdBy) {
        return create(name, knowledgeBaseId, scopes, rateLimitPerMinute, expiresAt, createdBy, null);
    }

    /**
     * Same as above but also binds the credential to the sites allowed to embed it.
     */
    @Transactional
    public Map<String, Object> create(String name, Long knowledgeBaseId, List<String> scopes, Integer rateLimitPerMinute, LocalDateTime expiresAt,
        Long createdBy, List<String> allowedOrigins) {
        return create(name, knowledgeBaseId, scopes, rateLimitPerMinute, expiresAt, createdBy, allowedOrigins, null, null);
    }

    @Transactional
    public Map<String, Object> create(String name, Long knowledgeBaseId, List<String> scopes, Integer rateLimitPerMinute, LocalDateTime expiresAt,
        Long createdBy, List<String> allowedOrigins, String memoryNamespace, String memoryAgentCode) {
        if (knowledgeBaseId != null && mapper.countKnowledgeBase(knowledgeBaseId) == 0) {
            throw new IllegalArgumentException("知识库不存在：" + knowledgeBaseId);
        }
        ExternalPrincipal principal = new ExternalPrincipal();
        principal.setPrincipalCode(ids.next("PR_"));
        principal.setName(name == null || name.trim().isEmpty() ? "未命名调用方" : name.trim());
        principal.setCreatedBy(createdBy);
        mapper.insertPrincipal(principal);
        String secret = newSecret();
        ApiKey key = new ApiKey();
        key.setKeyCode(ids.next("AK_"));
        key.setName(name == null || name.trim().isEmpty() ? "未命名调用方" : name.trim());
        key.setSecretHash(hash(secret));
        key.setKeyPrefix(secret.substring(0, SECRET_PREFIX.length() + 6));
        key.setKnowledgeBaseId(knowledgeBaseId);
        key.setPrincipalId(principal.getId());
        key.setScopes(normalizeScopes(scopes));
        key.setRateLimitPerMinute(rateLimitPerMinute == null || rateLimitPerMinute < 1 ? 60 : rateLimitPerMinute);
        key.setStatus(ApiKey.STATUS_ACTIVE);
        key.setExpiresAt(expiresAt);
        key.setCreatedBy(createdBy);
        key.setAllowedOrigins(normalizeOrigins(allowedOrigins));
        key.setMemoryNamespace(normalizeMemoryIdentity(memoryNamespace, "memoryNamespace"));
        key.setMemoryAgentCode(normalizeMemoryIdentity(memoryAgentCode, "memoryAgentCode"));
        mapper.insertApiKey(key);
        if (knowledgeBaseId != null) {
            mapper.insertPrincipalKnowledgeBaseGrant(principal.getId(), knowledgeBaseId, createdBy, "API Key 初始绑定知识库");
        }
        Map<String, Object> result = profile(mapper.getApiKey(key.getId()));
        // 明文只在此处出现一次，界面提示调用方立即保存。
        result.put("secret", secret);
        return result;
    }

    public Map<String, Object> revoke(Long id) {
        mapper.updateApiKeyStatus(id, ApiKey.STATUS_REVOKED);
        ApiKey key = mapper.getApiKey(id);
        return key == null ? null : profile(key);
    }

    /**
     * Turns existing memory capabilities off or on without changing the key's knowledge scopes.
     * A policy can disable a granted capability, but cannot grant a capability absent from scopes.
     */
    public Map<String, Object> updateMemoryPolicy(Long id, boolean readEnabled, boolean writeEnabled) {
        ApiKey key = mapper.getApiKey(id);
        if (key == null) {
            throw new IllegalArgumentException("API Key 不存在");
        }
        if (readEnabled && !key.scopeList().contains(ApiKey.SCOPE_MEMORY_READ)) {
            throw new IllegalArgumentException("该 API Key 未签发 MEMORY_READ scope，不能通过策略新增读取权限");
        }
        if (writeEnabled && !key.scopeList().contains(ApiKey.SCOPE_MEMORY_WRITE)) {
            throw new IllegalArgumentException("该 API Key 未签发 MEMORY_WRITE scope，不能通过策略新增写入权限");
        }
        mapper.updateApiKeyMemoryPolicy(id, Boolean.valueOf(readEnabled), Boolean.valueOf(writeEnabled));
        return profile(mapper.getApiKey(id));
    }

    /**
     * Resolves a presented secret to an active credential, or returns null.
     */
    public ApiKey authenticate(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return null;
        }
        ApiKey key = mapper.getApiKeyByHash(hash(secret.trim()));
        if (key == null || !key.isUsable()) {
            return null;
        }
        mapper.touchApiKey(key.getId());
        return key;
    }

    public ApiKey get(Long id) {
        return mapper.getApiKey(id);
    }

    public int countActive() {
        return mapper.countActiveApiKeys();
    }

    public Map<String, Object> profile(ApiKey key) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        if (key == null) {
            return row;
        }
        row.put("id", key.getId());
        row.put("keyCode", key.getKeyCode());
        row.put("name", key.getName());
        row.put("keyPrefix", key.getKeyPrefix());
        row.put("knowledgeBaseId", key.getKnowledgeBaseId());
        row.put("knowledgeBaseName", key.getKnowledgeBaseName());
        row.put("principalId", key.getPrincipalId());
        row.put("grantedKnowledgeBaseCount", key.getGrantedKnowledgeBaseCount() == null ? Integer.valueOf(0) : key.getGrantedKnowledgeBaseCount());
        row.put("scopes", key.getScopes());
        row.put("rateLimitPerMinute", key.getRateLimitPerMinute());
        row.put("status", key.getStatus());
        row.put("expiresAt", key.getExpiresAt());
        row.put("allowedOrigins", key.getAllowedOrigins());
        row.put("memoryNamespace", key.getMemoryNamespace());
        row.put("memoryAgentCode", key.getMemoryAgentCode());
        row.put("memoryReadEnabled", Boolean.valueOf(key.hasScope(ApiKey.SCOPE_MEMORY_READ)));
        row.put("memoryWriteEnabled", Boolean.valueOf(key.hasScope(ApiKey.SCOPE_MEMORY_WRITE)));
        row.put("lastUsedAt", key.getLastUsedAt());
        row.put("createdByName", key.getCreatedByName());
        row.put("createdTime", key.getCreatedTime());
        return row;
    }

    public List<Long> grantedKnowledgeBases(ApiKey key) {
        if (key == null || key.getPrincipalId() == null) {
            return new ArrayList<Long>();
        }
        return mapper.listPrincipalKnowledgeBaseGrants(key.getPrincipalId());
    }

    public boolean hasKnowledgeBaseGrant(ApiKey key, Long knowledgeBaseId) {
        return knowledgeBaseId != null && grantedKnowledgeBases(key).contains(knowledgeBaseId);
    }

    @Transactional
    public List<Map<String, Object>> replaceKnowledgeBaseGrants(Long keyId, List<Long> knowledgeBaseIds, Long operatorId) {
        ApiKey key = mapper.getApiKey(keyId);
        if (key == null) {
            throw new IllegalArgumentException("API Key 不存在");
        }
        if (key.getPrincipalId() == null) {
            throw new IllegalStateException("API Key 缺少主体，需先执行 V61 数据迁移");
        }
        Set<Long> uniqueIds = new LinkedHashSet<Long>();
        if (knowledgeBaseIds != null) {
            if (knowledgeBaseIds.size() > 100) {
                throw new IllegalArgumentException("单个主体最多授权 100 个知识库");
            }
            for (Long knowledgeBaseId : knowledgeBaseIds) {
                if (knowledgeBaseId == null || knowledgeBaseId.longValue() <= 0) {
                    throw new IllegalArgumentException("knowledgeBaseIds 只接受正整数");
                }
                if (mapper.countKnowledgeBase(knowledgeBaseId) == 0) {
                    throw new IllegalArgumentException("知识库不存在：" + knowledgeBaseId);
                }
                uniqueIds.add(knowledgeBaseId);
            }
        }
        for (Long oldGrant : mapper.listPrincipalKnowledgeBaseGrants(key.getPrincipalId())) {
            mapper.insertPrincipalKnowledgeBaseGrantAudit(key.getPrincipalId(), oldGrant, "REVOKE", operatorId, "控制台替换授权");
        }
        mapper.deletePrincipalKnowledgeBaseGrants(key.getPrincipalId());
        for (Long knowledgeBaseId : uniqueIds) {
            mapper.insertPrincipalKnowledgeBaseGrant(key.getPrincipalId(), knowledgeBaseId, operatorId, "控制台授权");
            mapper.insertPrincipalKnowledgeBaseGrantAudit(key.getPrincipalId(), knowledgeBaseId, "GRANT", operatorId, "控制台授权");
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Long id : grantedKnowledgeBases(key)) {
            Map<String, Object> grant = new LinkedHashMap<String, Object>();
            grant.put("knowledgeBaseId", id);
            result.add(grant);
        }
        return result;
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeScopes(List<String> scopes) {
        List<String> cleaned = new ArrayList<String>();
        if (scopes != null) {
            for (String scope : scopes) {
                String value = String.valueOf(scope == null ? "" : scope).trim().toUpperCase(Locale.ROOT);
                if (value.isEmpty()) {
                    continue;
                }
                boolean supported = ApiKey.SCOPE_READ.equals(value) || ApiKey.SCOPE_LIST.equals(value) || ApiKey.SCOPE_QUERY.equals(value)
                    || ApiKey.SCOPE_SEARCH.equals(value) || ApiKey.SCOPE_READ_OBJECT.equals(value)
                    || ApiKey.SCOPE_WRITE.equals(value) || ApiKey.SCOPE_INGEST.equals(value)
                    || ApiKey.SCOPE_MEMORY_READ.equals(value) || ApiKey.SCOPE_MEMORY_WRITE.equals(value);
                if (!supported) {
                    throw new IllegalArgumentException("不支持的权限范围：" + value);
                }
                if (!cleaned.contains(value)) {
                    cleaned.add(value);
                }
            }
        }
        if (cleaned.isEmpty()) {
            cleaned.add(ApiKey.SCOPE_READ);
        }
        return String.join(",", cleaned);
    }

    private String normalizeMemoryIdentity(String raw, String field) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > 64 || !value.matches("[A-Za-z0-9_.:-]+")) {
            throw new IllegalArgumentException(field + " 仅允许 1-64 位字母、数字、点、下划线、冒号或连字符");
        }
        if ("memoryNamespace".equals(field) && value.startsWith("key:")) {
            throw new IllegalArgumentException("memoryNamespace 不得使用系统保留的 key: 前缀");
        }
        return value;
    }

    /**
     * Origins are stored trimmed of any trailing slash so the check stays a plain equality:
     * browsers always send a bare origin, and a substring match would let https://evil.com
     * through just because it ends with a configured host name.
     */
    private String normalizeOrigins(List<String> origins) {
        List<String> cleaned = new ArrayList<String>();
        if (origins != null) {
            for (String origin : origins) {
                String value = String.valueOf(origin == null ? "" : origin).trim();
                while (value.endsWith("/")) {
                    value = value.substring(0, value.length() - 1);
                }
                if (value.isEmpty() || value.length() > 300 || cleaned.contains(value)) {
                    continue;
                }
                cleaned.add(value);
                if (cleaned.size() >= 20) {
                    break;
                }
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    /**
     * Whether an origin is permitted for this credential; an unrestricted key accepts anything.
     */
    public boolean allowsOrigin(ApiKey key, String origin) {
        if (key == null || key.getAllowedOrigins() == null || key.getAllowedOrigins().trim().isEmpty()) {
            return true;
        }
        if (origin == null || origin.trim().isEmpty()) {
            return false;
        }
        String presented = origin.trim();
        for (String allowed : key.getAllowedOrigins().split(",")) {
            if (allowed.trim().equalsIgnoreCase(presented)) {
                return true;
            }
        }
        return false;
    }

    /**
     * API Key 是高熵随机串，SHA-256 查表即可，无需慢哈希。
     */
    private String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
