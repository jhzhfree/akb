package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.service.ChunkPolicyService;
import com.jh.knowledgeos.service.KnowledgeBaseService;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin
public class KnowledgeSettingsController {
    private final ChunkPolicyService chunkPolicyService;
    private final KnowledgeBaseService knowledgeBaseService;
    public KnowledgeSettingsController(ChunkPolicyService chunkPolicyService, KnowledgeBaseService knowledgeBaseService) {
        this.chunkPolicyService = chunkPolicyService;
        this.knowledgeBaseService = knowledgeBaseService;
    }
    private Long resolveKb(Long kb) {
        if (kb != null) {
            return kb;
        }
        List<KnowledgeBase> bases = knowledgeBaseService.list();
        return bases.isEmpty() ? 1L : bases.get(0).getId();
    }
    @GetMapping("/settings/chunk-policy")
    @Operation(summary = "读取知识库分块策略与可选算法（设计 §4.1 分块策略）")
    public ApiResponse<Object> readChunkPolicy(@RequestParam(value = "kb", required = false) Long kb) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.putAll(chunkPolicyService.policy(resolveKb(kb)));
        payload.put("schema", chunkPolicyService.schema());
        return ApiResponse.ok(payload);
    }    @PutMapping("/settings/chunk-policy")
    @Operation(summary = "保存知识库分块策略（下次接入生效，已有文档需重新接入才能重切）")
    public ApiResponse<Object> saveChunkPolicy(@RequestParam(value = "kb", required = false) Long kb, @RequestBody(required = false) Map<String,
        Object> payload) {
        return ApiResponse.ok(chunkPolicyService.save(resolveKb(kb), payload));
    }    @PostMapping("/settings/chunk-policy/reset")
    @Operation(summary = "恢复内置默认分块策略")
    public ApiResponse<Object> resetChunkPolicy(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(chunkPolicyService.reset(resolveKb(kb)));
    }
}
