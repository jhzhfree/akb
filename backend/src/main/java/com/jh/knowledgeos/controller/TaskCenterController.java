package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.AlertService;
import com.jh.knowledgeos.service.BatchIngestionService;
import com.jh.knowledgeos.service.DocumentIngestionService;
import com.jh.knowledgeos.service.TaskCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task centre and alerting console.
 *
 * <p>One screen for "what is the platform doing and what went wrong": every job in one ledger with
 * retry and cancel, the dead letters that need a human decision, and the alerts raised by the rules
 * operations staff configured. Retry is deliberately explicit — it puts a job back in the queue, it
 * does not pretend the failure did not happen.</p>
 */
@RestController
@RequestMapping("/api/knowledge/tasks")
@CrossOrigin
@Tag(name = "TaskCenter", description = "任务中心：任务、重试、死信与告警")
public class TaskCenterController {

    private final TaskCenterService tasks;

    private final AlertService alerts;

    private final DocumentIngestionService ingestion;

    private final BatchIngestionService batchIngestion;

    public TaskCenterController(TaskCenterService tasks, AlertService alerts, @Lazy DocumentIngestionService ingestion, BatchIngestionService batchIngestion) {
        this.tasks = tasks;
        this.alerts = alerts;
        this.ingestion = ingestion;
        this.batchIngestion = batchIngestion;
    }

    /**
     * 总览一次给出任务、告警与规则三块数据：任务与告警各自带 offset 分页，
     * 统计口径不受分页影响（始终是筛选条件下的全量）。
     */
    @GetMapping
    @Operation(summary = "任务与告警总览：任务列表（分页）、统计、告警（分页）与规则")
    public ApiResponse<Object> overview(@RequestParam(value = "kb", required = false) Long kb, @RequestParam(value = "status", required = false)
        String status, @RequestParam(value = "taskType", required = false) String taskType, @RequestParam(value = "triggerType", required = false)
        String triggerType, @RequestParam(value = "limit", required = false) Integer limit, @RequestParam(value = "offset", required = false,
        defaultValue = "0") int offset, @RequestParam(value = "alertLimit", required = false) Integer alertLimit,
        @RequestParam(value = "alertOffset", required = false, defaultValue = "0") int alertOffset,
        @RequestParam(value = "alertStatus", required = false) String alertStatus, @RequestParam(value = "alertSeverity", required = false)
        String alertSeverity) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tasks", tasks.list(kb, status, taskType, triggerType, limit, offset));
        payload.put("taskTotal", tasks.count(kb, status, taskType, triggerType));
        payload.put("stats", tasks.stats(kb));
        payload.put("taskTypes", TaskCenterService.taskTypes());
        payload.put("alerts", alerts.events(kb, alertStatus, alertSeverity, alertLimit == null ? 20 : alertLimit, alertOffset));
        payload.put("alertTotal", alerts.countEvents(kb, alertStatus, alertSeverity));
        payload.put("alertStats", alerts.stats(kb));
        payload.put("rules", alerts.rules(kb));
        return ApiResponse.ok(payload);
    }

    @GetMapping("/stats")
    @Operation(summary = "任务与告警统计")
    public ApiResponse<Object> stats(@RequestParam(value = "kb", required = false) Long kb) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("tasks", tasks.stats(kb));
        payload.put("alerts", alerts.stats(kb));
        return ApiResponse.ok(payload);
    }

    @GetMapping("/types")
    @Operation(summary = "任务类型与告警指标字典")
    public ApiResponse<Object> dictionaries() {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("taskTypes", TaskCenterService.taskTypes());
        payload.put("alert", alerts.schema());
        return ApiResponse.ok(payload);
    }

    @GetMapping("/{taskCode}")
    @Operation(summary = "任务详情：状态、进度、重试次数与错误信息")
    public ApiResponse<Object> detail(@PathVariable String taskCode) {
        return ApiResponse.ok(tasks.detail(taskCode));
    }

    /**
     * Retry a failed or dead-lettered job. Ingestion jobs are re-driven through the ingestion
     * pipeline; every other type is returned to the queue for its executor to pick up.
     */
    @PostMapping("/{taskCode}/retry")
    @Operation(summary = "重试任务：失败与死信任务重新入队，接入类任务重新执行")
    public ApiResponse<Object> retry(@PathVariable String taskCode, @RequestParam(value = "kb", required = false) Long kb) {
        Map<String, Object> task = tasks.retry(taskCode);
        Map<String, Object> payload = new LinkedHashMap<String, Object>(task);
        if (TaskCenterService.TYPE_INGESTION.equals(String.valueOf(task.get("taskType"))) && task.get("targetCode") != null) {
            if (BatchIngestionService.BATCH_TARGET_TYPE.equals(String.valueOf(task.get("targetType")))) {
                // 批次父任务只记总账；真正能推进它的是批次本身，交给 batch 服务只重跑其中的失败文件，
                // 否则这条任务会在重入队后永远停在 QUEUED，没人负责把它带出队列。
                try {
                    payload.put("execution", batchIngestion.retryFailed(String.valueOf(task.get("targetCode"))));
                } catch (Exception e) {
                    payload.put("execution", "重跑批量失败文件失败：" + e.getMessage());
                }
                return ApiResponse.ok(payload);
            }
            try {
                Long documentId = Long.valueOf(String.valueOf(task.get("targetCode")));
                payload.put("execution", ingestion.retry(documentId));
            } catch (NumberFormatException e) {
                payload.put("execution", "任务目标不是文档编号，已重新入队等待执行器");
            } catch (Exception e) {
                payload.put("execution", "重新接入失败：" + e.getMessage());
            }
        }
        return ApiResponse.ok(payload);
    }

    @PostMapping("/{taskCode}/cancel")
    @Operation(summary = "取消排队中或运行中的任务")
    public ApiResponse<Object> cancel(@PathVariable String taskCode) {
        return ApiResponse.ok(tasks.cancel(taskCode));
    }

    // ---------------------------------------------------------------- alerting
    @GetMapping("/alerts")
    @Operation(summary = "告警事件：未处理优先，可按下状态与级别筛选")
    public ApiResponse<Object> alerts(@RequestParam(value = "kb", required = false) Long kb, @RequestParam(value = "status", required = false) String
        status, @RequestParam(value = "severity", required = false) String severity, @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("events", alerts.events(kb, status, severity, limit, offset));
        payload.put("total", alerts.countEvents(kb, status, severity));
        payload.put("stats", alerts.stats(kb));
        return ApiResponse.ok(payload);
    }

    @PostMapping("/alerts/evaluate")
    @Operation(summary = "立即评估告警规则（不等待下一次调度）")
    public ApiResponse<Object> evaluate(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(alerts.evaluate(kb));
    }

    @PostMapping("/alerts/{alertCode}/ack")
    @Operation(summary = "认领告警：有人接手，条件可能仍未恢复")
    public ApiResponse<Object> acknowledge(@PathVariable String alertCode, @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(alerts.acknowledge(alertCode, payload == null ? null : payload.get("note") == null ? null : String.valueOf(payload.get("note"))));
    }

    @PostMapping("/alerts/{alertCode}/resolve")
    @Operation(summary = "关闭告警并记录处理说明")
    public ApiResponse<Object> resolve(@PathVariable String alertCode, @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(alerts.resolve(alertCode, payload == null ? null : payload.get("note") == null ? null : String.valueOf(payload.get("note"))));
    }

    @PostMapping("/alerts/{alertCode}/reopen")
    @Operation(summary = "重新打开已关闭的告警")
    public ApiResponse<Object> reopen(@PathVariable String alertCode, @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(alerts.reopen(alertCode, payload == null ? null : payload.get("note") == null ? null : String.valueOf(payload.get("note"))));
    }

    @GetMapping("/alerts/rules")
    @Operation(summary = "告警规则列表")
    public ApiResponse<Object> rules(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(alerts.rules(kb));
    }

    @PostMapping("/alerts/rules")
    @Operation(summary = "新增告警规则：指标、阈值、窗口与级别")
    public ApiResponse<Object> createRule(@RequestParam(value = "kb", required = false) Long kb, @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(alerts.createRule(kb, payload));
    }

    @PutMapping("/alerts/rules/{ruleCode}")
    @Operation(summary = "修改告警规则")
    public ApiResponse<Object> updateRule(@PathVariable String ruleCode, @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(alerts.updateRule(ruleCode, payload));
    }

    @DeleteMapping("/alerts/rules/{ruleCode}")
    @Operation(summary = "删除告警规则")
    public ApiResponse<Object> deleteRule(@PathVariable String ruleCode) {
        alerts.deleteRule(ruleCode);
        return ApiResponse.ok("deleted");
    }
}
