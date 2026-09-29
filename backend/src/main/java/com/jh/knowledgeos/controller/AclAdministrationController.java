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

    @GetMapping("/changes/{kb}")
    public ApiResponse<Object> changes(@PathVariable Long kb, @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(admin.changes(kb, limit));
    }

    @GetMapping("/audits/{kb}")
    public ApiResponse<Object> audits(@PathVariable Long kb, @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(audit.list(kb, limit));
    }

    @PostMapping("/resources/{kb}")
    @Operation(summary = "修改知识资源 ACL 并通过 Transactional Outbox 传播")
    public ApiResponse<Object> change(@PathVariable Long kb, @Valid @RequestBody AclResourceChangeRequest request) {
        return ApiResponse.ok(admin.changeResourceAcl(kb, request.getResourceType(), request.getResourceCode(), request.getPermissionTags(),
            request.getReason()));
    }
}
