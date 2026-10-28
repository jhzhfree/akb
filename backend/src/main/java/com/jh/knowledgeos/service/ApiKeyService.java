package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.dto.ExternalKnowledgeBaseGrantRequest;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import com.jh.knowledgeos.mapper.AclPersistenceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final int PAT_MAX_LIFETIME_DAYS = 365;

    private static final List<String> PAT_DEFAULT_SCOPES = java.util.Arrays.asList(ApiKey.SCOPE_SEARCH, ApiKey.SCOPE_QUERY,
        ApiKey.SCOPE_LIST, ApiKey.SCOPE_READ_OBJECT);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExternalApiMapper mapper;

    private final IdGenerator ids;

    private final ObjectMapper objectMapper;

    private final KnowledgeBaseAccessService knowledgeBaseAccess;

    private final AclPersistenceMapper aclMapper;

    public ApiKeyService(ExternalApiMapper mapper, IdGenerator ids) {
        this(mapper, ids, new ObjectMapper(), null, null);
    }

    public ApiKeyService(ExternalApiMapper mapper, IdGenerator ids, ObjectMapper objectMapper) {
        this(mapper, ids, objectMapper, null, null);
    }

    /**
     * KnowledgeMapper 聚合继承了 AclPersistenceMapper，于是 knowledgeMapper 也是这个类型的
     * 候选 bean。这里要的是带自己 XML 命名空间的 aclPersistenceMapper，按名指定。
     */
    @Autowired
    public ApiKeyService(ExternalApiMapper mapper, IdGenerator ids, ObjectMapper objectMapper,
        KnowledgeBaseAccessService knowledgeBaseAccess,
        @Qualifier("aclPersistenceMapper") AclPersistenceMapper aclMapper) {
        this.mapper = mapper;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.knowledgeBaseAccess = knowledgeBaseAccess;
        this.aclMapper = aclMapper;
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
        principal.setPrincipalType("APPLICATION");
        principal.setName(name == null || name.trim().isEmpty() ? "未命名调用方" : name.trim());
        principal.setCreatedBy(createdBy);
        mapper.insertPrincipal(principal);
        String principalNamespace = normalizeMemoryIdentity(memoryNamespace, "memoryNamespace");
        String principalAgentCode = normalizeMemoryIdentity(memoryAgentCode, "memoryAgentCode");
        String effectiveNamespace = principalNamespace == null ? "principal:" + principal.getId() : principalNamespace;
        String effectiveAgent = principalAgentCode == null ? principal.getPrincipalCode() : principalAgentCode;
        mapper.upsertPrincipalMemoryProfile(principal.getId(), effectiveNamespace, effectiveAgent, createdBy);
        mapper.insertDefaultAgentMemoryGrant(principal.getId(), effectiveNamespace, effectiveAgent, createdBy);
        String secret = newSecret();
        ApiKey key = new ApiKey();
        key.setKeyCode(ids.next("AK_"));
        key.setName(name == null || name.trim().isEmpty() ? "未命名调用方" : name.trim());
        key.setSecretHash(hash(secret));
        key.setKeyPrefix(secret.substring(0, SECRET_PREFIX.length() + 6));
        key.setKnowledgeBaseId(knowledgeBaseId);
        key.setPrincipalId(principal.getId());
        key.setCredentialType(ApiKey.CREDENTIAL_SERVICE_KEY);
        key.setScopes(normalizeScopes(scopes));
        key.setRateLimitPerMinute(rateLimitPerMinute == null || rateLimitPerMinute < 1 ? 60 : rateLimitPerMinute);
        key.setStatus(ApiKey.STATUS_ACTIVE);
        key.setExpiresAt(expiresAt);
        key.setCreatedBy(createdBy);
        key.setAllowedOrigins(normalizeOrigins(allowedOrigins));
        // Stable Memory identity is persisted on the Principal profile, never on a new key.
        mapper.insertApiKey(key);
        if (knowledgeBaseId != null) {
            mapper.insertPrincipalKnowledgeBaseGrant(principal.getId(), knowledgeBaseId, createdBy, "API Key 初始绑定知识库");
        }
        Map<String, Object> result = profile(mapper.getApiKey(key.getId()));
        // 明文只在此处出现一次，界面提示调用方立即保存。
        result.put("secret", secret);
        return result;
    }

    /** Creates an expiring personal token owned by the current AKB user. */
    @Transactional
    public Map<String, Object> createPersonalAccessToken(Long userId, String name, List<String> requestedScopes, LocalDateTime expiresAt) {
        if (userId == null) throw new IllegalArgumentException("当前用户无效");
        User owner = mapper.getUserById(userId);
        if (owner == null || !"ACTIVE".equalsIgnoreCase(owner.getStatus())) throw new IllegalArgumentException("AKB 用户不存在或已停用");
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 128) throw new IllegalArgumentException("PAT 名称必须为 1 到 128 个字符");
        LocalDateTime now = LocalDateTime.now();
        if (expiresAt == null || !expiresAt.isAfter(now)) throw new IllegalArgumentException("个人访问令牌必须设置未来到期时间");
        long lifetimeDays = ChronoUnit.DAYS.between(now, expiresAt);
        if (lifetimeDays > PAT_MAX_LIFETIME_DAYS || expiresAt.isAfter(now.plusDays(PAT_MAX_LIFETIME_DAYS))) {
            throw new IllegalArgumentException("个人访问令牌最长有效期为 " + PAT_MAX_LIFETIME_DAYS + " 天");
        }
        List<String> scopes = requestedScopes == null || requestedScopes.isEmpty()
            ? new ArrayList<String>(PAT_DEFAULT_SCOPES) : requestedScopes;
        String normalizedScopes = normalizeScopes(scopes);
        for (String scope : normalizedScopes.split(",")) {
            if (!PAT_DEFAULT_SCOPES.contains(scope)) {
                throw new IllegalArgumentException("个人 PAT 暂只允许 SEARCH、QUERY、LIST、READ_OBJECT；Memory 和写入权限需单独审批");
            }
        }

        ExternalPrincipal principal = mapper.getUserPrincipal(userId);
        if (principal == null) {
            principal = new ExternalPrincipal();
            principal.setPrincipalCode(ids.next("USR_"));
            principal.setPrincipalType("USER");
            principal.setOwnerUserId(userId);
            principal.setName("AKB 用户 " + userId);
            principal.setCreatedBy(userId);
            mapper.insertPrincipal(principal);
        }
        if (!"ACTIVE".equalsIgnoreCase(principal.getStatus())) throw new IllegalArgumentException("用户主体已停用");
        refreshPersonalPrincipalGrants(principal.getId(), owner);

        String secret = newSecret();
        ApiKey key = new ApiKey();
        key.setKeyCode(ids.next("PAT_"));
        key.setName(cleanName);
        key.setSecretHash(hash(secret));
        key.setKeyPrefix(secret.substring(0, SECRET_PREFIX.length() + 6));
        key.setCredentialType(ApiKey.CREDENTIAL_USER_PAT);
        key.setOwnerUserId(userId);
        key.setPrincipalId(principal.getId());
        key.setScopes(normalizedScopes);
        key.setRateLimitPerMinute(60);
        key.setStatus(ApiKey.STATUS_ACTIVE);
        key.setExpiresAt(expiresAt);
        key.setCreatedBy(userId);
        mapper.insertApiKey(key);
        Map<String, Object> result = profile(mapper.getApiKey(key.getId()));
        result.put("secret", secret);
        result.put("expiresAt", expiresAt);
        return result;
    }

    public List<Map<String, Object>> listPersonalAccessTokens(Long userId) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (ApiKey key : mapper.listPersonalApiKeys(userId)) rows.add(profile(key));
        return rows;
    }

    @Transactional
    public boolean revokePersonalAccessToken(Long userId, Long tokenId) {
        if (mapper.revokePersonalApiKey(tokenId, userId) == 0) throw new IllegalArgumentException("个人访问令牌不存在或已撤销");
        return true;
    }

    @Transactional
    public Map<String, Object> updatePersonalAccessTokenScopes(Long tokenId, List<String> scopes, Long operatorId, String reason) {
        ApiKey key = mapper.getApiKey(tokenId);
        if (key == null || !ApiKey.CREDENTIAL_USER_PAT.equalsIgnoreCase(key.getCredentialType()) || key.getOwnerUserId() == null) {
            throw new IllegalArgumentException("个人访问令牌不存在");
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty() || cleanReason.length() > 500) throw new IllegalArgumentException("授权原因必须为 1 到 500 个字符");
        String updated = normalizeScopes(scopes);
        if (mapper.updatePersonalApiKeyScopes(tokenId, updated) != 1) throw new IllegalArgumentException("个人访问令牌状态已变化");
        mapper.insertPersonalApiKeyScopeAudit(tokenId, key.getOwnerUserId(), key.getScopes(), updated, operatorId, cleanReason);
        return profile(mapper.getApiKey(tokenId));
    }

    @Transactional
    public ExternalPrincipal assignPrincipalToLocalUser(Long principalId, Long userId) {
        User user = userId == null ? null : mapper.getUserById(userId);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("只能关联到有效的 AKB 登录用户");
        }
        ExternalPrincipal principal = mapper.getPrincipal(principalId);
        if (principal == null || !"ACTIVE".equalsIgnoreCase(principal.getStatus()) || !"USER".equalsIgnoreCase(principal.getPrincipalType())) {
            throw new IllegalArgumentException("只能将 ACTIVE USER 主体关联到 AKB 用户");
        }
        ExternalPrincipal existing = mapper.getUserPrincipal(userId);
        if (existing != null && !existing.getId().equals(principalId)) throw new IllegalArgumentException("AKB 用户已关联其他 USER 主体");
        if (principal.getOwnerUserId() != null && !principal.getOwnerUserId().equals(userId)) {
            throw new IllegalArgumentException("该 USER 主体已关联到其他 AKB 用户");
        }
        if (principal.getOwnerUserId() == null && mapper.assignPrincipalOwnerUser(principalId, userId) != 1) {
            throw new IllegalArgumentException("USER 主体关联状态已变化");
        }
        return mapper.getPrincipal(principalId);
    }

    @Transactional
    public ExternalPrincipal createServicePrincipal(String code, String name, String principalType, String instanceId,
        String externalSubjectId, String memoryNamespace, String memoryAgentCode, Long operatorId) {
        String type = principalType == null ? "" : principalType.trim().toUpperCase(Locale.ROOT);
        if (!("AGENT".equals(type) || "APPLICATION".equals(type))) throw new IllegalArgumentException("服务主体类型只能是 AGENT 或 APPLICATION");
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty() || cleanName.length() > 128) throw new IllegalArgumentException("服务主体名称必须为 1 到 128 个字符");
        String cleanCode = code == null || code.trim().isEmpty() ? ids.next("PR_") : code.trim();
        if (cleanCode.length() > 128 || !cleanCode.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException("principalCode 格式不合法");
        String instance = instanceId == null ? "" : instanceId.trim();
        String subject = externalSubjectId == null ? "" : externalSubjectId.trim();
        if ("AGENT".equals(type) && (instance.isEmpty() || subject.isEmpty())) {
            throw new IllegalArgumentException("AGENT 主体必须绑定 Octop instanceId 与不可变 agentId");
        }
        if (instance.length() > 128 || subject.length() > 255) throw new IllegalArgumentException("外部身份字段长度超限");
        ExternalPrincipal principal = new ExternalPrincipal();
        principal.setPrincipalCode(cleanCode);
        principal.setPrincipalType(type);
        principal.setExternalInstanceId(instance.isEmpty() ? null : instance);
        principal.setExternalSubjectId(subject.isEmpty() ? null : subject);
        principal.setName(cleanName);
        principal.setCreatedBy(operatorId);
        mapper.insertPrincipal(principal);
        String namespace = normalizeMemoryIdentity(memoryNamespace, "memoryNamespace");
        String agent = normalizeMemoryIdentity(memoryAgentCode, "memoryAgentCode");
        String effectiveNamespace = namespace == null ? "principal:" + principal.getId() : namespace;
        String effectiveAgent = agent == null ? cleanCode : agent;
        mapper.upsertPrincipalMemoryProfile(principal.getId(), effectiveNamespace, effectiveAgent, operatorId);
        if ("AGENT".equals(type)) mapper.insertDefaultAgentMemoryGrant(principal.getId(), effectiveNamespace, effectiveAgent, operatorId);
        return principal;
    }

    @Transactional
    public Map<String, Object> createServiceKey(Long principalId, String name, List<String> scopes, Integer rateLimitPerMinute,
        LocalDateTime expiresAt, List<String> allowedOrigins, Long operatorId) {
        ExternalPrincipal principal = mapper.getPrincipal(principalId);
        if (principal == null || !"ACTIVE".equalsIgnoreCase(principal.getStatus()) || "USER".equalsIgnoreCase(principal.getPrincipalType())) {
            throw new IllegalArgumentException("Service Key 只能签发给有效的 AGENT/APPLICATION 主体");
        }
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("到期时间必须晚于当前时间");
        String cleanName = name == null || name.trim().isEmpty() ? principal.getName() : name.trim();
        String secret = newSecret();
        ApiKey key = new ApiKey();
        key.setKeyCode(ids.next("AK_"));
        key.setName(cleanName);
        key.setSecretHash(hash(secret));
        key.setKeyPrefix(secret.substring(0, SECRET_PREFIX.length() + 6));
        key.setCredentialType(ApiKey.CREDENTIAL_SERVICE_KEY);
        key.setPrincipalId(principalId);
        key.setScopes(normalizeScopes(scopes));
        key.setRateLimitPerMinute(rateLimitPerMinute == null || rateLimitPerMinute < 1 ? 60 : rateLimitPerMinute);
        key.setStatus(ApiKey.STATUS_ACTIVE);
        key.setExpiresAt(expiresAt);
        key.setCreatedBy(operatorId);
        key.setAllowedOrigins(normalizeOrigins(allowedOrigins));
        mapper.insertApiKey(key);
        Map<String, Object> result = profile(mapper.getApiKey(key.getId()));
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
        boolean compositeMemory = key.scopeList().contains(ApiKey.SCOPE_MEMORY_MANAGE);
        if (readEnabled && !compositeMemory && !key.scopeList().contains(ApiKey.SCOPE_MEMORY_READ)) {
            throw new IllegalArgumentException("该 API Key 未签发 MEMORY_READ scope，不能通过策略新增读取权限");
        }
        if (writeEnabled && !compositeMemory && !key.scopeList().contains(ApiKey.SCOPE_MEMORY_WRITE)) {
            throw new IllegalArgumentException("该 API Key 未签发 MEMORY_WRITE scope，不能通过策略新增写入权限");
        }
        mapper.updateApiKeyMemoryPolicy(id, Boolean.valueOf(readEnabled), Boolean.valueOf(writeEnabled));
        return profile(mapper.getApiKey(id));
    }

    /**
     * Resolves a presented secret to an active credential, or returns null.
     */
    @Transactional
    public ApiKey authenticate(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            return null;
        }
        ApiKey key = mapper.getApiKeyByHash(hash(secret.trim()));
        if (key == null || !key.isUsable()) {
            return null;
        }
        if (ApiKey.CREDENTIAL_USER_PAT.equalsIgnoreCase(key.getCredentialType())) {
            User owner = key.getOwnerUserId() == null ? null : mapper.getUserById(key.getOwnerUserId());
            if (owner == null || !"ACTIVE".equalsIgnoreCase(owner.getStatus())) return null;
            refreshPersonalPrincipalGrants(key.getPrincipalId(), owner);
            key.setPermissionTags(personalAclTagsJson(owner));
        }
        mapper.touchApiKey(key.getId());
        return key;
    }

    public User ownerUser(ApiKey key) {
        if (key == null || key.getOwnerUserId() == null) return null;
        return mapper.getUserById(key.getOwnerUserId());
    }

    private void refreshPersonalPrincipalGrants(Long principalId, User owner) {
        if (principalId == null || owner == null || knowledgeBaseAccess == null) return;
        List<Long> readable = knowledgeBaseAccess.readableKnowledgeBaseIds(owner);
        List<String> tags = personalAclTags(owner);
        String tagsJson = isUnrestrictedAclUser(owner) ? null : json(tags);
        List<Map<String, Object>> current = mapper.listPrincipalKnowledgeBaseGrantDetails(principalId);
        boolean same = current != null && current.size() == readable.size();
        if (same) {
            for (Long knowledgeBaseId : readable) {
                Map<String, Object> row = null;
                for (Map<String, Object> candidate : current) {
                    if (knowledgeBaseId.equals(numberValue(candidate.get("knowledgeBaseId")))) { row = candidate; break; }
                }
                if (row == null || !"ACTIVE".equalsIgnoreCase(String.valueOf(row.get("status")))
                    || row.get("expiresAt") != null || !equalNullable(tagsJson, nullableString(row.get("permissionTagsJson")))) {
                    same = false;
                    break;
                }
            }
        }
        if (same) return;
        if (current != null) {
            for (Map<String, Object> row : current) {
                Long kb = numberValue(row.get("knowledgeBaseId"));
                if (kb != null) mapper.insertPrincipalKnowledgeBaseGrantAudit(principalId, kb, "REVOKE", owner.getId(),
                    "个人 PAT 权限与 AKB 用户当前可见知识库同步", nullableString(row.get("permissionTagsJson")));
            }
        }
        mapper.deletePrincipalKnowledgeBaseGrants(principalId);
        for (Long knowledgeBaseId : readable) {
            mapper.insertPrincipalKnowledgeBaseGrantWithExpiry(principalId, knowledgeBaseId, owner.getId(),
                "个人 PAT 按 AKB 用户当前可见范围同步", null, tagsJson);
            mapper.insertPrincipalKnowledgeBaseGrantAudit(principalId, knowledgeBaseId, "GRANT", owner.getId(),
                "个人 PAT 权限与 AKB 用户当前可见知识库同步", tagsJson);
        }
    }

    private List<String> personalAclTags(User owner) {
        if (isUnrestrictedAclUser(owner) || aclMapper == null) return null;
        List<String> tags = aclMapper.resolveAclPermissionTags(owner.getUserCode());
        return tags == null ? new ArrayList<String>() : tags;
    }

    private static boolean isUnrestrictedAclUser(User owner) {
        UserRole role = UserRole.of(owner.getRole());
        return role == UserRole.ADMIN || role == UserRole.KNOWLEDGE_ADMIN;
    }

    private String personalAclTagsJson(User owner) {
        return isUnrestrictedAclUser(owner) ? null : json(personalAclTags(owner));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("个人访问令牌 ACL 标签序列化失败", ex); }
    }

    private static boolean equalNullable(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    public ApiKey get(Long id) {
        return mapper.getApiKey(id);
    }

    public int countActive() {
        return mapper.countActiveApiKeys();
    }

    public List<Map<String, Object>> listPrincipals() {
        return mapper.listPrincipals();
    }

    @Transactional
    public Map<String, Object> assignPrincipal(Long keyId, Long principalId, String newPrincipalName, Long operatorId, String reason) {
        ApiKey key = mapper.getApiKey(keyId);
        if (key == null) {
            throw new IllegalArgumentException("API Key 不存在");
        }
        String cleanName = newPrincipalName == null ? "" : newPrincipalName.trim();
        if ((principalId == null) == cleanName.isEmpty()) {
            throw new IllegalArgumentException("请指定现有 principalId，或填写新主体名称（二选一）");
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) {
            throw new IllegalArgumentException("主体变更原因不能为空");
        }
        if (cleanReason.length() > 500) {
            throw new IllegalArgumentException("主体变更原因最多 500 个字符");
        }

        Long targetPrincipalId = principalId;
        if (targetPrincipalId != null) {
            ExternalPrincipal target = mapper.getPrincipal(targetPrincipalId);
            if (target == null || !"ACTIVE".equalsIgnoreCase(target.getStatus())) {
                throw new IllegalArgumentException("目标 principal 不存在或不可用");
            }
            if (targetPrincipalId.equals(key.getPrincipalId())) {
                return profile(key);
            }
        } else {
            if (cleanName.length() > 128) {
                throw new IllegalArgumentException("主体名称最多 128 个字符");
            }
            ExternalPrincipal created = new ExternalPrincipal();
            created.setPrincipalCode(ids.next("PR_"));
            created.setName(cleanName);
            created.setCreatedBy(operatorId);
            mapper.insertPrincipal(created);
            targetPrincipalId = created.getId();
            if (targetPrincipalId == null) {
                throw new IllegalStateException("创建 principal 后未返回主体 ID");
            }
            if (key.getPrincipalId() != null) {
                mapper.copyActivePrincipalKnowledgeBaseGrants(key.getPrincipalId(), targetPrincipalId, operatorId);
                for (Map<String, Object> grant : mapper.listPrincipalKnowledgeBaseGrantDetails(targetPrincipalId)) {
                    Long knowledgeBaseId = numberValue(grant.get("knowledgeBaseId"));
                    if (knowledgeBaseId != null) {
                        mapper.insertPrincipalKnowledgeBaseGrantAudit(targetPrincipalId, knowledgeBaseId, "GRANT", operatorId,
                            "主体拆分复制授权", nullableString(grant.get("permissionTagsJson")));
                    }
                }
            } else if (key.getKnowledgeBaseId() != null) {
                Long knowledgeBaseId = key.getKnowledgeBaseId();
                if (mapper.countKnowledgeBase(knowledgeBaseId) == 0) {
                    throw new IllegalArgumentException("知识库不存在：" + knowledgeBaseId);
                }
                mapper.insertPrincipalKnowledgeBaseGrant(targetPrincipalId, knowledgeBaseId, operatorId, "主体拆分保留原绑定");
                mapper.insertPrincipalKnowledgeBaseGrantAudit(targetPrincipalId, knowledgeBaseId, "GRANT", operatorId,
                    "主体拆分保留原绑定", null);
            }
        }

        mapper.updateApiKeyPrincipal(keyId, targetPrincipalId);
        mapper.insertApiKeyPrincipalAudit(keyId, key.getKeyCode(), key.getPrincipalId(), targetPrincipalId, operatorId, cleanReason);
        return profile(mapper.getApiKey(keyId));
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
        row.put("credentialType", key.getCredentialType());
        row.put("ownerUserId", key.getOwnerUserId());
        row.put("knowledgeBaseId", key.getKnowledgeBaseId());
        row.put("knowledgeBaseName", key.getKnowledgeBaseName());
        row.put("principalId", key.getPrincipalId());
        row.put("principalName", key.getPrincipalName());
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
        row.put("memoryManageEnabled", Boolean.valueOf(key.hasScope(ApiKey.SCOPE_MEMORY_MANAGE)));
        row.put("lastUsedAt", key.getLastUsedAt());
        row.put("createdByName", key.getCreatedByName());
        row.put("createdTime", key.getCreatedTime());
        return row;
    }

    public List<Long> grantedKnowledgeBases(ApiKey key) {
        if (key == null || key.getPrincipalId() == null) {
            return new ArrayList<Long>();
        }
        return grantedKnowledgeBases(key.getPrincipalId());
    }

    public List<Long> grantedKnowledgeBases(Long principalId) {
        if (principalId == null) {
            return new ArrayList<Long>();
        }
        return mapper.listPrincipalKnowledgeBaseGrants(principalId);
    }

    public List<Map<String, Object>> knowledgeBaseGrantDetails(ApiKey key) {
        if (key == null || key.getPrincipalId() == null) {
            return new ArrayList<Map<String, Object>>();
        }
        return knowledgeBaseGrantDetails(key.getPrincipalId());
    }

    public List<Map<String, Object>> knowledgeBaseGrantDetails(Long principalId) {
        if (principalId == null) {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> detail : mapper.listPrincipalKnowledgeBaseGrantDetails(principalId)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(detail);
            Object rawTags = row.remove("permissionTagsJson");
            row.put("permissionTags", parsePermissionTags(rawTags));
            result.add(row);
        }
        return result;
    }

    public boolean hasKnowledgeBaseGrant(ApiKey key, Long knowledgeBaseId) {
        return knowledgeBaseId != null && grantedKnowledgeBases(key).contains(knowledgeBaseId);
    }

    @Transactional
    public List<Map<String, Object>> replaceKnowledgeBaseGrants(Long keyId, List<Long> knowledgeBaseIds, Long operatorId) {
        Map<Long, ExternalKnowledgeBaseGrantRequest> uniqueGrants = new LinkedHashMap<Long, ExternalKnowledgeBaseGrantRequest>();
        if (knowledgeBaseIds != null) {
            for (Long knowledgeBaseId : knowledgeBaseIds) {
                if (uniqueGrants.containsKey(knowledgeBaseId)) {
                    continue;
                }
                ExternalKnowledgeBaseGrantRequest grant = new ExternalKnowledgeBaseGrantRequest();
                grant.setKnowledgeBaseId(knowledgeBaseId);
                uniqueGrants.put(knowledgeBaseId, grant);
            }
        }
        return replaceKnowledgeBaseGrantDetails(keyId, new ArrayList<ExternalKnowledgeBaseGrantRequest>(uniqueGrants.values()), operatorId);
    }

    @Transactional
    public List<Map<String, Object>> replaceKnowledgeBaseGrantDetails(Long keyId, List<ExternalKnowledgeBaseGrantRequest> grants, Long operatorId) {
        ApiKey key = mapper.getApiKey(keyId);
        if (key == null) {
            throw new IllegalArgumentException("API Key 不存在");
        }
        if (key.getPrincipalId() == null) {
            throw new IllegalStateException("API Key 缺少主体，需先执行 V61 数据迁移");
        }
        return replacePrincipalKnowledgeBaseGrantDetails(key.getPrincipalId(), grants, operatorId);
    }

    @Transactional
    public List<Map<String, Object>> replacePrincipalKnowledgeBaseGrantDetails(Long principalId, List<ExternalKnowledgeBaseGrantRequest> grants,
        Long operatorId) {
        ExternalPrincipal principal = mapper.getPrincipal(principalId);
        if (principal == null || !"ACTIVE".equalsIgnoreCase(principal.getStatus())) {
            throw new IllegalArgumentException("主体不存在或不可用");
        }
        Map<Long, LocalDateTime> expirations = new LinkedHashMap<Long, LocalDateTime>();
        Map<Long, String> reasons = new LinkedHashMap<Long, String>();
        Map<Long, String> permissionTagsByKnowledgeBase = new LinkedHashMap<Long, String>();
        if (grants != null) {
            if (grants.size() > 100) {
                throw new IllegalArgumentException("单个主体最多授权 100 个知识库");
            }
            for (ExternalKnowledgeBaseGrantRequest grant : grants) {
                Long knowledgeBaseId = grant == null ? null : grant.getKnowledgeBaseId();
                if (knowledgeBaseId == null || knowledgeBaseId.longValue() <= 0) {
                    throw new IllegalArgumentException("knowledgeBaseIds 只接受正整数");
                }
                if (expirations.containsKey(knowledgeBaseId)) {
                    throw new IllegalArgumentException("知识库授权不能重复：" + knowledgeBaseId);
                }
                if (mapper.countKnowledgeBase(knowledgeBaseId) == 0) {
                    throw new IllegalArgumentException("知识库不存在：" + knowledgeBaseId);
                }
                String expiresAtText = grant.getExpiresAt();
                LocalDateTime expiresAt = null;
                if (expiresAtText != null && !expiresAtText.trim().isEmpty()) {
                    try {
                        expiresAt = LocalDateTime.parse(expiresAtText.trim());
                    } catch (DateTimeParseException e) {
                        throw new IllegalArgumentException("expiresAt 需为 ISO 时间，如 2026-12-31T23:59:59");
                    }
                    if (!expiresAt.isAfter(LocalDateTime.now())) {
                        throw new IllegalArgumentException("知识库授权到期时间必须晚于当前时间");
                    }
                }
                String reason = grant.getReason() == null ? "" : grant.getReason().trim();
                if (reason.length() > 500) {
                    throw new IllegalArgumentException("授权原因最多 500 个字符");
                }
                expirations.put(knowledgeBaseId, expiresAt);
                reasons.put(knowledgeBaseId, reason.isEmpty() ? "控制台配置授权" : reason);
                permissionTagsByKnowledgeBase.put(knowledgeBaseId, serializePermissionTags(grant.getPermissionTags()));
            }
        }
        for (Map<String, Object> oldGrant : mapper.listPrincipalKnowledgeBaseGrantDetails(principalId)) {
            Long oldKnowledgeBaseId = numberValue(oldGrant.get("knowledgeBaseId"));
            if (oldKnowledgeBaseId != null) {
                mapper.insertPrincipalKnowledgeBaseGrantAudit(principalId, oldKnowledgeBaseId, "REVOKE", operatorId,
                    "控制台替换授权配置", nullableString(oldGrant.get("permissionTagsJson")));
            }
        }
        mapper.deletePrincipalKnowledgeBaseGrants(principalId);
        for (Map.Entry<Long, LocalDateTime> entry : expirations.entrySet()) {
            Long knowledgeBaseId = entry.getKey();
            String reason = reasons.get(knowledgeBaseId);
            String permissionTagsJson = permissionTagsByKnowledgeBase.get(knowledgeBaseId);
            mapper.insertPrincipalKnowledgeBaseGrantWithExpiry(principalId, knowledgeBaseId, operatorId, reason,
                entry.getValue(), permissionTagsJson);
            mapper.insertPrincipalKnowledgeBaseGrantAudit(principalId, knowledgeBaseId, "GRANT", operatorId, reason,
                permissionTagsJson);
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Long id : grantedKnowledgeBases(principalId)) {
            Map<String, Object> grant = new LinkedHashMap<String, Object>();
            grant.put("knowledgeBaseId", id);
            result.add(grant);
        }
        return result;
    }

    private String serializePermissionTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        if (tags.size() > 100) {
            throw new IllegalArgumentException("单个知识库最多配置 100 个资料标签");
        }
        List<String> normalized = new ArrayList<String>();
        for (String value : tags) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            String tag = value.trim();
            if (tag.length() > 128) {
                throw new IllegalArgumentException("资料标签最多 128 个字符");
            }
            if (!normalized.contains(tag)) {
                normalized.add(tag);
            }
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("资料标签无法编码", e);
        }
    }

    private List<String> parsePermissionTags(Object value) {
        String json = nullableString(value);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(json, List.class);
            if (!(parsed instanceof List)) {
                throw new IllegalStateException("知识库授权标签必须为数组");
            }
            List<String> tags = new ArrayList<String>();
            for (Object item : (List<?>) parsed) {
                if (item != null) {
                    String tag = String.valueOf(item).trim();
                    if (!tag.isEmpty()) {
                        tags.add(tag);
                    }
                }
            }
            return tags;
        } catch (Exception e) {
            throw new IllegalStateException("知识库授权标签配置损坏", e);
        }
    }

    private static Long numberValue(Object value) {
        if (value instanceof Number) {
            return Long.valueOf(((Number) value).longValue());
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
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
                    || ApiKey.SCOPE_MEMORY_READ.equals(value) || ApiKey.SCOPE_MEMORY_WRITE.equals(value)
                    || ApiKey.SCOPE_MEMORY_MANAGE.equals(value);
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
