package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Persistence for external-service credentials and call audits. */
public interface ExternalApiMapper {
    List<ApiKey> listApiKeys();

    ApiKey getApiKey(@Param("id") Long id);

    ApiKey getApiKeyByHash(@Param("secretHash") String secretHash);

    int insertApiKey(ApiKey key);

    int updateApiKeyStatus(@Param("id") Long id, @Param("status") String status);

    int touchApiKey(@Param("id") Long id);

    int insertApiCallAudit(ApiCallAudit audit);

    List<ApiCallAudit> listApiCallAudits(@Param("keyId") Long keyId, @Param("limit") int limit);

    /** Calls of one channel (REST / SSE / MCP): each integration surface is inspected separately. */
    /** 集成概览：最近调用（不限凭证），按时间倒序。 */
    List<ApiCallAudit> listRecentApiCallAudits(@Param("limit") int limit);

    /** 集成概览：窗口内调用总量、成功/失败数与耗时均值/最大值。 */
    Map<String, Object> aggregateApiCallStats(@Param("since") LocalDateTime since);

    /** P95 近似：窗口内耗时升序后取第 offset 条，offset 由调用方按总量换算。 */
    Integer percentileDuration(@Param("since") LocalDateTime since, @Param("offset") int offset);

    /** 协议分布：REST / MCP / SSE 各自调用量。 */
    List<Map<String, Object>> countCallsByChannel(@Param("since") LocalDateTime since);

    List<ApiCallAudit> listChannelAudits(@Param("channel") String channel, @Param("limit") int limit);

    /** One call addressed by the trace id the log row shows; null when nothing was recorded under it. */
    ApiCallAudit findApiCallAuditByTraceId(@Param("traceId") String traceId);

    int countActiveApiKeys();
}
