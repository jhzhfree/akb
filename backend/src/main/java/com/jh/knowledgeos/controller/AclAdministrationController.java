package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.dto.AclResourceChangeRequest;
import com.jh.knowledgeos.service.AclAdministrationService;
import com.jh.knowledgeos.service.AclAuditService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge/acl")
public class /**
 * Administrative endpoints for ACL governance, propagation visibility, and audit inspection.
 */
AclAdministrationController {

    private final AclAdministrationService admin;

    private final AclAuditService audit;

    public AclAdministrationController(AclAdministrationService admin, AclAuditService audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping("/subjects")
    public ApiResponse<Object> subjects() {
        return ApiResponse.ok(admin.subjects());
    }

    @GetMapping("/roles")
    public ApiResponse<Object> roles() {
        return ApiResponse.ok(admin.roles());
    }

    /**
     * 权限变更与访问审计都是只增不减的流水：列表按 offset 取页，total 单独计数，
     * 页面因此能显示真实总条数并翻到任意一页，而不是永远只看得到最近 100 条。
     */
    @GetMapping("/changes/{kb}")
    public ApiResponse<Object> changes(@PathVariable Long kb, @RequestParam(required = false) Integer limit,
        @RequestParam(required = false, defaultValue = "0") int offset) {
        return ApiResponse.ok(page(admin.changes(kb, limit, offset), admin.countChanges(kb), limit, offset));
    }

    @GetMapping("/audits/{kb}")
    public ApiResponse<Object> audits(@PathVariable Long kb, @RequestParam(required = false) Integer limit,
        @RequestParam(required = false, defaultValue = "0") int offset) {
        return ApiResponse.ok(page(audit.list(kb, limit, offset), audit.count(kb), limit, offset));
    }

    private Map<String, Object> page(List<Map<String, Object>> items, long total, Integer limit, int offset) {
        int size = Math.min(500, Math.max(1, limit == null ? 20 : limit));
        int safeOffset = Math.max(offset, 0);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("items", items);
        payload.put("total", total);
        payload.put("page", safeOffset / size + 1);
        payload.put("size", size);
        payload.put("pageCount", (int) Math.ceil((double) total / (double) size));
        return payload;
    }

    @PostMapping("/resources/{kb}")
    @Operation(summary = "修改知识资源 ACL 并通过 Transactional Outbox 传播")
    public ApiResponse<Object> change(@PathVariable Long kb, @Valid @RequestBody AclResourceChangeRequest request) {
        return ApiResponse.ok(admin.changeResourceAcl(kb, request.getResourceType(), request.getResourceCode(), request.getPermissionTags(),
            request.getReason()));
    }
}
