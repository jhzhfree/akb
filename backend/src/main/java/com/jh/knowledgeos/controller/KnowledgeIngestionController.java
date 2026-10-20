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
public class KnowledgeIngestionController {
    private final KnowledgeService service;
    private final AclContextResolver aclContextResolver;
    private final AiClient aiClient;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestionObservabilityService ingestionObservabilityService;
    private final KnowledgeBasePurgeService purgeService;
    private final BatchIngestionService batchIngestionService;

    public KnowledgeIngestionController(
        KnowledgeService service,
        AclContextResolver aclContextResolver,
        AiClient aiClient,
        KnowledgeBaseService knowledgeBaseService,
        IngestionObservabilityService ingestionObservabilityService,
        KnowledgeBasePurgeService purgeService,
        BatchIngestionService batchIngestionService) {
        this.service = service;
        this.aclContextResolver = aclContextResolver;
        this.aiClient = aiClient;
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestionObservabilityService = ingestionObservabilityService;
        this.purgeService = purgeService;
        this.batchIngestionService = batchIngestionService;
    }
























    @GetMapping("/bases")
    @Operation(summary = "列出知识库工作区")
    public ApiResponse<Object> bases() {
        return ApiResponse.ok(knowledgeBaseService.list());
    }


    @PostMapping("/bases")
    @Operation(summary = "创建知识库工作区，并指定创建人与可见范围（公开 / 私有 / 用户组）")
    public ApiResponse<Object> createBase(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.create(request.getName(), request.getDescription(), request.getVisibility(),
            request.getOwnerGroupId(), request.getScenario(), request.getDefaultQuery(), AuthContext.get()));
    }


    @PutMapping("/bases/{id}")
    @Operation(summary = "编辑知识库：名称、描述与可见范围（创建人或管理员）")
    public ApiResponse<Object> updateBase(@PathVariable Long id, @Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.update(id, request.getName(), request.getDescription(), request.getVisibility(),
            request.getOwnerGroupId(), request.getScenario(), request.getDefaultQuery()));
    }


    @PostMapping("/bases/{id}/access")
    @Operation(summary = "调整知识库可见范围与所属用户组")
    public ApiResponse<Object> updateBaseAccess(@PathVariable Long id, @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.updateAccess(id, request.getVisibility(), request.getOwnerGroupId()));
    }


    @PostMapping("/bases/{id}/archive")
    @Operation(summary = "归档知识库工作区")
    public ApiResponse<Object> archiveBase(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeBaseService.archive(id));
    }


    @PostMapping("/bases/{id}/purge")
    @Operation(summary = "清空知识库内的全部知识数据（保留知识库本身）")
    public ApiResponse<Object> purgeBase(@PathVariable Long id, @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean
        dryRun, @RequestParam(value = "purgeIndex", required = false, defaultValue = "true") boolean purgeIndex) {
        if (dryRun) {
            return ApiResponse.ok(purgeService.preview(id));
        }
        Map<String, Object> result = purgeService.purgeKnowledge(id);
        @SuppressWarnings("unchecked")
        List<String> storagePaths = (List<String>) result.remove("sourceStoragePaths");
        result.putAll(purgeService.cleanup(id, storagePaths, purgeIndex));
        return ApiResponse.ok(result);
    }

    @GetMapping("/ingestion/{kb}/runs")
    public ApiResponse<Object> ingestionRuns(@PathVariable Long kb, @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(ingestionObservabilityService.list(kb, limit));
    }


    @GetMapping("/ingestion/runs/{runId}")
    public ApiResponse<Object> ingestionRun(@PathVariable Long runId) {
        return ApiResponse.ok(ingestionObservabilityService.detail(runId));
    }


    @PostMapping(value = "/documents/upload", consumes = "multipart/form-data")
    @Operation(summary = "上传并处理知识源文档")
    public ApiResponse<Object> upload(@RequestParam("knowledgeBaseId") Long knowledgeBaseId, @RequestParam("file") MultipartFile file,
        @RequestParam(value = "authorityScore", required = false) Double authorityScore, @RequestParam(value = "permissionTags", required = false)
        String permissionTags) throws Exception {
        return ApiResponse.ok(service.upload(knowledgeBaseId, file, authorityScore, permissionTags));
    }

    /**
     * Batch ingestion: many files, one batch code, one task entry. Returns immediately so no HTTP
     * client has to stay open for the minutes several documents take; the caller polls the batch
     * status below, and each file still gets its own task, events and evidence.
     */
    @PostMapping(value = "/documents/batch-upload", consumes = "multipart/form-data")
    @Operation(summary = "批量上传多个知识源文档：返回批次号异步执行，逐文件记录成功/失败（设计 §6.2）")
    public ApiResponse<Object> batchUpload(@RequestParam("knowledgeBaseId") Long knowledgeBaseId, @RequestParam("files") List<MultipartFile> files,
        @RequestParam(value = "authorityScore", required = false) Double authorityScore, @RequestParam(value = "permissionTags", required = false)
        String permissionTags) throws Exception {
        return ApiResponse.ok(batchIngestionService.submit(knowledgeBaseId, files, authorityScore, permissionTags));
    }

    @GetMapping("/documents/batches/{kb}")
    @Operation(summary = "批量接入批次列表（按创建时间倒序）")
    public ApiResponse<Object> batchList(@PathVariable Long kb, @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(batchIngestionService.list(kb, limit));
    }


    @GetMapping("/documents/batch/{batchCode}")
    @Operation(summary = "批量接入批次状态：总进度与每个文件的文档/运行/失败原因")
    public ApiResponse<Object> batchStatus(@PathVariable String batchCode) {
        return ApiResponse.ok(batchIngestionService.status(batchCode));
    }


    @PostMapping("/documents/batch/{batchCode}/cancel")
    @Operation(summary = "取消批次：排队中的文件不再启动，已在解析的文件跑完后即停")
    public ApiResponse<Object> batchCancel(@PathVariable String batchCode) {
        return ApiResponse.ok(batchIngestionService.cancel(batchCode));
    }

    @PostMapping("/documents/batch/{batchCode}/retry")
    @Operation(summary = "重跑批次内的失败文件，成功文件不受影响")
    public ApiResponse<Object> batchRetry(@PathVariable String batchCode) {
        return ApiResponse.ok(batchIngestionService.retryFailed(batchCode));
    }


    @GetMapping("/sources/{kb}/{sourceId}/preview")
    @Operation(summary = "预览摄入的源文件（文本格式取原文，二进制回退解析文本）")
    public ApiResponse<Object> sourcePreview(@PathVariable Long kb, @PathVariable Long sourceId, @RequestParam(value = "limit", required = false)
        Integer limit, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.sourcePreview(kb, sourceId, limit, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/sources/{kb}/{sourceId}/file")
    @Operation(summary = "下载/在线打开摄入的源文件原件")
    public ResponseEntity<Resource> sourceFile(@PathVariable Long kb, @PathVariable Long sourceId, @RequestHeader(value =
        "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        com.jh.knowledgeos.domain.SourceFile file = service.sourceFile(kb, sourceId, aclContextResolver.resolve(permissionTags));
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
            inlineDisposition(file.getName())).contentType(MediaType.parseMediaType(file.getContentType())).body(file.getResource());
    }

    private String inlineDisposition(String name) {
        String safe = name == null ? "source" : name.replace("\"", "'");
        String encoded;
        try {
            encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException ex) {
            // UTF-8 is always present; keep an ASCII fallback so the header stays valid.
            encoded = safe.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
        return "inline; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }

    @PostMapping(value = "/okf/inspect", consumes = "multipart/form-data")
    @Operation(summary = "校验并规范化 OKF v0.2 知识包")
    public ApiResponse<Object> inspectOkf(@RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(aiClient.inspectOkf(file));
    }
}
