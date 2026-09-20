package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.GovernanceInsightService;
import com.jh.knowledgeos.service.KnowledgeBaseAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only governance insight: version history and quality overview.
 *
 * <p>Design §5.1 (workbench quality) and the version traceability note both ask for the same thing:
 * a page that can answer what the knowledge is now, what it used to be, why it changed, and where
 * the original text lives. Everything here is derived from authoritative tables, never cached
 * invented numbers.</p>
 */
@RestController
@RequestMapping("/api/knowledge/governance")
@CrossOrigin
@Tag(name = "Governance Insight", description = "版本治理与知识质量概览")
public class GovernanceInsightController {

    private final GovernanceInsightService service;
    private final KnowledgeBaseAccessService accessService;

    public GovernanceInsightController(GovernanceInsightService service, KnowledgeBaseAccessService accessService) {
        this.service = service;
        this.accessService = accessService;
    }

    @GetMapping("/versions/{kb}")
    @Operation(summary = "版本快照列表：按对象类型 / 变更类型 / 关键字筛选（设计 §9.4 Lineage）")
    public ApiResponse<Map<String, Object>> versions(
            @PathVariable Long kb,
            @RequestParam(value = "objectType", required = false) String objectType,
            @RequestParam(value = "changeType", required = false) String changeType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {
        accessService.assertRead(kb);
        return ApiResponse.ok(service.versions(kb, objectType, changeType, keyword, limit, offset));
    }

    @GetMapping("/versions/{kb}/{objectType}/{objectCode}/evidence")
    @Operation(summary = "版本对象的证据原文定位链：source → document → page → heading → chunk")
    public ApiResponse<Map<String, Object>> evidence(
            @PathVariable Long kb,
            @PathVariable String objectType,
            @PathVariable String objectCode) {
        accessService.assertRead(kb);
        return ApiResponse.ok(service.evidence(kb, objectType, objectCode));
    }

    @GetMapping("/quality/{kb}")
    @Operation(summary = "知识质量概览：可信覆盖率 / 引用准确率 / 检索命中 / 新鲜度 / 待办健康度（设计 §5.1）")
    public ApiResponse<Map<String, Object>> quality(
            @PathVariable Long kb,
            @RequestParam(value = "withinDays", required = false, defaultValue = "30") int withinDays) {
        accessService.assertRead(kb);
        return ApiResponse.ok(service.quality(kb, withinDays));
    }
}
