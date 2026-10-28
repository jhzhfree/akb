package com.jh.knowledgeos.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** Persistence boundary for the isolated Agent memory domain. */
public interface AgentMemoryMapper {

    int insert(@Param("code") String code, @Param("namespace") String namespace, @Param("agent") String agent,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode, @Param("level") String level,
        @Param("content") String content, @Param("tagsJson") String tagsJson, @Param("sourceType") String sourceType,
        @Param("sourceRef") String sourceRef, @Param("confidence") Double confidence, @Param("importance") Integer importance,
        @Param("expiresAt") LocalDateTime expiresAt, @Param("keyId") Long keyId, @Param("idempotencyKey") String idempotencyKey,
        @Param("payloadFingerprint") String payloadFingerprint);

    Map<String, Object> findByIdempotency(@Param("namespace") String namespace, @Param("agent") String agent,
        @Param("idempotencyKey") String idempotencyKey);

    List<Map<String, Object>> recall(@Param("namespace") String namespace, @Param("agent") String agent,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode, @Param("query") String query,
        @Param("limit") Integer limit);

    Map<String, Object> get(@Param("namespace") String namespace, @Param("code") String code);

    int forget(@Param("namespace") String namespace, @Param("code") String code, @Param("agent") String agent);

    boolean hasScopeAccess(@Param("keyId") Long keyId, @Param("namespace") String namespace,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode, @Param("accessMode") String accessMode);

    List<Map<String, Object>> listScopeAccess(@Param("keyId") Long keyId, @Param("namespace") String namespace);

    int deleteScopeAccess(@Param("keyId") Long keyId);

    int insertScopeAccess(@Param("keyId") Long keyId, @Param("namespace") String namespace,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode,
        @Param("canRead") boolean canRead, @Param("canWrite") boolean canWrite, @Param("operatorId") Long operatorId);

    boolean hasPrincipalScopeAccess(@Param("principalId") Long principalId, @Param("namespace") String namespace,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode, @Param("accessMode") String accessMode);

    List<Map<String, Object>> listPrincipalScopeAccess(@Param("principalId") Long principalId, @Param("namespace") String namespace);

    int deletePrincipalScopeAccess(@Param("principalId") Long principalId);

    int insertPrincipalScopeAccess(@Param("principalId") Long principalId, @Param("namespace") String namespace,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode,
        @Param("canRead") boolean canRead, @Param("canWrite") boolean canWrite, @Param("operatorId") Long operatorId);

    List<Map<String, Object>> listManaged(@Param("namespace") String namespace, @Param("status") String status,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode, @Param("limit") int limit, @Param("offset") int offset);

    Map<String, Object> getManaged(@Param("namespace") String namespace, @Param("code") String code);

    int updateManagedStatus(@Param("namespace") String namespace, @Param("code") String code,
        @Param("status") String status, @Param("expectedStatus") String expectedStatus);

    int deleteManaged(@Param("namespace") String namespace, @Param("code") String code);

    int insertGovernanceEvent(@Param("memoryCode") String memoryCode, @Param("namespace") String namespace,
        @Param("agentCode") String agentCode, @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode,
        @Param("operation") String operation, @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus,
        @Param("reason") String reason, @Param("operatorId") Long operatorId);

    List<Map<String, Object>> listGovernanceEvents(@Param("namespace") String namespace,
        @Param("limit") int limit, @Param("offset") int offset);

    int purgeExpired();
}
