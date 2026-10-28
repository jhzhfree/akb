package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.domain.EmbedTokenRevocation;
import com.jh.knowledgeos.domain.ExternalPrincipal;
import com.jh.knowledgeos.domain.ExternalIdentityLink;
import com.jh.knowledgeos.domain.ExternalIdentityBinding;
import com.jh.knowledgeos.domain.User;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Persistence for external-service credentials and call audits.
 */
public interface ExternalApiMapper {

    List<ApiKey> listApiKeys();

    ApiKey getApiKey(@Param("id") Long id);

    ApiKey getApiKeyByHash(@Param("secretHash") String secretHash);

    int insertApiKey(ApiKey key);

    int insertPrincipal(ExternalPrincipal principal);

    ExternalPrincipal getPrincipal(@Param("id") Long id);

    ExternalPrincipal getUserPrincipal(@Param("userId") Long userId);

    User getUserById(@Param("userId") Long userId);

    int assignPrincipalOwnerUser(@Param("principalId") Long principalId, @Param("userId") Long userId);

    List<ApiKey> listPersonalApiKeys(@Param("userId") Long userId);

    int revokePersonalApiKey(@Param("id") Long id, @Param("userId") Long userId);

    int updatePersonalApiKeyScopes(@Param("id") Long id, @Param("scopes") String scopes);

    int insertPersonalApiKeyScopeAudit(@Param("keyId") Long keyId, @Param("ownerUserId") Long ownerUserId,
        @Param("oldScopes") String oldScopes, @Param("newScopes") String newScopes, @Param("operatorId") Long operatorId,
        @Param("reason") String reason);

    ExternalPrincipal findActiveExternalUserBinding(@Param("provider") String provider, @Param("instanceId") String instanceId,
        @Param("externalUserId") String externalUserId);

    List<Map<String, Object>> listExternalIdentityBindings(@Param("provider") String provider,
        @Param("providerInstanceId") String providerInstanceId,
        @Param("externalUserId") String externalUserId);

    int insertExternalIdentityBinding(ExternalIdentityBinding binding);

    int reactivateExternalIdentityBinding(@Param("id") Long id, @Param("principalId") Long principalId,
        @Param("operatorId") Long operatorId, @Param("reason") String reason);

    int revokeExternalIdentityBinding(@Param("id") Long id, @Param("operatorId") Long operatorId, @Param("reason") String reason);

    int insertExternalIdentityBindingAudit(@Param("bindingId") Long bindingId, @Param("provider") String provider,
        @Param("providerInstanceId") String providerInstanceId, @Param("externalUserId") String externalUserId,
        @Param("principalId") Long principalId, @Param("action") String action, @Param("operatorId") Long operatorId,
        @Param("reason") String reason);

    int insertOctopAssertionJti(@Param("jti") String jti, @Param("expiresAt") java.time.LocalDateTime expiresAt);

    int deleteExpiredOctopAssertionJtis();

    int upsertPrincipalMemoryProfile(@Param("principalId") Long principalId, @Param("namespace") String namespace,
        @Param("agentCode") String agentCode, @Param("operatorId") Long operatorId);

    int insertDefaultAgentMemoryGrant(@Param("principalId") Long principalId, @Param("namespace") String namespace,
        @Param("agentCode") String agentCode, @Param("operatorId") Long operatorId);

    List<Map<String, Object>> listPrincipals();

    ExternalPrincipal findActiveUserPrincipalByIdentity(@Param("issuer") String issuer, @Param("subject") String subject);

    int insertPrincipalIdentityLink(ExternalIdentityLink link);

    List<Map<String, Object>> listPrincipalIdentityLinks(@Param("principalId") Long principalId);

    int revokePrincipalIdentityLink(@Param("id") Long id, @Param("operatorId") Long operatorId);

    int insertPrincipalIdentityAudit(@Param("identityLinkId") Long identityLinkId, @Param("principalId") Long principalId,
        @Param("action") String action, @Param("operatorId") Long operatorId, @Param("reason") String reason);

    int updateApiKeyPrincipal(@Param("keyId") Long keyId, @Param("principalId") Long principalId);

    int insertApiKeyPrincipalAudit(@Param("keyId") Long keyId, @Param("keyCode") String keyCode,
        @Param("oldPrincipalId") Long oldPrincipalId, @Param("newPrincipalId") Long newPrincipalId,
        @Param("operatorId") Long operatorId, @Param("reason") String reason);

    int copyActivePrincipalKnowledgeBaseGrants(@Param("sourcePrincipalId") Long sourcePrincipalId,
        @Param("targetPrincipalId") Long targetPrincipalId, @Param("createdBy") Long createdBy);

    String getPrincipalKnowledgeBaseGrantPermissionTags(@Param("principalId") Long principalId,
        @Param("knowledgeBaseId") Long knowledgeBaseId);

    int countKnowledgeBase(@Param("knowledgeBaseId") Long knowledgeBaseId);

    int insertPrincipalKnowledgeBaseGrant(@Param("principalId") Long principalId, @Param("knowledgeBaseId") Long knowledgeBaseId,
        @Param("createdBy") Long createdBy, @Param("reason") String reason);

    int insertPrincipalKnowledgeBaseGrantWithExpiry(@Param("principalId") Long principalId, @Param("knowledgeBaseId") Long knowledgeBaseId,
        @Param("createdBy") Long createdBy, @Param("reason") String reason, @Param("expiresAt") LocalDateTime expiresAt,
        @Param("permissionTagsJson") String permissionTagsJson);

    List<Long> listPrincipalKnowledgeBaseGrants(@Param("principalId") Long principalId);

    List<Map<String, Object>> listPrincipalKnowledgeBaseGrantDetails(@Param("principalId") Long principalId);

    int deletePrincipalKnowledgeBaseGrants(@Param("principalId") Long principalId);

    int insertPrincipalKnowledgeBaseGrantAudit(@Param("principalId") Long principalId, @Param("knowledgeBaseId") Long knowledgeBaseId,
        @Param("action") String action, @Param("operatorId") Long operatorId, @Param("reason") String reason,
        @Param("permissionTagsJson") String permissionTagsJson);

    int updateApiKeyStatus(@Param("id") Long id, @Param("status") String status);

    int updateApiKeyMemoryPolicy(@Param("id") Long id, @Param("readEnabled") Boolean readEnabled,
        @Param("writeEnabled") Boolean writeEnabled);

    int touchApiKey(@Param("id") Long id);

    int insertApiCallAudit(ApiCallAudit audit);

    List<ApiCallAudit> listApiCallAudits(@Param("keyId") Long keyId, @Param("limit") int limit);

    /**
     * Calls of one channel (REST / SSE / MCP): each integration surface is inspected separately.
     */
    /**
     * 集成概览：最近调用（不限凭证），按时间倒序。
     */
    List<ApiCallAudit> listRecentApiCallAudits(@Param("limit") int limit);

    /**
     * 集成概览：窗口内调用总量、成功/失败数与耗时均值/最大值。
     */
    Map<String, Object> aggregateApiCallStats(@Param("since") LocalDateTime since);

    /**
     * P95 近似：窗口内耗时升序后取第 offset 条，offset 由调用方按总量换算。
     */
    Integer percentileDuration(@Param("since") LocalDateTime since, @Param("offset") int offset);

    /**
     * 协议分布：REST / MCP / SSE 各自调用量。
     */
    List<Map<String, Object>> countCallsByChannel(@Param("since") LocalDateTime since);

    List<ApiCallAudit> listChannelAudits(@Param("channel") String channel, @Param("limit") int limit);

    /**
     * One call addressed by the trace id the log row shows; null when nothing was recorded under it.
     */
    ApiCallAudit findApiCallAuditByTraceId(@Param("traceId") String traceId);

    int countActiveApiKeys();

    /* ---- 嵌入令牌生命周期：吊销名单与按令牌的调用轨迹 ---- */

    /**
     * Revocation row of one token id; null means the token was never revoked.
     *
     * <p>Read on every call rather than cached: a revoke is an emergency action, and a cache would
     * quietly keep accepting a token somebody just killed.</p>
     */
    EmbedTokenRevocation findEmbedTokenRevocation(@Param("jti") String jti);

    int insertEmbedTokenRevocation(EmbedTokenRevocation row);

    int deleteEmbedTokenRevocation(@Param("jti") String jti);

    List<EmbedTokenRevocation> listEmbedTokenRevocations(@Param("limit") int limit);

    List<ApiCallAudit> listTokenCallAudits(@Param("keyId") Long keyId, @Param("jti") String jti, @Param("limit") int limit);

    /**
     * Tokens seen recently, grouped: the console lists them so an operator can revoke one without
     * first digging an id out of a log line.
     */
    List<Map<String, Object>> recentTokenSummaries(@Param("since") LocalDateTime since, @Param("limit") int limit);
}
