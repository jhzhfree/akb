package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.config.AuthInterceptor;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.LoginRequest;
import com.jh.knowledgeos.dto.PasswordChangeRequest;
import com.jh.knowledgeos.mapper.AuthMapper;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.AuthService;
import com.jh.knowledgeos.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;

/** Login, logout, and current-account endpoints for the console. */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin
@Tag(name = "Auth", description = "用户登录与会话管理")
public class AuthController {
    private final AuthService authService;
    private final UserService users;
    private final AuthMapper mapper;
    private final KnowledgeProperties.Auth properties;

    public AuthController(
            AuthService authService,
            UserService users,
            AuthMapper mapper,
            KnowledgeProperties properties) {
        this.authService = authService;
        this.users = users;
        this.mapper = mapper;
        this.properties = properties.auth();
    }

    @PostMapping("/login")
    @Operation(summary = "用户名密码登录，返回会话 token")
    public ApiResponse<Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String agent = http == null ? "" : String.valueOf(http.getHeader("User-Agent"));
        return ApiResponse.ok(authService.login(request.getUsername(), request.getPassword(), agent));
    }

    @PostMapping("/logout")
    @Operation(summary = "注销当前会话")
    public ApiResponse<Object> logout(HttpServletRequest http) {
        authService.logout(http == null ? null : AuthInterceptor.extractToken(http));
        return ApiResponse.ok(true);
    }

    @GetMapping("/me")
    @Operation(summary = "当前登录账号与角色")
    public ApiResponse<Object> me() {
        User user = AuthContext.get();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", properties.isEnabled());
        out.put("allowAnonymous", properties.isAllowAnonymous());
        out.put("user", user == null ? null : users.profile(user));
        out.put("canManageUsers", user != null && UserRole.of(user.getRole()).canManageUsers());
        out.put("canWriteKnowledge", user != null && UserRole.of(user.getRole()).canWriteKnowledge());
        return ApiResponse.ok(out);
    }

    @GetMapping("/status")
    @Operation(summary = "认证开关与是否已完成初始管理员创建")
    public ApiResponse<Object> status() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("enabled", properties.isEnabled());
        out.put("allowAnonymous", properties.isAllowAnonymous());
        out.put("initialized", mapper.countUsers() > 0);
        return ApiResponse.ok(out);
    }

    @PostMapping("/password")
    @Operation(summary = "修改当前账号密码，修改后其他会话失效")
    public ApiResponse<Object> changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        User user = AuthContext.get();
        if (user == null) {
            throw new SecurityException("未登录");
        }
        authService.changePassword(user, request.getOldPassword(), request.getNewPassword());
        return ApiResponse.ok(true);
    }
}
