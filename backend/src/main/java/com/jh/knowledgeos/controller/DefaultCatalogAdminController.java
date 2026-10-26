package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.DefaultCatalogLoader;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.AiClient;
import com.jh.knowledgeos.service.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final AiClient aiClient;

    public DefaultCatalogAdminController(AiClient aiClient) {
        this.aiClient = aiClient;
    }

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

    /**
     * Restore the shipped defaults: both catalogs in the config directory, plus the AI
     * service's query policy strategies.
     *
     * <p>Two services, two answers. The AI service may be down or refuse; that must not
     * hide the fact that the local files were restored, so each side reports separately and
     * {@code allRestored} says whether everything actually went through.
     */
    @PostMapping("/restore")
    @Operation(
        summary = "恢复出厂默认配置",
        description = "用镜像内的默认配置覆盖配置目录：场景模板、衍生规则、检索策略。已建知识库已播种的配置不受影响。")
    public ApiResponse<Object> restore() {
        requireAdmin();
        Map<String, Object> backend = DefaultCatalogLoader.restore();

        Map<String, Object> ai = new LinkedHashMap<String, Object>();
        boolean aiOk;
        try {
            Map<String, Object> result = aiClient.post(
                "/ai/settings/query-policy/restore-defaults", new LinkedHashMap<String, Object>());
            ai.put("ok", true);
            ai.put("result", result);
            aiOk = true;
        } catch (RuntimeException exc) {
            ai.put("ok", false);
            ai.put("error", exc.getMessage());
            aiOk = false;
        }

        Object failed = backend.get("failed");
        boolean filesOk = failed instanceof List && ((List<?>) failed).isEmpty();
        boolean configured = Boolean.TRUE.equals(backend.get("configured"));

        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("backend", backend);
        out.put("aiService", ai);
        out.put("allRestored", configured && filesOk && aiOk);
        return ApiResponse.ok(out);
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
