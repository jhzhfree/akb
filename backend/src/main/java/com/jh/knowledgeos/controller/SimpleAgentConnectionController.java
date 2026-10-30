package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
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
    @GetMapping("/api/v1/external/identity/configuration")
    public ApiResponse<Object> configuration(HttpServletRequest request, @RequestParam String agentId) {
        return ApiResponse.ok(service.configuration(request.getHeader("X-API-Key"), agentId));
    }
}
