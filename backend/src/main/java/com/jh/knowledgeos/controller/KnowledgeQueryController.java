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
public class KnowledgeQueryController {
    private final KnowledgeService service;
    private final ObjectMapper objectMapper;
    private final AclContextResolver aclContextResolver;
    private final KnowledgeBaseAccessService accessService;

    public KnowledgeQueryController(
        KnowledgeService service,
        ObjectMapper objectMapper,
        AclContextResolver aclContextResolver,
        KnowledgeBaseAccessService accessService) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.aclContextResolver = aclContextResolver;
        this.accessService = accessService;
    }


    @PostMapping("/retrieve")
    @Operation(summary = "纯检索：返回排序后的候选 Chunk，不生成答案（设计 §17.1）")
    public ApiResponse<Object> retrieve(@RequestBody Map<String, Object> request) throws Exception {
        return ApiResponse.ok(service.retrieve(request));
    }


    @PostMapping("/answer")
    @Operation(summary = "生成式回答：答案、引用、置信度与 traceId（设计 §17.1）")
    public ApiResponse<Object> answer(@RequestBody Map<String, Object> request) throws Exception {
        return ApiResponse.ok(service.answer(request));
    }


    @PostMapping("/query")
    @Operation(summary = "执行可追溯混合知识问答")
    public ApiResponse<QueryResponse> query(@Valid @RequestBody QueryRequest request, @RequestHeader(value = "X-Authenticated-Permission-Tags",
        required = false) String permissionTags) throws Exception {
        accessService.assertRead(request.getKnowledgeBaseId());
        request.setPermissionTags(aclContextResolver.resolve(permissionTags));
        return ApiResponse.ok(objectMapper.convertValue(service.query(request.toMap()), QueryResponse.class));
    }

    @GetMapping("/trace/{qid}")
    public ApiResponse<Object> trace(@PathVariable String qid) {
        return ApiResponse.ok(service.trace(qid));
    }


    @GetMapping("/answer-qualification/{qid}")
    @Operation(summary = "查看权威答案资格决策与 Claim 证据账本")
    public ApiResponse<Object> answerQualification(@PathVariable String qid) {
        return ApiResponse.ok(service.answerQualification(qid));
    }


    @GetMapping("/answer-qualifications/{kb}/summary")
    @Operation(summary = "统计答案资格状态和策略版本分布")
    public ApiResponse<Object> answerQualificationSummary(@PathVariable Long kb, @RequestParam(value = "hours", required = false) Integer hours) {
        return ApiResponse.ok(service.answerQualificationSummary(kb, hours));
    }


    @GetMapping("/traces/{kb}")
    @Operation(summary = "查询知识库问答历史（分页）")
    public ApiResponse<Object> queryHistory(@PathVariable Long kb,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "pageSize", required = false) Integer pageSize,
        // 兼容旧调用：早先只传 limit，现在当作 pageSize，页码默认第一页。
        @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.queryHistory(kb, page, pageSize == null ? limit : pageSize));
    }}
