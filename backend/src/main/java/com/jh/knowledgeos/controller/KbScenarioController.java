package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.KbScenarioService;
import com.jh.knowledgeos.service.KnowledgeBaseService;
import com.jh.knowledgeos.domain.KnowledgeBase;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * Scenario profile of a knowledge base: the templates to choose from, and the profile of one library.
 */
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin
public class KbScenarioController {

    private final KbScenarioService scenarios;

    private final KnowledgeBaseService knowledgeBaseService;

    public KbScenarioController(KbScenarioService scenarios, KnowledgeBaseService knowledgeBaseService) {
        this.scenarios = scenarios;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/settings/scenario")
    @Operation(summary = "读取知识库场景配置：模板目录、字段取值范围与当前生效配置")
    public ApiResponse<Object> read(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(scenarios.schema(resolveKb(kb)));
    }

    @PutMapping("/settings/scenario")
    @Operation(summary = "保存知识库场景配置：生成、衍生与查询三段，保存后对该知识库立即生效")
    public ApiResponse<Object> save(@RequestParam(value = "kb", required = false) Long kb, @RequestBody(required = false) java.util.Map<String,
        Object> payload) {
        return ApiResponse.ok(scenarios.save(resolveKb(kb), payload));
    }

    @PostMapping("/settings/scenario/reset")
    @Operation(summary = "恢复所选场景模板的内置默认配置")
    public ApiResponse<Object> reset(@RequestParam(value = "kb", required = false) Long kb) {
        return ApiResponse.ok(scenarios.reset(resolveKb(kb)));
    }

    private Long resolveKb(Long kb) {
        if (kb != null) {
            return kb;
        }
        List<KnowledgeBase> bases = knowledgeBaseService.list();
        return bases.isEmpty() ? 1L : bases.get(0).getId();
    }
}
