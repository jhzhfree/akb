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
public class KnowledgeReadController {
    private final KnowledgeService service;
    private final AclContextResolver aclContextResolver;

    public KnowledgeReadController(KnowledgeService service, AclContextResolver aclContextResolver) {
        this.service = service;
        this.aclContextResolver = aclContextResolver;
    }


    @GetMapping("/overview/{kb}")
    @Operation(summary = "知识库工作台概览")
    public ApiResponse<Object> overview(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String
        permissionTags) {
        return ApiResponse.ok(service.overview(kb, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/sources/{kb}")
    public ApiResponse<Object> sources(@PathVariable Long kb, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String
        permissionTags) {
        return ApiResponse.ok(service.sources(kb, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/documents/{kb}")
    public ApiResponse<Object> documents(@PathVariable Long kb) {
        return ApiResponse.ok(service.documents(kb));
    }


    @PostMapping("/documents/{documentId}/retry")
    @Operation(summary = "重跑失败/索引失效的文档：从原始文件重新解析，旧 Chunk 版本保留为 SUPERSEDED（设计 §16.1）")
    public ApiResponse<Object> retryDocument(@PathVariable Long documentId) throws Exception {
        return ApiResponse.ok(service.retryDocument(documentId));
    }


    @GetMapping("/documents/{documentId}/chunks")
    @Operation(summary = "文档 Chunk 清单与元数据：切分策略、页码/字符区间、token 数、revision（设计 §7.2）")
    public ApiResponse<Object> documentChunks(@PathVariable Long documentId) {
        return ApiResponse.ok(service.documentChunks(documentId));
    }


    @GetMapping("/lineage/{kb}/{objectCode}")
    @Operation(summary = "知识对象溯源链：对象 → Evidence → Chunk → Document → Source（设计 §9.4）")
    public ApiResponse<Object> lineage(@PathVariable Long kb, @PathVariable String objectCode, @RequestParam(value = "objectType", required = false)
        String objectType) {
        return ApiResponse.ok(service.lineage(kb, objectType, objectCode));
    }

    @GetMapping("/content-unit/{kb}/{contentUnitId}")
    @Operation(summary = "按检索 ContentUnit 定位原文")
    public ApiResponse<Object> contentUnitDetail(@PathVariable Long kb, @PathVariable Long contentUnitId, @RequestHeader(value =
        "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.contentUnitDetail(kb, contentUnitId, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/evidence/{kb}/{evidenceCode}")
    @Operation(summary = "查看证据原文与来源定位")
    public ApiResponse<Object> evidenceDetail(@PathVariable Long kb, @PathVariable String evidenceCode, @RequestHeader(value =
        "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.evidenceDetail(kb, evidenceCode, aclContextResolver.resolve(permissionTags)));
    }
}
