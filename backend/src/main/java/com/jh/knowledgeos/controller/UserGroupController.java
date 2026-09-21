package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.UserGroupRequest;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.UserGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-group administration. Reading groups is open to every signed-in account because the
 * knowledge-base form offers them as a sharing target; mutations require an administrator.
 */
@RestController
@RequestMapping("/api/groups")
@CrossOrigin
@Tag(name = "UserGroups", description = "用户组管理")
public class UserGroupController {

    private final UserGroupService service;

    public UserGroupController(UserGroupService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "用户组列表")
    public ApiResponse<Object> list() {
        return ApiResponse.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "创建用户组")
    public ApiResponse<Object> create(@RequestBody UserGroupRequest request) {
        requireAdmin();
        return ApiResponse.ok(service.create(request.getName(), request.getDescription()));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户组")
    public ApiResponse<Object> update(@PathVariable Long id, @RequestBody UserGroupRequest request) {
        requireAdmin();
        return ApiResponse.ok(service.update(id, request.getName(), request.getDescription()));
    }

    @PostMapping("/{id}/status")
    @Operation(summary = "启用或归档用户组")
    public ApiResponse<Object> updateStatus(@PathVariable Long id, @RequestParam(value = "status", defaultValue = "ACTIVE") String status) {
        requireAdmin();
        service.updateStatus(id, status);
        return ApiResponse.ok(true);
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "全量替换用户组成员")
    public ApiResponse<Object> setMembers(@PathVariable Long id, @RequestBody UserGroupRequest request) {
        requireAdmin();
        return ApiResponse.ok(service.setMembers(id, request.getMemberIds()));
    }

    @DeleteMapping("/{id}/members/{userId}")
    @Operation(summary = "移除单个成员")
    public ApiResponse<Object> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        requireAdmin();
        java.util.List<Long> remaining = service.memberIdsOf(id);
        remaining.remove(userId);
        return ApiResponse.ok(service.setMembers(id, remaining));
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
