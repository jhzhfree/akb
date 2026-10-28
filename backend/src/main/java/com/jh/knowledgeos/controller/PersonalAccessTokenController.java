package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.ApiKeyService;
import com.jh.knowledgeos.service.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Self-service lifecycle for user-owned, expiring personal access tokens. */
@RestController
@RequestMapping("/api/me/personal-access-tokens")
@Tag(name = "Personal Access Tokens", description = "用户个人访问令牌")
public class PersonalAccessTokenController {
    private final ApiKeyService apiKeyService;

    public PersonalAccessTokenController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    @Operation(summary = "列出当前用户的个人访问令牌，不返回明文")
    public ApiResponse<Object> list() {
        return ApiResponse.ok(apiKeyService.listPersonalAccessTokens(requireUser().getId()));
    }

    @PostMapping
    @Operation(summary = "创建强制到期的只读个人访问令牌，明文仅返回一次")
    public ApiResponse<Object> create(@RequestBody(required = false) Map<String, Object> body) {
        User user = requireUser();
        if (body == null) throw new IllegalArgumentException("请求内容不能为空");
        String name = string(body.get("name"));
        String expiry = string(body.get("expiresAt"));
        LocalDateTime expiresAt = parseExpiry(expiry);
        List<String> scopes = strings(body.get("scopes"));
        return ApiResponse.ok(apiKeyService.createPersonalAccessToken(user.getId(), name, scopes, expiresAt));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "撤销当前用户自己的个人访问令牌")
    public ApiResponse<Object> revoke(@PathVariable Long id) {
        return ApiResponse.ok(apiKeyService.revokePersonalAccessToken(requireUser().getId(), id));
    }

    @PostMapping("/{id}/rotate")
    @Operation(summary = "轮换个人访问令牌：换发新明文并立即撤销旧令牌，主体与授权不变")
    public ApiResponse<Object> rotate(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        User user = requireUser();
        String expiry = body == null ? null : string(body.get("expiresAt"));
        return ApiResponse.ok(apiKeyService.rotatePersonalAccessToken(user.getId(), id, parseExpiry(expiry)));
    }

    private User requireUser() {
        User user = AuthContext.get();
        if (user == null || user.getId() == null) throw new SecurityException("请先登录");
        return user;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDateTime parseExpiry(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("个人访问令牌必须设置到期时间");
        String value = raw.trim();
        try {
            if (value.length() == 10) return LocalDate.parse(value).atTime(23, 59, 59);
            return LocalDateTime.parse(value);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("expiresAt 需为 ISO 日期或时间，例如 2027-01-31");
        }
    }

    private static List<String> strings(Object value) {
        if (value == null) return null;
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("scopes 必须是字符串数组");
        java.util.ArrayList<String> result = new java.util.ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) throw new IllegalArgumentException("scopes 必须是字符串数组");
            result.add((String) item);
        }
        return result;
    }
}
