package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single ledger of asynchronous work.
 *
 * <p>Ingestion runs, index delivery, schema rebuilds, derivations and evaluations each used to
 * keep their own book, so answering "what is failing right now" meant knowing which table to
 * open. Every long running job now registers here first: one status vocabulary
 * (QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELED / DEAD), one retry policy with exponential
 * back-off, and one idempotent handle (the task code) that the console, the alert rules and the
 * platform event bus can all point at. The specialised tables keep their own detail.</p>
 */
@Service
public class TaskCenterService {

    public static final String QUEUED = "QUEUED";

    public static final String RUNNING = "RUNNING";

    public static final String SUCCEEDED = "SUCCEEDED";

    public static final String FAILED = "FAILED";

    public static final String CANCELED = "CANCELED";

    public static final String DEAD = "DEAD";

    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    public static final String TRIGGER_EVENT = "EVENT";

    public static final String TRIGGER_RETRY = "RETRY";

    public static final String TYPE_INGESTION = "INGESTION";

    public static final String TYPE_EXTRACTION = "EXTRACTION";

    public static final String TYPE_EMBEDDING = "EMBEDDING";

    public static final String TYPE_INDEX = "INDEX";

    public static final String TYPE_DERIVATION = "DERIVATION";

    public static final String TYPE_EVALUATION = "EVALUATION";

    public static final String TYPE_SYNC = "SYNC";

    public static final String TYPE_SCHEMA = "SCHEMA";

    public static final String TYPE_ACL_REBUILD = "ACL_REBUILD";

    /**
     * Task types the centre offers as a filter, in the order the console shows them.
     */
    public static final List<String> TASK_TYPES = Collections.unmodifiableList(Arrays.asList(TYPE_INGESTION, TYPE_EXTRACTION, TYPE_EMBEDDING,
        TYPE_INDEX, TYPE_DERIVATION, TYPE_EVALUATION, TYPE_SYNC, TYPE_SCHEMA, TYPE_ACL_REBUILD));

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<String> FAILURE_STATUSES = Collections.unmodifiableList(Arrays.asList(FAILED, DEAD));

    private final KnowledgeMapper mapper;

    private final IdGenerator ids;

    private final ObjectMapper objectMapper;

    public TaskCenterService(KnowledgeMapper mapper, IdGenerator ids, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.ids = ids;
        this.objectMapper = objectMapper;
    }

    /**
     * Register a job before it starts, so a crash mid-flight still leaves a trace.
     */
    public Map<String, Object> enqueue(Long kb, String taskType, String taskName, String targetType, String targetCode, String triggerType,
        Map<String, Object> payload) {
        String taskCode = ids.next("TASK");
        mapper.insertTaskRun(taskCode, kb, normalizeType(taskType), taskName, targetType, targetCode, defaultTrigger(triggerType), currentUser(), 3,
            write(payload));
        Map<String, Object> created = new LinkedHashMap<String, Object>();
        created.put("taskCode", taskCode);
        created.put("status", QUEUED);
        created.put("taskType", normalizeType(taskType));
        return created;
    }

    public void start(String taskCode, String stage) {
        mapper.updateTaskRun(taskCode, RUNNING, stage, 5, null, null);
    }

    public void progress(String taskCode, String stage, int progress) {
        mapper.updateTaskRun(taskCode, RUNNING, stage, Math.max(0, Math.min(100, progress)), null, null);
    }

    /**
     * Intermediate write for a job that is still running. A batch execution takes minutes and
     * the task centre is the only place operators look, so its running rows already have to carry
     * how many files landed and which failed; waiting until the end would make a stalled batch
     * indistinguishable from a running one.
     */
    public void checkpoint(String taskCode, String stage, int progress, Map<String, Object> result) {
        mapper.updateTaskRun(taskCode, RUNNING, stage, Math.max(0, Math.min(100, progress)), null, write(result));
    }

    public void succeed(String taskCode, Map<String, Object> result) {
        mapper.updateTaskRun(taskCode, SUCCEEDED, "COMPLETED", 100, null, write(result));
    }

    /**
     * Record a failure. A job that still has attempts left goes back to the queue with an
     * exponentially growing delay; one that exhausted them becomes dead-letter work that only a
     * human decision can resume.
     */
    public Map<String, Object> fail(String taskCode, String message) {
        Map<String, Object> task = mapper.getTaskRun(taskCode);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在：" + taskCode);
        }
        int attempts = toInt(task.get("retryCount")) + 1;
        int max = toInt(task.get("maxAttempts")) > 0 ? toInt(task.get("maxAttempts")) : 3;
        String status = attempts >= max ? DEAD : FAILED;
        mapper.updateTaskRun(taskCode, status, null, 100, message, null);
        Map<String, Object> outcome = new LinkedHashMap<String, Object>();
        outcome.put("taskCode", taskCode);
        outcome.put("status", status);
        outcome.put("retryCount", attempts);
        outcome.put("maxAttempts", max);
        outcome.put("retryable", !DEAD.equals(status));
        return outcome;
    }

    public Map<String, Object> cancel(String taskCode) {
        Map<String, Object> task = require(taskCode);
        String status = text(task.get("status"));
        if (!QUEUED.equals(status) && !RUNNING.equals(status)) {
            throw new IllegalArgumentException("只有排队中或运行中的任务可以取消，当前状态：" + status);
        }
        mapper.updateTaskRun(taskCode, CANCELED, "CANCELED", 100, null, null);
        return detail(taskCode);
    }

    /**
     * Put a failed or dead-lettered job back in the queue; the executor decides how to run it.
     */
    public Map<String, Object> retry(String taskCode) {
        Map<String, Object> task = require(taskCode);
        String status = text(task.get("status"));
        if (!FAILED.equals(status) && !DEAD.equals(status) && !CANCELED.equals(status)) {
            throw new IllegalArgumentException("只有失败、死信或已取消的任务可以重试，当前状态：" + status);
        }
        mapper.updateTaskRun(taskCode, QUEUED, "QUEUED", 0, null, null);
        return detail(taskCode);
    }

    /**
     * 任务分页：解析/索引类任务会随时间持续堆积，一次拉全量既拖慢页面也让「共 N 条」失真，
     * 因此取页与计数共用同一套筛选条件，由调用方按页码传 offset。
     */
    public List<Map<String, Object>> list(Long kb, String status, String taskType, String triggerType, Integer limit, int offset) {
        return mapper.listTaskRuns(kb, blank(status), blank(taskType), blank(triggerType),
            limit == null || limit <= 0 ? 50 : Math.min(limit, 200), Math.max(offset, 0));
    }

    public long count(Long kb, String status, String taskType, String triggerType) {
        return mapper.countTaskRuns(kb, blank(status), blank(taskType), blank(triggerType));
    }

    public Map<String, Object> detail(String taskCode) {
        return require(taskCode);
    }

    /**
     * Counts behind the four tiles of the task centre.
     */
    public Map<String, Object> stats(Long kb) {
        Map<String, Object> stats = new LinkedHashMap<String, Object>();
        int total = 0;
        for (Map<String, Object> row : mapper.taskStats(kb)) {
            String status = text(row.get("status"));
            int count = toInt(row.get("total"));
            total += count;
            stats.put(camel(status), count);
        }
        stats.put("total", total);
        stats.put("failure", toInt(stats.get("failed")) + toInt(stats.get("dead")));
        stats.put("deadLetter", mapper.countOutboxDead(kb));
        stats.put("documentFailed", mapper.countDocumentFailed(kb));
        return stats;
    }

    /**
     * Failures inside a window, for the alert rules.
     */
    public int countFailures(Long kb, int windowMinutes) {
        String since = LocalDateTime.now().minusMinutes(Math.max(1, windowMinutes)).format(TS);
        return mapper.countTaskOutcome(kb, since, FAILURE_STATUSES);
    }

    public int countOutcome(Long kb, int windowMinutes, List<String> statuses) {
        String since = LocalDateTime.now().minusMinutes(Math.max(1, windowMinutes)).format(TS);
        if (statuses == null || statuses.isEmpty()) {
            return 0;
        }
        return mapper.countTaskOutcome(kb, since, statuses);
    }

    public static List<String> failureStatuses() {
        return FAILURE_STATUSES;
    }

    private Map<String, Object> require(String taskCode) {
        Map<String, Object> task = mapper.getTaskRun(taskCode);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在：" + taskCode);
        }
        return task;
    }

    private String normalizeType(String taskType) {
        String type = text(taskType).toUpperCase();
        return TASK_TYPES.contains(type) ? type : TYPE_INGESTION;
    }

    private String defaultTrigger(String triggerType) {
        String trigger = text(triggerType).toUpperCase();
        if (TRIGGER_MANUAL.equals(trigger) || TRIGGER_SCHEDULED.equals(trigger) || TRIGGER_EVENT.equals(trigger) || TRIGGER_RETRY.equals(trigger)) {
            return trigger;
        }
        return TRIGGER_MANUAL;
    }

    private String currentUser() {
        User user = AuthContext.get();
        return user == null ? "system" : user.getUsername();
    }

    private String write(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String blank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String camel(String status) {
        String value = text(status).toLowerCase();
        if (value.isEmpty()) {
            return "unknown";
        }
        if (value.contains("_")) {
            StringBuilder builder = new StringBuilder();
            boolean upper = false;
            for (char c : value.toCharArray()) {
                if (c == '_') {
                    upper = true;
                } else if (upper) {
                    builder.append(Character.toUpperCase(c));
                    upper = false;
                } else {
                    builder.append(c);
                }
            }
            return builder.toString();
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Task types the console renders as a filter chip.
     */
    public static List<String> taskTypes() {
        return new ArrayList<String>(TASK_TYPES);
    }
}
