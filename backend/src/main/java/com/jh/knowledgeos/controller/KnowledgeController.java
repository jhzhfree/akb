package com.jh.knowledgeos.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.dto.AdjudicateRequest;
import com.jh.knowledgeos.dto.ConflictResolveRequest;
import com.jh.knowledgeos.dto.ExtractRequest;
import com.jh.knowledgeos.dto.QueryRequest;
import com.jh.knowledgeos.dto.QueryResponse;
import com.jh.knowledgeos.service.KnowledgeService;
import com.jh.knowledgeos.service.IndexOutboxService;
import com.jh.knowledgeos.service.IndexSchemaService;
import com.jh.knowledgeos.service.AiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.Map;

/**
 * HTTP façade for knowledge ingestion, extraction, governance, query, and index operations.
 */
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin
@Tag(name = "Knowledge", description = "知识生成、治理、检索与可观测 API")
public class KnowledgeController {
    private final KnowledgeService service;
    private final ObjectMapper objectMapper;
    private final IndexOutboxService outboxService;
    private final AiClient aiClient;
    private final IndexSchemaService indexSchemaService;

    public KnowledgeController(
            KnowledgeService service,
            ObjectMapper objectMapper,
            IndexOutboxService outboxService,
            AiClient aiClient,
            IndexSchemaService indexSchemaService) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
        this.aiClient = aiClient;
        this.indexSchemaService = indexSchemaService;
    }

    @PostMapping(value = "/documents/upload", consumes = "multipart/form-data")
    @Operation(summary = "上传并处理知识源文档")
    public ApiResponse<Object> upload(
            @RequestParam("knowledgeBaseId") Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "authorityScore", required = false) Double authorityScore)
            throws Exception {
        return ApiResponse.ok(service.upload(knowledgeBaseId, file, authorityScore));
    }

    @GetMapping("/sources/{kb}")
    public ApiResponse<Object> sources(@PathVariable Long kb) {
        return ApiResponse.ok(service.sources(kb));
    }

    @GetMapping("/documents/{kb}")
    public ApiResponse<Object> documents(@PathVariable Long kb) {
        return ApiResponse.ok(service.documents(kb));
    }

    @PostMapping("/extract")
    @Operation(summary = "抽取候选知识")
    public ApiResponse<Object> extract(@Valid @RequestBody ExtractRequest request) {
        return ApiResponse.ok(service.extract(request.toMap()));
    }

    @PostMapping("/adjudicate")
    @Operation(summary = "人工裁决知识对象")
    public ApiResponse<Object> adjudicate(@Valid @RequestBody AdjudicateRequest request) {
        return ApiResponse.ok(service.adjudicate(request.toMap()));
    }

    @PostMapping("/query")
    @Operation(summary = "执行可追溯混合知识问答")
    public ApiResponse<QueryResponse> query(@Valid @RequestBody QueryRequest request) throws Exception {
        return ApiResponse.ok(
                objectMapper.convertValue(service.query(request.toMap()), QueryResponse.class));
    }

    @GetMapping("/cards/{kb}")
    public ApiResponse<Object> cards(@PathVariable Long kb) {
        return ApiResponse.ok(service.cards(kb));
    }

    @GetMapping("/claims/{kb}")
    public ApiResponse<Object> claims(@PathVariable Long kb) {
        return ApiResponse.ok(service.claims(kb));
    }

    @PostMapping("/adjudication/{kb}/run")
    public ApiResponse<Object> intelligentAdjudicate(@PathVariable Long kb) throws Exception {
        return ApiResponse.ok(service.intelligentAdjudicate(kb));
    }

    @GetMapping("/adjudication/{kb}/assessments")
    public ApiResponse<Object> assessments(
            @PathVariable Long kb,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.assessments(kb, limit));
    }

    @GetMapping("/reviews/{kb}/pending")
    public ApiResponse<Object> pendingReviews(@PathVariable Long kb) {
        return ApiResponse.ok(service.pendingReviews(kb));
    }

    @GetMapping("/conflicts/{kb}")
    public ApiResponse<Object> conflicts(
            @PathVariable Long kb,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(service.conflicts(kb, status));
    }

    @PostMapping("/conflicts/{id}/resolve")
    public ApiResponse<Object> resolveConflict(
            @PathVariable Long id,
            @Valid @RequestBody ConflictResolveRequest request) {
        return ApiResponse.ok(service.resolveConflict(id, request.getStatus()));
    }

    @GetMapping("/graph/{kb}/{code}")
    public ApiResponse<Object> graph(@PathVariable Long kb, @PathVariable String code) {
        return ApiResponse.ok(service.graph(kb, code));
    }

    @GetMapping("/capabilities")
    public ApiResponse<Object> capabilities() {
        return ApiResponse.ok(service.capabilities());
    }

    @GetMapping("/index/outbox/{kb}")
    @Operation(summary = "查询索引 Outbox 事件")
    public ApiResponse<Object> outbox(
            @PathVariable Long kb,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(outboxService.list(kb, status, limit));
    }

    @GetMapping("/index/outbox/{kb}/stats")
    @Operation(summary = "查询索引 Outbox 积压与死信指标")
    public ApiResponse<Object> outboxStats(@PathVariable Long kb) {
        return ApiResponse.ok(outboxService.stats(kb));
    }

    @PostMapping("/index/outbox/events/{eventId}/replay")
    @Operation(summary = "重放失败索引事件")
    public ApiResponse<Object> replayOutbox(@PathVariable String eventId) {
        return ApiResponse.ok(outboxService.replay(eventId));
    }

    @PostMapping("/index/outbox/events/{eventId}/skip")
    @Operation(summary = "人工跳过阻塞的死信事件")
    public ApiResponse<Object> skipOutbox(@PathVariable String eventId) {
        return ApiResponse.ok(outboxService.skip(eventId));
    }

    @GetMapping("/index/drift/{kb}")
    @Operation(summary = "检查 MySQL 与外部检索视图数量漂移")
    public ApiResponse<Object> indexDrift(@PathVariable Long kb) {
        return ApiResponse.ok(outboxService.drift(kb, aiClient));
    }

    @GetMapping("/trace/{qid}")
    public ApiResponse<Object> trace(@PathVariable String qid) {
        return ApiResponse.ok(service.trace(qid));
    }

    @PostMapping("/index/schema/{kb}/rebuild")
    public ApiResponse<Map<String, Object>> rebuildIndexSchema(
            @PathVariable Long kb, @RequestParam String schemaVersion) {
        return ApiResponse.ok(indexSchemaService.rebuild(kb, schemaVersion));
    }
    @PostMapping("/index/schema/{kb}/activate/{releaseId}")
    public ApiResponse<Map<String, Object>> activateIndexSchema(
            @PathVariable Long kb, @PathVariable String releaseId) {
        return ApiResponse.ok(indexSchemaService.activate(kb, releaseId));
    }
    @PostMapping("/index/schema/{kb}/rollback/{releaseId}")
    public ApiResponse<Map<String, Object>> rollbackIndexSchema(
            @PathVariable Long kb, @PathVariable String releaseId) {
        return ApiResponse.ok(indexSchemaService.rollback(kb, releaseId));
    }
    @GetMapping("/index/schema/{kb}")
    public ApiResponse<Object> listIndexSchemas(@PathVariable Long kb) {
        return ApiResponse.ok(indexSchemaService.list(kb));
    }
}
