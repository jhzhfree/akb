package com.jh.knowledgeos.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.ChunkPolicyService;
import com.jh.knowledgeos.service.KnowledgeBaseAccessService;
import com.jh.knowledgeos.dto.AdjudicateRequest;
import com.jh.knowledgeos.dto.ConflictAdjudicateRequest;
import com.jh.knowledgeos.dto.ConflictResolveRequest;
import com.jh.knowledgeos.dto.ExtractRequest;
import com.jh.knowledgeos.dto.QueryRequest;
import com.jh.knowledgeos.dto.QueryResponse;
import com.jh.knowledgeos.dto.KnowledgeBaseRequest;
import com.jh.knowledgeos.service.AclContextResolver;
import com.jh.knowledgeos.service.AclAuditService;
import com.jh.knowledgeos.service.AclRebuildOrchestrator;
import com.jh.knowledgeos.service.BatchIngestionService;
import com.jh.knowledgeos.service.EntityGovernanceService;
import com.jh.knowledgeos.service.KnowledgeBaseService;
import com.jh.knowledgeos.service.KnowledgeBasePurgeService;
import com.jh.knowledgeos.service.IngestionObservabilityService;
import com.jh.knowledgeos.service.KnowledgeLifecycleService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import javax.validation.Valid;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Focused HTTP facade extracted from KnowledgeController. */
@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin
@Tag(name = "Knowledge", description = "知识平台领域 API")
public class KnowledgeIndexOperationsController {
    private final IndexOutboxService outboxService;
    private final AiClient aiClient;
    private final IndexSchemaService indexSchemaService;
    private final AclRebuildOrchestrator aclRebuildOrchestrator;

    public KnowledgeIndexOperationsController(
        IndexOutboxService outboxService,
        AiClient aiClient,
        IndexSchemaService indexSchemaService,
        AclRebuildOrchestrator aclRebuildOrchestrator) {
        this.outboxService = outboxService;
        this.aiClient = aiClient;
        this.indexSchemaService = indexSchemaService;
        this.aclRebuildOrchestrator = aclRebuildOrchestrator;
    }


    @GetMapping("/index/outbox/{kb}")
    @Operation(summary = "查询索引 Outbox 事件")
    public ApiResponse<Object> outbox(@PathVariable Long kb, @RequestParam(value = "status", required = false) String status, @RequestParam(value =
        "limit", required = false) Integer limit) {
        return ApiResponse.ok(outboxService.list(kb, status, limit));
    }

    @GetMapping("/index/outbox/{kb}/page")
    @Operation(summary = "分页查询索引 Outbox 事件")
    public ApiResponse<Object> outboxPage(@PathVariable Long kb, @RequestParam(value = "status", required = false) String status, @RequestParam(value
        = "page", required = false) Integer page, @RequestParam(value = "size", required = false) Integer size) {
        return ApiResponse.ok(outboxService.page(kb, status, page, size));
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


    @PostMapping("/acl/rebuild/{changeCode}/start")
    public ApiResponse<Object> startAclRebuild(@PathVariable String changeCode) {
        return ApiResponse.ok(aclRebuildOrchestrator.start(changeCode));
    }


    @PostMapping("/acl/rebuild/{jobCode}/qualify")
    public ApiResponse<Object> qualifyAclRebuild(@PathVariable String jobCode) {
        return ApiResponse.ok(aclRebuildOrchestrator.qualify(jobCode));
    }


    @PostMapping("/acl/rebuild/{jobCode}/activate")
    public ApiResponse<Object> activateAclRebuild(@PathVariable String jobCode) {
        return ApiResponse.ok(aclRebuildOrchestrator.activate(jobCode));
    }


    @GetMapping("/acl/rebuild/{kb}")
    public ApiResponse<Object> listAclRebuilds(@PathVariable Long kb, @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(aclRebuildOrchestrator.list(kb, limit));
    }


    @PostMapping("/index/schema/{kb}/rebuild")
    public ApiResponse<Map<String, Object>> rebuildIndexSchema(@PathVariable Long kb, @RequestParam String schemaVersion) {
        return ApiResponse.ok(indexSchemaService.rebuild(kb, schemaVersion));
    }


    @PostMapping("/index/schema/{kb}/activate/{releaseId}")
    public ApiResponse<Map<String, Object>> activateIndexSchema(@PathVariable Long kb, @PathVariable String releaseId) {
        return ApiResponse.ok(indexSchemaService.activate(kb, releaseId));
    }


    @PostMapping("/index/schema/{kb}/rollback/{releaseId}")
    public ApiResponse<Map<String, Object>> rollbackIndexSchema(@PathVariable Long kb, @PathVariable String releaseId) {
        return ApiResponse.ok(indexSchemaService.rollback(kb, releaseId));
    }


    @GetMapping("/index/schema/{kb}")
    public ApiResponse<Object> listIndexSchemas(@PathVariable Long kb) {
        return ApiResponse.ok(indexSchemaService.list(kb));
    }}
