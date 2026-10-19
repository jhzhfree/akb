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
        @Param("expiresAt") LocalDateTime expiresAt, @Param("keyId") Long keyId, @Param("idempotencyKey") String idempotencyKey);

    Map<String, Object> findByIdempotency(@Param("namespace") String namespace, @Param("agent") String agent,
        @Param("idempotencyKey") String idempotencyKey);

    List<Map<String, Object>> recall(@Param("namespace") String namespace, @Param("agent") String agent,
        @Param("scopeType") String scopeType, @Param("scopeCode") String scopeCode, @Param("query") String query,
        @Param("limit") Integer limit);

    Map<String, Object> get(@Param("namespace") String namespace, @Param("code") String code);

    int forget(@Param("namespace") String namespace, @Param("code") String code, @Param("agent") String agent);

    int purgeExpired();
}
