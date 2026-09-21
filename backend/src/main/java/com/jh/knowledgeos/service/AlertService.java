package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alerting: what counts as abnormal, and what happened about it.
 *
 * <p>Raw counters are not alerts. A rule binds a metric to a threshold, a window and a severity,
 * so the platform can say "more than 30% of jobs failed in the last hour" instead of "there are
 * 41 failures". A firing rule produces one open alert identified by a dedup key: the condition
 * firing again raises the occurrence count instead of flooding the console, and when the metric
 * recovers the same alert is resolved automatically — nobody has to clear yesterday's incident.</p>
 */
@Service
public class AlertService {
    public static final String INFO = "INFO";
    public static final String WARNING = "WARNING";
    public static final String CRITICAL = "CRITICAL";

    public static final String OPEN = "OPEN";
    public static final String ACKED = "ACKED";
    public static final String RESOLVED = "RESOLVED";

    public static final String METRIC_TASK_FAILURE_COUNT = "TASK_FAILURE_COUNT";
    public static final String METRIC_TASK_FAILURE_RATE = "TASK_FAILURE_RATE";
    public static final String METRIC_DLQ_BACKLOG = "DLQ_BACKLOG";
    public static final String METRIC_DOCUMENT_FAILED = "DOCUMENT_FAILED";
    public static final String METRIC_DERIVED_STALE = "DERIVED_STALE";
    public static final String METRIC_SCHEMA_VIOLATION = "SCHEMA_VIOLATION";

    /** Metrics the console offers when a rule is created or edited. */
    public static final List<String> METRICS = Collections.unmodifiableList(Arrays.asList(
            METRIC_TASK_FAILURE_COUNT, METRIC_TASK_FAILURE_RATE, METRIC_DLQ_BACKLOG,
            METRIC_DOCUMENT_FAILED, METRIC_DERIVED_STALE, METRIC_SCHEMA_VIOLATION));

    private static final Map<String, String> METRIC_LABELS;
    static {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put(METRIC_TASK_FAILURE_COUNT, "任务失败数");
        labels.put(METRIC_TASK_FAILURE_RATE, "任务失败率(%)");
        labels.put(METRIC_DLQ_BACKLOG, "索引投递死信数");
        labels.put(METRIC_DOCUMENT_FAILED, "文档处理失败数");
        labels.put(METRIC_DERIVED_STALE, "衍生知识失效数");
        labels.put(METRIC_SCHEMA_VIOLATION, "Schema 未登记类型数");
        METRIC_LABELS = Collections.unmodifiableMap(labels);
    }

    private final KnowledgeMapper mapper;
    private final IdGenerator ids;
    private final ObjectMapper objectMapper;
    private final TaskCenterService tasks;
    private final KnowledgeSchemaService schema;
    private final PlatformEventPublisher publisher;

    public AlertService(KnowledgeMapper mapper, IdGenerator ids, ObjectMapper objectMapper,
            TaskCenterService tasks, KnowledgeSchemaService schema, PlatformEventPublisher publisher) {
        this.mapper = mapper;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.tasks = tasks;
        this.schema = schema;
        this.publisher = publisher;
    }

    // ------------------------------------------------------------------ rules

    public List<Map<String, Object>> rules(Long kb) {
        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> rule : mapper.listAlertRules(kb)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(rule);
            row.put("metricLabel", METRIC_LABELS.get(text(rule.get("metric"))));
            rules.add(row);
        }
        return rules;
    }

    public Map<String, Object> createRule(Long kb, Map<String, Object> body) {
        String name = text(body.get("name")).trim();
        String metric = text(body.get("metric")).trim().toUpperCase();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("告警规则名称不能为空");
        }
        if (!METRICS.contains(metric)) {
            throw new IllegalArgumentException("不支持的告警指标：" + metric);
        }
        double threshold = toDouble(body.get("threshold"), 1);
        int window = toInt(body.get("windowMinutes"), 60);
        String severity = severity(body.get("severity"));
        String ruleCode = ids.next("AR");
        mapper.insertAlertRule(ruleCode, kb, name, metric, severity, threshold, window,
                text(body.get("channels")).isEmpty() ? "IN_APP" : text(body.get("channels")),
                currentUser());
        return rule(ruleCode);
    }

    public Map<String, Object> updateRule(String ruleCode, Map<String, Object> body) {
        Map<String, Object> existing = mapper.getAlertRule(ruleCode);
        if (existing == null) {
            throw new IllegalArgumentException("告警规则不存在：" + ruleCode);
        }
        String metric = body.get("metric") == null ? text(existing.get("metric"))
                : text(body.get("metric")).trim().toUpperCase();
        if (!METRICS.contains(metric)) {
            throw new IllegalArgumentException("不支持的告警指标：" + metric);
        }
        mapper.updateAlertRule(ruleCode,
                body.get("name") == null ? text(existing.get("name")) : text(body.get("name")).trim(),
                metric,
                severity(body.get("severity") == null ? existing.get("severity") : body.get("severity")),
                toDouble(body.get("threshold"), toDouble(existing.get("threshold"), 1)),
                toInt(body.get("windowMinutes"), toInt(existing.get("windowMinutes"), 60)),
                body.get("channels") == null ? text(existing.get("channels")) : text(body.get("channels")),
                body.get("enabled") == null ? toBoolean(existing.get("enabled"))
                        : toBoolean(body.get("enabled")));
        return rule(ruleCode);
    }

    public void deleteRule(String ruleCode) {
        mapper.deleteAlertRule(ruleCode);
    }

    private Map<String, Object> rule(String ruleCode) {
        Map<String, Object> rule = mapper.getAlertRule(ruleCode);
        if (rule == null) {
            throw new IllegalArgumentException("告警规则不存在：" + ruleCode);
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>(rule);
        row.put("metricLabel", METRIC_LABELS.get(text(rule.get("metric"))));
        return row;
    }

    public Map<String, Object> schema() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("metrics", METRICS);
        payload.put("metricLabels", METRIC_LABELS);
        payload.put("severities", Arrays.asList(INFO, WARNING, CRITICAL));
        payload.put("statuses", Arrays.asList(OPEN, ACKED, RESOLVED));
        return payload;
    }

    // ----------------------------------------------------------------- events

    public List<Map<String, Object>> events(Long kb, String status, String severity, Integer limit) {
        return mapper.listAlertEvents(kb, blank(status), blank(severity),
                limit == null || limit <= 0 ? 50 : Math.min(limit, 200));
    }

    public Map<String, Object> stats(Long kb) {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        int open = 0;
        int acked = 0;
        int critical = 0;
        for (Map<String, Object> row : mapper.alertStats(kb)) {
            String status = text(row.get("status"));
            int total = toInt(row.get("total"), 0);
            if (OPEN.equals(status)) {
                open += total;
            } else if (ACKED.equals(status)) {
                acked += total;
            }
            if (CRITICAL.equals(text(row.get("severity"))) && !RESOLVED.equals(status)) {
                critical += total;
            }
            stats.put(status.toLowerCase() + "_" + text(row.get("severity")).toLowerCase(), total);
        }
        stats.put("open", open);
        stats.put("acked", acked);
        stats.put("critical", critical);
        stats.put("unresolved", open + acked);
        return stats;
    }

    /** Acknowledge: somebody owns it now, but the condition may still hold. */
    public Map<String, Object> acknowledge(String alertCode, String note) {
        return changeStatus(alertCode, ACKED, note);
    }

    public Map<String, Object> resolve(String alertCode, String note) {
        return changeStatus(alertCode, RESOLVED, note);
    }

    public Map<String, Object> reopen(String alertCode, String note) {
        return changeStatus(alertCode, OPEN, note);
    }

    private Map<String, Object> changeStatus(String alertCode, String status, String note) {
        Map<String, Object> alert = mapper.getAlertEvent(alertCode);
        if (alert == null) {
            throw new IllegalArgumentException("告警不存在：" + alertCode);
        }
        mapper.updateAlertStatus(alertCode, status, currentUser(), note);
        return mapper.getAlertEvent(alertCode);
    }

    // -------------------------------------------------------------- evaluation

    /**
     * Evaluate every enabled rule for one knowledge base (or platform wide when kb is null),
     * raising, updating and clearing alerts as the metrics move.
     */
    public Map<String, Object> evaluate(Long kb) {
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        int raised = 0;
        int cleared = 0;
        for (Map<String, Object> rule : mapper.listAlertRules(kb)) {
            if (!toBoolean(rule.get("enabled"))) {
                continue;
            }
            Long scope = rule.get("knowledgeBaseId") == null ? null
                    : Long.valueOf(text(rule.get("knowledgeBaseId")));
            if (kb != null && scope != null && !scope.equals(kb)) {
                continue;
            }
            double value = measure(text(rule.get("metric")), scope, toInt(rule.get("windowMinutes"), 60));
            double threshold = toDouble(rule.get("threshold"), 1);
            String dedupKey = text(rule.get("ruleCode")) + "@" + (scope == null ? "ALL" : scope);
            if (value >= threshold) {
                raise(rule, scope, value, dedupKey);
                raised++;
            } else {
                cleared += mapper.resolveAlertByDedup(dedupKey, "指标已恢复");
            }
        }
        outcome.put("raised", raised);
        outcome.put("cleared", cleared);
        outcome.put("evaluatedAt", java.time.LocalDateTime.now().toString());
        return outcome;
    }

    /** Current value of one metric. Every metric is a count or a ratio over a time window. */
    private double measure(String metric, Long scope, int windowMinutes) {
        if (METRIC_DLQ_BACKLOG.equals(metric)) {
            return mapper.countOutboxDead(scope);
        }
        if (METRIC_DOCUMENT_FAILED.equals(metric)) {
            return mapper.countDocumentFailed(scope);
        }
        if (METRIC_DERIVED_STALE.equals(metric)) {
            int stale = 0;
            for (Map<String, Object> row : mapper.derivedCandidateStats(scope)) {
                if ("STALE".equals(text(row.get("status")))) {
                    stale += toInt(row.get("total"), 0);
                }
            }
            return stale;
        }
        if (METRIC_SCHEMA_VIOLATION.equals(metric)) {
            if (scope == null) {
                return 0;
            }
            return schema.violationCount(scope);
        }
        if (METRIC_TASK_FAILURE_RATE.equals(metric)) {
            int failure = tasks.countOutcome(scope, windowMinutes, TaskCenterService.failureStatuses());
            int success = tasks.countOutcome(scope, windowMinutes,
                    Collections.singletonList(TaskCenterService.SUCCEEDED));
            int total = failure + success;
            return total == 0 ? 0 : failure * 100.0 / total;
        }
        return tasks.countOutcome(scope, windowMinutes, TaskCenterService.failureStatuses());
    }

    private void raise(Map<String, Object> rule, Long scope, double value, String dedupKey) {
        String metric = text(rule.get("metric"));
        String severity = severity(rule.get("severity"));
        String title = text(rule.get("name")) + "：" + METRIC_LABELS.get(metric) + " "
                + round(value) + " ≥ 阈值 " + round(toDouble(rule.get("threshold"), 1));
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("metric", metric);
        detail.put("metricLabel", METRIC_LABELS.get(metric));
        detail.put("value", round(value));
        detail.put("threshold", toDouble(rule.get("threshold"), 1));
        detail.put("windowMinutes", toInt(rule.get("windowMinutes"), 60));
        detail.put("ruleCode", rule.get("ruleCode"));
        // First firing, or firing again after it had recovered: worth announcing. A condition
        // that is already open only grows its occurrence count, it does not re-notify.
        Map<String, Object> existing = mapper.getAlertEventByDedup(dedupKey);
        boolean announce = existing == null || !OPEN.equals(text(existing.get("status")));
        mapper.upsertAlertEvent(ids.next("AL"), text(rule.get("ruleCode")), scope, severity, title,
                write(detail), value, dedupKey);
        if (announce) {
            Map<String, Object> current = mapper.getAlertEventByDedup(dedupKey);
            detail.put("severity", severity);
            publisher.emitSystem(PlatformEventTypes.KNOWLEDGE_GAP_DETECTED, scope, "ALERT",
                    text(current == null ? null : current.get("alertCode")), title, detail);
        }
    }

    private String severity(Object value) {
        String severity = text(value).trim().toUpperCase();
        if (INFO.equals(severity) || WARNING.equals(severity) || CRITICAL.equals(severity)) {
            return severity;
        }
        return WARNING;
    }

    private String currentUser() {
        User user = AuthContext.get();
        return user == null ? "system" : user.getUsername();
    }

    private String write(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String blank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean toBoolean(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return "1".equals(text(value)) || Boolean.parseBoolean(text(value));
    }
}
