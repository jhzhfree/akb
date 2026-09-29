package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.service.EvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * Evaluation center (design §14): datasets, cases, runs and the gaps a run exposes.
 *
 * <p>Everything is knowledge-base scoped — an evaluation only means something inside the corpus it
 * was written against, and a dataset borrowed from another knowledge base would silently grade the
 * wrong index.</p>
 */
@RestController
@RequestMapping("/api/knowledge/evaluations")
@CrossOrigin
@Tag(name = "Evaluation", description = "评测中心：数据集、用例、运行与知识缺口")
public class EvaluationController {

    private final EvaluationService service;

    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    @GetMapping("/datasets/{kb}")
    @Operation(summary = "列出知识库的评测数据集")
    public ApiResponse<List<Map<String, Object>>> datasets(@PathVariable Long kb) {
        return ApiResponse.ok(service.listDatasets(kb));
    }

    @PostMapping("/datasets/{kb}")
    @Operation(summary = "创建评测数据集")
    public ApiResponse<Map<String, Object>> createDataset(@PathVariable Long kb, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.createDataset(kb, body == null ? new java.util.LinkedHashMap<String, Object>() : body));
    }

    @PutMapping("/datasets/{code}")
    @Operation(summary = "更新评测数据集")
    public ApiResponse<Integer> updateDataset(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.updateDataset(code, body == null ? new java.util.LinkedHashMap<String, Object>() : body));
    }

    @GetMapping("/datasets/{code}/cases")
    @Operation(summary = "列出数据集的评测用例")
    public ApiResponse<List<Map<String, Object>>> cases(@PathVariable String code) {
        return ApiResponse.ok(service.listCases(code));
    }

    @PostMapping("/datasets/{code}/cases")
    @Operation(summary = "新增评测用例")
    public ApiResponse<Map<String, Object>> createCase(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.createCase(code, body == null ? new java.util.LinkedHashMap<String, Object>() : body));
    }

    @DeleteMapping("/cases/{id}")
    @Operation(summary = "删除评测用例")
    public ApiResponse<Integer> deleteCase(@PathVariable Long id) {
        return ApiResponse.ok(service.deleteCase(id));
    }

    @PostMapping("/runs/{kb}")
    @Operation(summary = "运行评测：回放数据集并计算 Hit@K / Recall / MRR / NDCG / 延迟分位")
    public ApiResponse<Map<String, Object>> run(@PathVariable Long kb, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.run(kb, body == null ? new java.util.LinkedHashMap<String, Object>() : body));
    }

    @GetMapping("/runs/{kb}")
    @Operation(summary = "列出评测运行历史")
    public ApiResponse<List<Map<String, Object>>> runs(@PathVariable Long kb, @RequestParam(value = "datasetId", required = false) Long datasetId,
        @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.listRuns(kb, datasetId, limit));
    }

    @GetMapping("/runs/{kb}/{runCode}")
    @Operation(summary = "查看评测运行详情与逐用例结果")
    public ApiResponse<Map<String, Object>> runDetail(@PathVariable Long kb, @PathVariable String runCode) {
        return ApiResponse.ok(service.runDetail(runCode));
    }

    @GetMapping("/gaps/{kb}/{runCode}")
    @Operation(summary = "查看一次运行暴露的知识缺口（未召回期望知识的问题）")
    public ApiResponse<Map<String, Object>> gaps(@PathVariable Long kb, @PathVariable String runCode) {
        return ApiResponse.ok(service.gaps(runCode));
    }
}
