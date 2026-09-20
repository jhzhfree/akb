package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.IntegrationInsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knowledge integration read model (detailed design §6 / §11).
 *
 * <p>Separate from {@code /api/external/admin}: that controller signs credentials, this one only
 * reports what published services are doing, so the overview can safely be opened by any console
 * operator who manages integrations.</p>
 */
@RestController
@RequestMapping("/api/integration")
@CrossOrigin
@Tag(name = "Integration", description = "知识集成：服务概览与调用日志")
public class IntegrationInsightController {
    private final IntegrationInsightService service;

    public IntegrationInsightController(IntegrationInsightService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @Operation(summary = "集成概览：服务 KPI、协议分布、健康状态与最近调用")
    public ApiResponse<Object> overview(@RequestParam(value = "hours", required = false) Integer hours) {
        requireManager();
        return ApiResponse.ok(service.overview(hours));
    }

    @GetMapping("/call-logs")
    @Operation(summary = "调用日志：按协议与结果过滤，traceId 可回到 Query Trace")
    public ApiResponse<Object> callLogs(
            @RequestParam(value = "hours", required = false) Integer hours,
            @RequestParam(value = "protocol", required = false) String protocol,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit) {
        requireManager();
        return ApiResponse.ok(service.callLogs(hours, protocol, status, limit));
    }

    /** Integration surfaces are governed by administrators or knowledge-base administrators. */
    private User requireManager() {
        User user = AuthContext.get();
        if (user == null) throw new UnauthorizedException("请先登录控制台");
        UserRole role = UserRole.of(user.getRole());
        if (role != UserRole.ADMIN && role != UserRole.KNOWLEDGE_ADMIN) {
            throw new SecurityException("需要系统管理员或知识库管理员角色");
        }
        return user;
    }
}
