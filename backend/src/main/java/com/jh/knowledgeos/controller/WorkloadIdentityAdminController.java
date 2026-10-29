package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.ExternalKeyRequest;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.WorkloadIdentityAdminService;
import com.jh.knowledgeos.service.AgentKeyAdminService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Trust registration changes require a system administrator, not an Agent request. */
@RestController
@RequestMapping("/api/external/admin/workload")
public class WorkloadIdentityAdminController {
    private final WorkloadIdentityAdminService service;
    public WorkloadIdentityAdminController(WorkloadIdentityAdminService service) { this.service = service; }
    private AgentKeyAdminService agentKeys;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAgentKeys(AgentKeyAdminService value) { this.agentKeys = value; }
    @GetMapping("/policies/{id}/agents/{binding}/keys")
    public ApiResponse<Object> keys(@PathVariable long id, @PathVariable long binding) { administrator(); return ApiResponse.ok(agentKeys.keys(id, binding)); }
    @PostMapping("/policies/{id}/agents/{binding}/keys/challenges")
    public ApiResponse<Object> challenge(@PathVariable long id, @PathVariable long binding, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(agentKeys.challenge(id, binding, body, administrator()));
    }
    @PostMapping("/policies/{id}/agents/{binding}/keys/registrations")
    public ApiResponse<Object> register(@PathVariable long id, @PathVariable long binding, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(agentKeys.register(id, binding, body, administrator()));
    }
    @PutMapping("/policies/{id}/agents/{binding}/keys/{kid}/status")
    public ApiResponse<Object> keyStatus(@PathVariable long id, @PathVariable long binding, @PathVariable String kid, @RequestBody Map<String, Object> body) {
        agentKeys.status(id, binding, kid, body, administrator()); return ApiResponse.ok(true);
    }
    @GetMapping("/policies")
    public ApiResponse<Object> policies() { administrator(); return ApiResponse.ok(service.policies()); }
    @PostMapping("/policies")
    public ApiResponse<Object> create(@RequestBody Map<String, Object> body) { return ApiResponse.ok(service.createPolicy(body, administrator())); }
    @GetMapping("/policies/{id}/agents")
    public ApiResponse<Object> agents(@PathVariable long id) { administrator(); return ApiResponse.ok(service.bindings(id)); }
    @PostMapping("/policies/{id}/agents")
    public ApiResponse<Object> bind(@PathVariable long id, @RequestBody Map<String, Object> body) { return ApiResponse.ok(service.bind(id, body, administrator())); }
    @PostMapping("/policies/{id}/keys")
    public ApiResponse<Object> key(@PathVariable long id, @RequestBody ExternalKeyRequest body) { return ApiResponse.ok(service.createKey(id, body, administrator())); }
    @PutMapping("/policies/{id}/status")
    public ApiResponse<Object> status(@PathVariable long id, @RequestBody Map<String, Object> body) { service.status(id, null, body, administrator()); return ApiResponse.ok(true); }
    @PutMapping("/policies/{id}/agents/{binding}/status")
    public ApiResponse<Object> status(@PathVariable long id, @PathVariable long binding, @RequestBody Map<String, Object> body) { service.status(id, binding, body, administrator()); return ApiResponse.ok(true); }
    private long administrator() {
        User user = AuthContext.get();
        if (user == null) throw new UnauthorizedException("请先登录控制台");
        if (UserRole.of(user.getRole()) != UserRole.ADMIN) throw new SecurityException("需要系统管理员角色");
        return user.getId();
    }
}
