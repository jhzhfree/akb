package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.DefaultCatalogLoader;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Default catalogs (scenario templates, derivation rule presets): show where the running
 * values came from, and re-read them without a restart.
 *
 * <p>Limiting to system administrators on purpose. Reloading changes what every new
 * knowledge base gets seeded with, so it is an operational action, not a preference.
 */
@RestController
@RequestMapping("/api/admin/defaults")
@CrossOrigin
@Tag(name = "默认配置")
public class DefaultCatalogAdminController {

    @GetMapping
    @Operation(summary = "当前默认配置的来源与条数")
    public ApiResponse<Object> status() {
        requireAdmin();
        return ApiResponse.ok(DefaultCatalogLoader.status());
    }

    @PostMapping("/reload")
    @Operation(
        summary = "重新加载默认配置（场景模板、衍生规则）",
        description = "重新读取配置目录里的文件，立即对新建知识库生效；已建知识库已播种的配置不受影响。")
    public ApiResponse<Object> reload() {
        requireAdmin();
        DefaultCatalogLoader.reload();
        return ApiResponse.ok(DefaultCatalogLoader.status());
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
