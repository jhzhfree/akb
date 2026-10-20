package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import com.jh.knowledgeos.service.KnowledgeLifecycleService;

/** Exposes lifecycle operations for expiry, supersession, and source impact reviews. */
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin
public class KnowledgeLifecycleController {
    private final KnowledgeLifecycleService knowledgeLifecycleService;
    public KnowledgeLifecycleController(KnowledgeLifecycleService knowledgeLifecycleService) {
        this.knowledgeLifecycleService = knowledgeLifecycleService;
    }
    @PostMapping("/lifecycle/{kb}/expire")
    @Operation(summary = "失效到期的知识对象（设计 V1.1 §5.5 / §17 有效期到期自动失效）")
    public ApiResponse<Object> expireDueKnowledge(@PathVariable Long kb, @RequestParam(value = "dryRun", required = false, defaultValue = "false")
        boolean dryRun) {
        return ApiResponse.ok(knowledgeLifecycleService.expireDueClaims(kb, dryRun));
    }    @GetMapping("/lifecycle/{kb}/summary")
    @Operation(summary = "知识生命周期看板：生效/过期/替代分布与即将到期数量（V1.0 §5.8）")
    public ApiResponse<Object> lifecycleSummary(@PathVariable Long kb, @RequestParam(value = "withinDays", required = false) Integer withinDays) {
        return ApiResponse.ok(knowledgeLifecycleService.summary(kb, withinDays));
    }    @GetMapping("/lifecycle/{kb}/expiring")
    @Operation(summary = "即将过期的断言清单，用于复核续期（V1.0 §5.8）")
    public ApiResponse<Object> lifecycleExpiring(@PathVariable Long kb, @RequestParam(value = "withinDays", required = false) Integer withinDays) {
        return ApiResponse.ok(knowledgeLifecycleService.expiringClaims(kb, withinDays));
    }    @PostMapping("/lifecycle/{kb}/supersede")
    @Operation(summary = "用新版本替代旧版本知识对象（设计 V1.1 §5.5 版本管理）")
    public ApiResponse<Object> supersedeKnowledge(@PathVariable Long kb, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(knowledgeLifecycleService.supersede(kb, String.valueOf(request.get("objectType")),
            String.valueOf(request.get("oldObjectCode")), String.valueOf(request.get("newObjectCode"))));
    }    @GetMapping("/lifecycle/sources/{sourceId}/impact")
    @Operation(summary = "来源删除/撤销的影响分析（设计 V1.1 §12）")
    public ApiResponse<Object> sourceImpact(@PathVariable Long sourceId) {
        return ApiResponse.ok(knowledgeLifecycleService.sourceImpact(sourceId));
    }
    /**
     * Knowledge ledger batch maintenance: assign the owner / validity window / scope on multiple
     * sources at once, or retire them in bulk. The body is loose on purpose: an operator
     * editing a ledger rarely changes every column at the same time.
     */
    @PostMapping("/lifecycle/{kb}/sources/governance")
    @Operation(summary = "知识台账批量治理：补责任人/生失效/适用范围，或批量标记废弃")
    public ApiResponse<Object> updateSourceGovernance(@PathVariable Long kb, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(knowledgeLifecycleService.updateSourceGovernance(kb, request));
    }
}
