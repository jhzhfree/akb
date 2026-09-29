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
 * Pass-through façade for the AI-service retrieval switches: Elasticsearch and
 * Milvus can be turned on and off from the console, with their connection
 * details, instead of by editing the deployment and restarting the AI service.
 */
@RestController
@RequestMapping("/api/knowledge/settings/retrieval")
@CrossOrigin
@Tag(name = "RetrievalSettings", description = "检索索引：Elasticsearch 与 Milvus 开关")
public class RetrievalSettingsController {

    private static final String AI_SETTINGS_RETRIEVAL = "/ai/settings/retrieval";

    private static final String AI_SETTINGS_RETRIEVAL_TEST = "/ai/settings/retrieval/test";

    private static final String AI_SETTINGS_RETRIEVAL_ENV = "/ai/settings/retrieval/env";

    private final AiClient aiClient;

    public RetrievalSettingsController(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @GetMapping
    @Operation(summary = "读取检索索引配置（凭据掩码）")
    public ApiResponse<Map<String, Object>> read() {
        return ApiResponse.ok(aiClient.get(AI_SETTINGS_RETRIEVAL));
    }

    @PostMapping
    @Operation(summary = "保存检索索引配置（掩码字段视为不修改）")
    public ApiResponse<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(aiClient.post(AI_SETTINGS_RETRIEVAL, body(payload)));
    }

    @PostMapping("/test")
    @Operation(summary = "探测 Elasticsearch 与 Milvus 连通性")
    public ApiResponse<Map<String, Object>> test() {
        return ApiResponse.ok(aiClient.post(AI_SETTINGS_RETRIEVAL_TEST, Collections.<String, Object>emptyMap()));
    }

    @GetMapping("/env")
    @Operation(summary = "查看检索索引配置来源与持久化位置")
    public ApiResponse<Map<String, Object>> env() {
        return ApiResponse.ok(aiClient.get(AI_SETTINGS_RETRIEVAL_ENV));
    }

    private static Map<String, Object> body(Map<String, Object> payload) {
        return payload == null ? Collections.<String, Object>emptyMap() : payload;
    }
}
