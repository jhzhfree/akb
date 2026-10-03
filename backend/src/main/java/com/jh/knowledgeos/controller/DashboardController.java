package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.AclContextResolver;
import com.jh.knowledgeos.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workbench aggregates (detailed design §3 / §11).
 *
 * <p>Kept separate from the knowledge command API: the workbench is a read-only projection that
 * changes shape together with the product, while the knowledge API follows the domain objects.</p>
 */
@RestController
@RequestMapping("/api/knowledge/dashboard")
@CrossOrigin
@Tag(name = "Dashboard", description = "知识工作台：资产 KPI、生命周期与治理待办")
public class DashboardController {

    private final DashboardService service;
    private final AclContextResolver aclContextResolver;

    public DashboardController(DashboardService service, AclContextResolver aclContextResolver) {
        this.service = service;
        this.aclContextResolver = aclContextResolver;
    }

    /**
     * 三个投影都按当前身份的 ACL 统计：控制台显示的数字必须等于该身份能看到的条目数，
     * 否则会出现「KPI 显示 173 条断言，点进去一条都没有」。
     */
    @GetMapping("/summary/{kb}")
    @Operation(summary = "工作台 KPI：来源/文档/卡片/断言/实体/关系与健康度")
    public ApiResponse<Object> summary(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String
        permissionTags) {
        return ApiResponse.ok(service.summary(kb, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/lifecycle/{kb}")
    @Operation(summary = "知识生命周期分阶段统计：接入 → 解析 → 抽取 → 衍生 → 裁决 → 发布")
    public ApiResponse<Object> lifecycle(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String
        permissionTags) {
        return ApiResponse.ok(service.lifecycle(kb, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/governance/{kb}")
    @Operation(summary = "治理待办：待审核 / 冲突 / 过期 / 无证据 / 低置信 / 衍生失效")
    public ApiResponse<Object> governance(@PathVariable Long kb, @RequestParam(value = "lowConfidence", required = false) Double lowConfidence,
        @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.governance(kb, lowConfidence, aclContextResolver.resolve(permissionTags)));
    }
}
