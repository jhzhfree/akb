package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.KnowledgeDerivationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Knowledge derivation console (design §4.5).
 *
 * <p>Derived knowledge is produced, never authored: a rule walks the graph, applies a template or
 * asks a model, and everything it produces lands as a candidate carrying its base assertions, the
 * path or template used and the rule and algorithm version behind it. A candidate becomes knowledge
 * only after review, and when a base assertion changes it is marked stale rather than left quietly
 * wrong. Rules run either on demand or on a schedule — the same execution, different trigger.</p>
 */
@RestController
@RequestMapping("/api/knowledge/derivations")
@CrossOrigin
@Tag(name = "Derivation", description = "知识衍生：规则、候选与运行记录")
public class DerivationController {

    private final KnowledgeDerivationService derivation;

    public DerivationController(KnowledgeDerivationService derivation) {
        this.derivation = derivation;
    }

    /**
     * Everything the derivation page needs in one round trip.
     */
    @GetMapping
    @Operation(summary = "衍生总览：规则、候选、运行记录与统计")
    public ApiResponse<Object> overview(@RequestParam(value = "kb", required = false) Long kb, @RequestParam(value = "status", required = false) String status, @RequestParam(value = "ruleCode", required = false) String ruleCode, @RequestParam(value = "limit", required = false) Integer limit) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("rules", derivation.rules(kb));
        payload.put("candidates", derivation.candidates(kb, status, ruleCode, limit));
        payload.put("runs", derivation.runs(kb, null, 20));
        payload.put("stats", derivation.stats(kb));
        payload.put("schema", derivation.schema());
        return ApiResponse.ok(payload);
    }

    @GetMapping("/rules")
    @Operation(summary = "衍生规则列表")
    public ApiResponse<Object> rules(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(derivation.rules(kb));
    }

    @PostMapping("/rules")
    @Operation(summary = "新增衍生规则：手动或计划触发")
    public ApiResponse<Object> createRule(@RequestParam(value = "kb", required = false) Long kb, @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(derivation.createRule(kb, payload));
    }

    @PutMapping("/rules/{ruleCode}")
    @Operation(summary = "修改衍生规则：定义、开关与计划表达式")
    public ApiResponse<Object> updateRule(@PathVariable String ruleCode, @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(derivation.updateRule(ruleCode, payload));
    }

    @DeleteMapping("/rules/{ruleCode}")
    @Operation(summary = "删除衍生规则")
    public ApiResponse<Object> deleteRule(@PathVariable String ruleCode) {
        derivation.deleteRule(ruleCode);
        return ApiResponse.ok("deleted");
    }

    @PostMapping("/rules/{ruleCode}/run")
    @Operation(summary = "手动执行衍生规则（立即产出候选并记录一次运行）")
    public ApiResponse<Object> run(@PathVariable String ruleCode, @RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(derivation.run(kb, ruleCode, KnowledgeDerivationService.SCHEDULE_MANUAL));
    }

    @PostMapping("/rules/{ruleCode}/plan")
    @Operation(summary = "把规则改为计划衍生并给定 cron 表达式；cron 为空则改回手动")
    public ApiResponse<Object> plan(@PathVariable String ruleCode, @RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> body = payload == null ? new LinkedHashMap<String, Object>() : payload;
        Map<String, Object> update = new LinkedHashMap<String, Object>(body);
        String cron = body.get("cronExpr") == null ? null : String.valueOf(body.get("cronExpr")).trim();
        update.put("scheduleType", cron == null || cron.isEmpty() ? KnowledgeDerivationService.SCHEDULE_MANUAL : KnowledgeDerivationService.SCHEDULE_SCHEDULED);
        return ApiResponse.ok(derivation.updateRule(ruleCode, update));
    }

    @GetMapping("/candidates")
    @Operation(summary = "候选衍生知识列表：草稿、失效、待审、已批准与驳回")
    public ApiResponse<Object> candidates(@RequestParam(value = "kb", required = false) Long kb, @RequestParam(value = "status", required = false) String status, @RequestParam(value = "ruleCode", required = false) String ruleCode, @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(derivation.candidates(kb, status, ruleCode, limit));
    }

    @GetMapping("/candidates/{candidateCode}")
    @Operation(summary = "候选衍生知识详情：来源断言、图谱路径与置信度")
    public ApiResponse<Object> candidate(@PathVariable String candidateCode) {
        return ApiResponse.ok(derivation.candidate(candidateCode));
    }

    @PostMapping("/candidates/{candidateCode}/review")
    @Operation(summary = "批准或驳回候选衍生知识")
    public ApiResponse<Object> review(@PathVariable String candidateCode, @RequestBody(required = false) Map<String, Object> payload) {
        String decision = payload == null ? "" : String.valueOf(payload.get("decision"));
        String reason = payload == null || payload.get("reason") == null ? null : String.valueOf(payload.get("reason"));
        return ApiResponse.ok(derivation.review(candidateCode, decision, reason));
    }

    /**
     * Called when base assertions move: the derived facts built on them are no longer current.
     */
    @PostMapping("/stale")
    @Operation(summary = "将依赖指定断言的衍生候选标记为失效")
    public ApiResponse<Object> markStale(@RequestParam(value = "kb", required = false) Long kb, @RequestBody(required = false) Map<String, Object> payload) {
        List<String> codes = new ArrayList<String>();
        if (payload != null && payload.get("assertionCodes") instanceof List) {
            for (Object item : (List<?>) payload.get("assertionCodes")) {
                codes.add(String.valueOf(item));
            }
        }
        return ApiResponse.ok(derivation.markStale(kb, codes));
    }

    @GetMapping("/runs")
    @Operation(summary = "衍生运行记录：手动与计划的执行历史")
    public ApiResponse<Object> runs(@RequestParam(value = "kb", required = false) Long kb, @RequestParam(value = "ruleCode", required = false) String ruleCode, @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(derivation.runs(kb, ruleCode, limit));
    }

    @GetMapping("/schema")
    @Operation(summary = "衍生类型、计划与默认定义字典")
    public ApiResponse<Object> schema() {
        return ApiResponse.ok(derivation.schema());
    }
}
