package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.PasswordResetRequest;
import com.jh.knowledgeos.dto.UserRequest;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import javax.validation.Valid;

/**
 * Account administration; only system administrators may call these endpoints.
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin
@Tag(name = "Users", description = "用户管理")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "用户列表")
    public ApiResponse<Object> list() {
        requireAdmin();
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "创建用户")
    public ApiResponse<Object> create(@RequestBody UserRequest request) {
        requireAdmin();
        return ApiResponse.ok(service.create(request.getUsername(), request.getPassword(), request.getDisplayName(), request.getEmail(),
            request.getRole(), request.getGroupIds()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户资料、角色与用户组")
    public ApiResponse<Object> update(@PathVariable Long id, @RequestBody UserRequest request) {
        requireAdmin();
        return ApiResponse.ok(service.update(id, request.getDisplayName(), request.getEmail(), request.getRole(), request.getGroupIds()));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "启用或停用用户")
    public ApiResponse<Object> updateStatus(@PathVariable Long id, @RequestParam(value = "status", defaultValue = "ACTIVE") String status) {
        User admin = requireAdmin();
        if (admin.getId() != null && admin.getId().equals(id) && !"ACTIVE".equalsIgnoreCase(status)) {
            throw new IllegalArgumentException("不能停用当前登录的账号");
        }
        service.updateStatus(id, status);
        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/password")
    @Operation(summary = "重置用户密码")
    public ApiResponse<Object> resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordResetRequest request) {
        requireAdmin();
        service.resetPassword(id, request.getPassword());
        return ApiResponse.ok(true);
    }

    private User requireAdmin() {
        User user = AuthContext.get();
        if (user == null) {
            throw new SecurityException("未登录");
        }
        if (UserRole.of(user.getRole()) != UserRole.ADMIN) {
            throw new SecurityException("仅系统管理员可执行该操作");
        }
        return user;
    }
}
