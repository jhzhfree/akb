package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.AiApiPaths;
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
 * Pass-through façade for the AI-service query policy: operations staff tune the
 * knowledge-consumption pipeline (routing rules, recall channels, fusion weights,
 * rerank depth and answer guardrails) from the console without redeploying.
 */
@RestController
@RequestMapping("/api/knowledge/settings/query-policy")
@CrossOrigin
@Tag(name = "QueryPolicy", description = "问答策略：路由、召回、融合、重排与答案门控")
public class QueryPolicyController {
    private final AiClient aiClient;

    public QueryPolicyController(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @GetMapping
    @Operation(summary = "读取问答策略配置")
    public ApiResponse<Map<String, Object>> read() {
        return ApiResponse.ok(aiClient.get(AiApiPaths.QUERY_POLICY));
    }

    @PostMapping
    @Operation(summary = "保存问答策略配置（下一次问答起生效）")
    public ApiResponse<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(aiClient.post(AiApiPaths.QUERY_POLICY, body(payload)));
    }

    @PostMapping("/reset")
    @Operation(summary = "恢复内置默认问答策略")
    public ApiResponse<Map<String, Object>> reset() {
        return ApiResponse.ok(aiClient.post(AiApiPaths.QUERY_POLICY_RESET, Collections.<String, Object>emptyMap()));
    }

    @GetMapping("/schema")
    @Operation(summary = "查看可配置通道说明与默认值")
    public ApiResponse<Map<String, Object>> schema() {
        return ApiResponse.ok(aiClient.get(AiApiPaths.QUERY_POLICY_SCHEMA));
    }

    @GetMapping("/defaults")
    @Operation(summary = "查看环境默认值（不读取已保存配置）")
    public ApiResponse<Map<String, Object>> defaults() {
        return ApiResponse.ok(aiClient.get(AiApiPaths.QUERY_POLICY_DEFAULTS));
    }

    private static Map<String, Object> body(Map<String, Object> payload) {
        return payload == null ? Collections.<String, Object>emptyMap() : payload;
    }
}
