package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.SimpleAgentConnectionService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class SimpleAgentConnectionController {
    private final SimpleAgentConnectionService service;
    public SimpleAgentConnectionController(SimpleAgentConnectionService service) { this.service = service; }
    private long admin() {
        User user = AuthContext.get();
        if (user == null) throw new UnauthorizedException("请先登录控制台");
        if (UserRole.of(user.getRole()) != UserRole.ADMIN) throw new SecurityException("需要系统管理员角色");
        return user.getId();
    }
    @GetMapping("/api/external/admin/agent-connections")
    public ApiResponse<Object> list() { admin(); return ApiResponse.ok(service.list()); }
    @PostMapping("/api/external/admin/agent-connections")
    public ApiResponse<Object> create(@RequestBody Map<String, Object> body) { return ApiResponse.ok(service.create(body, admin())); }
    @PutMapping("/api/external/admin/agent-connections/{id}/scope")
    public ApiResponse<Object> scope(@PathVariable long id, @RequestBody Map<String, Object> body) { service.scope(id, body, admin()); return ApiResponse.ok(true); }
    @GetMapping("/api/external/admin/agent-connections/{id}/agents")
    public ApiResponse<Object> agents(@PathVariable long id) { admin(); return ApiResponse.ok(service.agents(id)); }
    @PostMapping("/api/external/admin/agent-connections/{id}/agents")
    public ApiResponse<Object> register(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.register(id, body, admin())); }
    // 下面这几个原本挂在复杂版 /workload/policies/** 下，简化版界面在用。收归到自己的路径下，
    // 复杂版界面删掉后它们就是唯一入口。
    @PostMapping("/api/external/admin/agent-connections/{id}/keys")
    public ApiResponse<Object> key(@PathVariable long id, @RequestBody ExternalKeyRequest body) { return ApiResponse.ok(service.issueKey(id, body, admin())); }
    @PutMapping("/api/external/admin/agent-connections/{id}/status")
    public ApiResponse<Object> status(@PathVariable long id, @RequestBody Map<String, Object> body) { service.status(id, null, body, admin()); return ApiResponse.ok(true); }
    @PutMapping("/api/external/admin/agent-connections/{id}/agents/{binding}/status")
    public ApiResponse<Object> agentStatus(@PathVariable long id, @PathVariable long binding, @RequestBody Map<String, Object> body) { service.status(id, binding, body, admin()); return ApiResponse.ok(true); }
    @PutMapping("/api/external/admin/agent-connections/{id}/agents/{binding}/keys/{kid}/status")
    public ApiResponse<Object> keyStatus(@PathVariable long id, @PathVariable long binding, @PathVariable String kid, @RequestBody Map<String, Object> body) {
        service.keyStatus(id, binding, kid, body, admin()); return ApiResponse.ok(true);
    }
    @GetMapping("/api/external/admin/agent-connections/{id}/checklist")
    public ApiResponse<Object> checklist(@PathVariable long id) { admin(); return ApiResponse.ok(service.checklist(id)); }
    @GetMapping("/api/v1/external/identity/configuration")
    public ApiResponse<Object> configuration(HttpServletRequest request, @RequestParam String agentId) {
        return ApiResponse.ok(service.configuration(request.getHeader("X-API-Key"), agentId));
    }
}
