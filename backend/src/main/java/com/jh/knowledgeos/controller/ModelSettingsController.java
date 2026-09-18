package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.AiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * Pass-through façade for the AI-service model-settings centre: the console can
 * read, edit and probe the generation / embedding endpoints without redeploying.
 */
@RestController
@RequestMapping("/api/knowledge/settings/model")
@CrossOrigin
@Tag(name = "ModelSettings", description = "模型配置：对话模型与向量模型")
public class ModelSettingsController {
    private static final String AI_SETTINGS_MODEL = "/ai/settings/model";
    private static final String AI_SETTINGS_TEST = "/ai/settings/test";
    private static final String AI_SETTINGS_ENV = "/ai/settings/env";

    private final AiClient aiClient;

    public ModelSettingsController(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @GetMapping
    @Operation(summary = "读取模型配置（凭据掩码）")
    public ApiResponse<Map<String, Object>> read() {
        return ApiResponse.ok(aiClient.get(AI_SETTINGS_MODEL));
    }

    @PostMapping
    @Operation(summary = "保存模型配置（掩码字段视为不修改）")
    public ApiResponse<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(aiClient.post(AI_SETTINGS_MODEL, body(payload)));
    }

    @PostMapping("/test")
    @Operation(summary = "测试对话模型或向量模型连通性")
    public ApiResponse<Map<String, Object>> test(@RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(aiClient.post(AI_SETTINGS_TEST, body(payload)));
    }

    @GetMapping("/env")
    @Operation(summary = "查看模型配置来源与持久化位置")
    public ApiResponse<Map<String, Object>> env() {
        return ApiResponse.ok(aiClient.get(AI_SETTINGS_ENV));
    }

    private static Map<String, Object> body(Map<String, Object> payload) {
        return payload == null ? Collections.<String, Object>emptyMap() : payload;
    }
}
