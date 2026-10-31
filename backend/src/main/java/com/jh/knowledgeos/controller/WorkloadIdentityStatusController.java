package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkloadIdentityStatusController {
    private final ExternalAccessService access;
    private final ExternalAuditService audit;
    public WorkloadIdentityStatusController(ExternalAccessService access, ExternalAuditService audit) { this.access = access; this.audit = audit; }
    @GetMapping("/api/v1/external/identity/status")
    @Operation(summary = "以候选 Agent 密钥验证当前身份，不读取知识库", description = "要求 V2 严格凭据、逐请求签名及 LIST scope；仅返回调用者自身身份。")
    public ApiResponse<Object> status(HttpServletRequest request) {
        long start = System.currentTimeMillis();
        ApiKey key = access.authenticate(request); access.assertScope(key, "LIST");
        Map<String, Object> result = com.jh.knowledgeos.service.VerifiedAgentIdentity.describe(key);
        audit.record(key, "REST", "GET /identity/status", null, 200, System.currentTimeMillis() - start, null, null, result);
        return ApiResponse.ok(result);
    }
}
