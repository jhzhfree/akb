package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import com.jh.knowledgeos.service.AclContextResolver;
import com.jh.knowledgeos.service.EntityGovernanceService;

/** Handles entity resolution, alias maintenance, and governed entity merges. */
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin
public class EntityGovernanceController {
    private final EntityGovernanceService entityGovernanceService;
    private final AclContextResolver aclContextResolver;
    public EntityGovernanceController(EntityGovernanceService entityGovernanceService, AclContextResolver aclContextResolver) {
        this.entityGovernanceService = entityGovernanceService;
        this.aclContextResolver = aclContextResolver;
    }
    @GetMapping("/entities/{kb}")
    @Operation(summary = "实体与别名词典：查看归一结果（V1.0 §5.4.1）")
    public ApiResponse<Object> entities(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String
        permissionTags) {
        return ApiResponse.ok(entityGovernanceService.entities(kb, aclContextResolver.resolve(permissionTags)));
    }    @GetMapping("/entities/{kb}/{entityCode}")
    @Operation(summary = "实体详情：别名、影响面与归一依据（V1.0 §5.4.1）")
    public ApiResponse<Object> entityDetail(@PathVariable Long kb, @PathVariable String entityCode) {
        return ApiResponse.ok(entityGovernanceService.detail(kb, entityCode));
    }    @PostMapping("/entities/{kb}/{entityCode}/aliases")
    @Operation(summary = "为实体补充人工别名（V1.0 §5.4.1）")
    public ApiResponse<Object> addEntityAlias(@PathVariable Long kb, @PathVariable String entityCode, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.addAlias(kb, entityCode, request.get("alias") == null ? null :
            String.valueOf(request.get("alias")), request.get("aliasType") == null ? null : String.valueOf(request.get("aliasType")),
            request.get("confidence") instanceof Number ? ((Number) request.get("confidence")).doubleValue() : null));
    }    @PostMapping("/entities/{kb}/{entityCode}/aliases/remove")
    @Operation(summary = "解绑实体的错误别名（V1.0 §5.4.1）")
    public ApiResponse<Object> removeEntityAlias(@PathVariable Long kb, @PathVariable String entityCode, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.removeAlias(kb, entityCode, request.get("alias") == null ? null : String.valueOf(request.get("alias"))));
    }    @PostMapping("/entities/{kb}/merge")
    @Operation(summary = "人工确认实体合并（V1.0 §5.4.1）")
    public ApiResponse<Object> mergeEntity(@PathVariable Long kb, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.merge(kb, request.get("from") == null ? null : String.valueOf(request.get("from")),
            request.get("to") == null ? null : String.valueOf(request.get("to"))));
    }    @PostMapping("/entities/{kb}/{entityCode}/status")
    @Operation(summary = "调整实体状态，可将误合并的实体恢复为 ACTIVE（V1.0 §5.4.1）")
    public ApiResponse<Object> updateEntityStatus(@PathVariable Long kb, @PathVariable String entityCode, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.updateStatus(kb, entityCode, request.get("status") == null ? null :
            String.valueOf(request.get("status"))));
    }
}
