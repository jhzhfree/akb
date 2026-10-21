package com.jh.knowledgeos.service;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read model of the "知识集成" module (detailed design §6).
 *
 * <p>Integration is about exposing trustworthy knowledge to external systems, so the overview has
 * to answer: how many services are published, how much are they being called, is anything failing,
 * and which protocol carries the traffic. All of it is derived from the call audit log plus the
 * credential registry — no separate statistics table to keep in sync.</p>
 */
@Service
public class IntegrationInsightService {

    /**
     * 列表行里一条报文最多展示的字符数；完整报文仍由详情接口返回。
     */
    private static final int LIST_PAYLOAD_CHARS = 1500;

    private final ExternalApiMapper mapper;

    private final ApiKeyService apiKeyService;

    private final KnowledgeProperties.External properties;

    public IntegrationInsightService(ExternalApiMapper mapper, ApiKeyService apiKeyService, KnowledgeProperties properties) {
        this.mapper = mapper;
        this.apiKeyService = apiKeyService;
        this.properties = properties.external();
    }

    /**
     * 集成概览：服务 KPI、协议分布、健康状态、最近调用。
     */
    public Map<String, Object> overview(Integer hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours == null || hours <= 0 ? 24 : Math.min(hours, 24 * 30));
        Map<String, Object> stats = mapper.aggregateApiCallStats(since);
        long total = num(stats.get("total"));
        long success = num(stats.get("successCount"));
        long error = num(stats.get("errorCount"));
        long p95 = percentile(since, total);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("windowHours", hours == null ? 24 : hours);
        result.put("services", services());
        result.put("serviceCount", services().size());
        result.put("credentialCount", apiKeyService.list().size());
        result.put("activeCredentialCount", apiKeyService.countActive());
        result.put("calls", total);
        result.put("successCount", success);
        result.put("errorCount", error);
        result.put("successRate", total == 0 ? 100.0d : round(success * 100.0d / total));
        result.put("p95Ms", p95);
        result.put("avgDurationMs", num(stats.get("avgDurationMs")));
        result.put("maxDurationMs", num(stats.get("maxDurationMs")));
        result.put("protocolDistribution", protocolDistribution(since));
        result.put("health", health(error, total, p95));
        result.put("recentCalls", recentCalls(10));
        return result;
    }

    /**
     * 调用日志：protocol / status 过滤在内存完成，量级受 limit 限制。
     */
    public List<Map<String, Object>> callLogs(Integer hours, String protocol, String status, Integer limit) {
        int max = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        LocalDateTime since = LocalDateTime.now().minusHours(hours == null || hours <= 0 ? 24 : Math.min(hours, 24 * 30));
        List<ApiCallAudit> audits = mapper.listRecentApiCallAudits(200);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        String channel = protocol == null || protocol.trim().isEmpty() ? null : protocol.trim().toUpperCase();
        String outcome = status == null || status.trim().isEmpty() ? null : status.trim().toUpperCase();
        for (ApiCallAudit audit : audits) {
            if (audit.getCreatedTime() != null && audit.getCreatedTime().isBefore(since)) {
                continue;
            }
            if (channel != null && !channel.equals(String.valueOf(audit.getChannel()))) {
                continue;
            }
            boolean failed = audit.getStatusCode() != null && audit.getStatusCode() >= 400;
            if ("SUCCESS".equals(outcome) && failed) {
                continue;
            }
            if ("ERROR".equals(outcome) && !failed) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", audit.getCreatedTime());
            row.put("protocol", audit.getChannel());
            row.put("caller", audit.getCallerName());
            row.put("client", audit.getKeyCode());
            row.put("callerIp", audit.getCallerIp());
            row.put("method", audit.getMethod());
            row.put("status", audit.getStatusCode());
            row.put("latencyMs", audit.getDurationMs());
            row.put("traceId", audit.getTraceId());
            row.put("message", audit.getMessage());
            // 列表一次给几十行，完整报文会让这一屏变成几 MB；清单里给足够判断的开头，
            // 完整内容留给「查看 Trace」的详情接口。
            row.put("requestParams", digest(audit.getRequestParams()));
            row.put("responseData", digest(audit.getResponseData()));
            rows.add(row);
            if (rows.size() >= max) {
                break;
            }
        }
        return rows;
    }

    /**
     * 列表里的报文摘要：超过上限时明确标出，避免读者把截断的 JSON 当成乱码。
     */
    private String digest(String value) {
        if (value == null || value.length() <= LIST_PAYLOAD_CHARS) {
            return value;
        }
        return value.substring(0, LIST_PAYLOAD_CHARS) + "…[列表仅显示前 " + LIST_PAYLOAD_CHARS + " 字符，完整报文见「查看 Trace」]";
    }

    /**
     * One call in full — what the trace view opens.
     *
     * <p>The list row stays thin on purpose: an operator scans hundreds of them. This answers the
     * follow-up question locally, which is the whole point of storing the payloads: what exactly did
     * this caller send, and what did we hand back. Both reach the caller already sanitised and
     * bounded by the recorder, so nothing here has to reconstruct or trim them.</p>
     */
    public Map<String, Object> callDetail(String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return null;
        }
        ApiCallAudit audit = mapper.findApiCallAuditByTraceId(traceId.trim());
        if (audit == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", audit.getId());
        row.put("time", audit.getCreatedTime());
        row.put("traceId", audit.getTraceId());
        row.put("caller", audit.getCallerName());
        row.put("client", audit.getKeyCode());
        row.put("callerIp", audit.getCallerIp());
        row.put("protocol", audit.getChannel());
        row.put("method", audit.getMethod());
        row.put("knowledgeBaseId", audit.getKnowledgeBaseId());
        row.put("status", audit.getStatusCode());
        row.put("latencyMs", audit.getDurationMs());
        row.put("message", audit.getMessage());
        row.put("requestParams", audit.getRequestParams());
        row.put("responseData", audit.getResponseData());
        return row;
    }

    /**
     * 四类集成服务：Search / Retrieve / Answer / Knowledge Object（设计 §6.3）。
     */
    private List<Map<String, Object>> services() {
        String base = "/api/v1/external";
        return new ArrayList<Map<String, Object>>(java.util.Arrays.asList(service("search", "Search Service", "搜索知识对象", "POST " + base + "/search",
            properties.isEnabled()), service("retrieve", "Retrieve Service", "为外部 LLM/Agent 提供 Context", "POST " + base + "/query",
            properties.isEnabled()), service("answer", "Answer Service", "检索 + 生成一体化回答", "POST " + base + "/query", properties.isEnabled()),
            service("knowledge-object", "Knowledge Object Service", "读取知识对象与血缘", "GET " + base + "/cards/{code}", properties.isEnabled())));
    }

    private Map<String, Object> service(String code, String name, String purpose, String endpoint, boolean enabled) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("code", code);
        row.put("name", name);
        row.put("purpose", purpose);
        row.put("endpoint", endpoint);
        row.put("status", enabled ? "PUBLISHED" : "DISABLED");
        return row;
    }

    private List<Map<String, Object>> protocolDistribution(LocalDateTime since) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> raw : mapper.countCallsByChannel(since)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("protocol", raw.get("channel"));
            row.put("calls", num(raw.get("total")) + num(raw.get("successCount")) + num(raw.get("errorCount")));
            row.put("successCount", num(raw.get("successCount")));
            row.put("errorCount", num(raw.get("errorCount")));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 健康状态：错误率与 P95 决定通道告警等级，凭证数量不足提示凭证风险。
     */
    private List<Map<String, Object>> health(long error, long total, long p95) {
        double errorRate = total == 0 ? 0.0d : error * 100.0d / total;
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(healthRow("REST", properties.isEnabled() ? statusOf(errorRate, p95) : "DISABLED", errorRate, p95));
        rows.add(healthRow("MCP", properties.isMcpEnabled() ? statusOf(errorRate, p95) : "DISABLED", errorRate, p95));
        rows.add(healthRow("SSE", properties.isSseEnabled() ? statusOf(errorRate, p95) : "DISABLED", errorRate, p95));
        rows.add(healthRow("凭证", apiKeyService.countActive() > 0 ? "正常" : "无可用凭证", 0.0d, 0L));
        return rows;
    }

    private String statusOf(double errorRate, long p95) {
        if (errorRate >= 10.0d) {
            return "异常";
        }
        if (errorRate >= 2.0d || p95 >= 3000L) {
            return "限流/降级";
        }
        return "正常";
    }

    private Map<String, Object> healthRow(String name, String status, double errorRate, long p95) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("status", status);
        row.put("errorRate", round(errorRate));
        row.put("p95Ms", p95);
        return row;
    }

    private List<Map<String, Object>> recentCalls(int limit) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (ApiCallAudit audit : mapper.listRecentApiCallAudits(limit)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", audit.getCreatedTime());
            row.put("caller", audit.getCallerName());
            row.put("client", audit.getKeyCode());
            row.put("protocol", audit.getChannel());
            row.put("method", audit.getMethod());
            row.put("status", audit.getStatusCode());
            row.put("latencyMs", audit.getDurationMs());
            row.put("traceId", audit.getTraceId());
            rows.add(row);
        }
        return rows;
    }

    /**
     * P95 近似：总量小于 20 条时直接取最大值，避免小样本下分位数失真。
     */
    private long percentile(LocalDateTime since, long total) {
        if (total <= 0) {
            return 0L;
        }
        if (total < 20) {
            return num(mapper.aggregateApiCallStats(since).get("maxDurationMs"));
        }
        Integer value = mapper.percentileDuration(since, (int) Math.floor(total * 0.95d));
        return value == null ? 0L : value.longValue();
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private long num(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
