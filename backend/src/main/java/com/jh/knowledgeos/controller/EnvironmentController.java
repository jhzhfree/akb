package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime environment descriptor for the console header: the UI keeps the
 * "current space + environment + index state" context bar on every space page,
 * so the environment must be readable instead of hard-coded on the client.
 */
@RestController
@RequestMapping("/api/knowledge/env")
@CrossOrigin
@Tag(name = "Environment", description = "运行环境：profile、版本与依赖形态")
public class EnvironmentController {
    private final Environment environment;

    public EnvironmentController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping
    @Operation(summary = "查看运行环境：profile、版本与外部依赖形态")
    public ApiResponse<Map<String, Object>> env() {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] active = environment.getActiveProfiles();
        String profile = active.length == 0 ? "default" : String.join(",", active);
        out.put("profile", profile);
        // 部署环境允许由运维显式覆盖，默认回落到 Spring profile，避免出现无意义的 "default"。
        out.put("environment", environment.getProperty("APP_ENV", profile));
        out.put("application", environment.getProperty("spring.application.name", "knowledge-os"));
        out.put("version", environment.getProperty("knowledge.os.version", "V1.0"));
        out.put("aiService", environment.getProperty("knowledge.ai-service-base-url", ""));
        out.put("storage", environment.getProperty("knowledge.source-storage-dir", ""));
        out.put("opsRbac", environment.getProperty("knowledge.ops-rbac-enabled", "false"));
        out.put("serverTime", Instant.now().toString());
        return ApiResponse.ok(out);
    }
}
