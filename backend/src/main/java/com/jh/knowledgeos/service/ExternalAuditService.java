package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Metering for the external interface-service layer.
 *
 * <p>RESTful, MCP and SSE calls all land in the same audit table, so one credential can be
 * traced across every channel it was used on.</p>
 */
@Service
public class ExternalAuditService {
    private static final Logger log = LoggerFactory.getLogger(ExternalAuditService.class);
    private final ExternalApiMapper mapper;

    public ExternalAuditService(ExternalApiMapper mapper) {
        this.mapper = mapper;
    }

    public void record(
            ApiKey key, String channel, String method, Long knowledgeBaseId,
            int statusCode, long durationMillis, String message) {
        ApiCallAudit audit = new ApiCallAudit();
        audit.setKeyId(key == null ? null : key.getId());
        audit.setKeyCode(key == null ? null : key.getKeyCode());
        audit.setChannel(channel);
        audit.setMethod(method);
        audit.setKnowledgeBaseId(knowledgeBaseId);
        audit.setStatusCode(statusCode);
        audit.setDurationMs((int) Math.min(Math.max(durationMillis, 0), Integer.MAX_VALUE));
        audit.setTraceId(MDC.get("traceId"));
        audit.setMessage(truncate(message, 500));
        try {
            mapper.insertApiCallAudit(audit);
        } catch (Exception e) {
            // 审计失败不能影响对外服务本身，只留下日志。
            log.warn("failed to persist external call audit channel={} method={}", channel, method, e);
        }
    }

    public List<ApiCallAudit> recent(Long keyId, int limit) {
        return mapper.listApiCallAudits(keyId, Math.min(Math.max(limit, 1), 200));
    }

    /** Recent calls of one channel, so an integration surface can be inspected on its own. */
    public List<ApiCallAudit> recentByChannel(String channel, int limit) {
        return mapper.listChannelAudits(channel, Math.min(Math.max(limit, 1), 200));
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
