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
public class KnowledgeGovernanceController {
    private final KnowledgeService service;
    private final AclContextResolver aclContextResolver;
    private final KnowledgeBaseAccessService accessService;

    public KnowledgeGovernanceController(KnowledgeService service, AclContextResolver aclContextResolver, KnowledgeBaseAccessService accessService) {
        this.service = service;
        this.aclContextResolver = aclContextResolver;
        this.accessService = accessService;
    }


    @PostMapping("/extract")
    @Operation(summary = "抽取候选知识")
    public ApiResponse<Object> extract(@Valid @RequestBody ExtractRequest request) {
        accessService.assertWrite(request.getKnowledgeBaseId());
        return ApiResponse.ok(service.extract(request.toMap()));
    }


    @PostMapping("/adjudicate")
    @Operation(summary = "人工裁决知识对象")
    public ApiResponse<Object> adjudicate(@Valid @RequestBody AdjudicateRequest request) {
        return ApiResponse.ok(service.adjudicate(request.toMap()));
    }


    @GetMapping("/cards/{kb}")
    public ApiResponse<Object> cards(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.cards(kb, aclContextResolver.resolve(permissionTags)));
    }


    @GetMapping("/claims/{kb}")
    public ApiResponse<Object> claims(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String
        permissionTags) {
        return ApiResponse.ok(service.claims(kb, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/assertions/{kb}")
    @Operation(summary = "断言（Assertion）清单，与 /claims 同义，供外部系统按领域术语调用（设计 §17.1）")
    public ApiResponse<Object> assertions(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String
        permissionTags) {
        return ApiResponse.ok(service.claims(kb, aclContextResolver.resolve(permissionTags)));
    }

    @PostMapping("/adjudication/{kb}/run")
    public ApiResponse<Object> intelligentAdjudicate(@PathVariable Long kb) throws Exception {
        return ApiResponse.ok(service.intelligentAdjudicate(kb));
    }


    @GetMapping("/adjudication/{kb}/assessments")
    public ApiResponse<Object> assessments(@PathVariable Long kb, @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.assessments(kb, limit));
    }


    @GetMapping("/reviews/{kb}/pending")
    public ApiResponse<Object> pendingReviews(@PathVariable Long kb) {
        return ApiResponse.ok(service.pendingReviews(kb));
    }


    @GetMapping("/conflicts/{kb}")
    public ApiResponse<Object> conflicts(@PathVariable Long kb, @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(service.conflicts(kb, status));
    }


    @PostMapping("/conflicts/{id}/resolve")
    public ApiResponse<Object> resolveConflict(@PathVariable Long id, @Valid @RequestBody ConflictResolveRequest request) {
        return ApiResponse.ok(service.resolveConflict(id, request.getStatus(), request.getResolution()));
    }


    @GetMapping("/conflicts/{id}/targets")
    @Operation(summary = "冲突双方对象对照：返回待比对的两侧内容，而非仅对象编码")
    public ApiResponse<Object> conflictTargets(@PathVariable Long id) {
        return ApiResponse.ok(service.conflictTargets(id));
    }


    @PostMapping("/conflicts/{id}/adjudicate")
    @Operation(summary = "裁决知识冲突：保留一方或双弃，并同步两侧对象状态与快照")
    public ApiResponse<Object> adjudicateConflict(@PathVariable Long id, @Valid @RequestBody ConflictAdjudicateRequest request) {
        return ApiResponse.ok(service.adjudicateConflict(id, request.getDecision(), request.getComment(), request.getReviewer()));
    }}
