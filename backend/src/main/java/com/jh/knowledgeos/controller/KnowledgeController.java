package com.jh.knowledgeos.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.dto.AdjudicateRequest;
import com.jh.knowledgeos.dto.ConflictResolveRequest;
import com.jh.knowledgeos.dto.ExtractRequest;
import com.jh.knowledgeos.dto.QueryRequest;
import com.jh.knowledgeos.dto.QueryResponse;
import com.jh.knowledgeos.dto.KnowledgeBaseRequest;
import com.jh.knowledgeos.service.AclContextResolver;
import com.jh.knowledgeos.service.AclAuditService;
import com.jh.knowledgeos.service.AclRebuildOrchestrator;
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
import java.util.List;
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
    private final AclContextResolver aclContextResolver;
    private final AclAuditService aclAuditService;
    private final IndexOutboxService outboxService;
    private final AiClient aiClient;
    private final IndexSchemaService indexSchemaService;
    private final AclRebuildOrchestrator aclRebuildOrchestrator;
    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestionObservabilityService ingestionObservabilityService;
    private final KnowledgeLifecycleService knowledgeLifecycleService;
    private final EntityGovernanceService entityGovernanceService;
    private final KnowledgeBasePurgeService purgeService;

    public KnowledgeController(
            KnowledgeService service,
            ObjectMapper objectMapper,
            AclContextResolver aclContextResolver,
            AclAuditService aclAuditService,
            IndexOutboxService outboxService,
            AiClient aiClient,
            IndexSchemaService indexSchemaService,
            AclRebuildOrchestrator aclRebuildOrchestrator,
            KnowledgeBaseService knowledgeBaseService,
            IngestionObservabilityService ingestionObservabilityService,
            KnowledgeLifecycleService knowledgeLifecycleService,
            EntityGovernanceService entityGovernanceService,
            KnowledgeBasePurgeService purgeService) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.aclContextResolver = aclContextResolver;
        this.aclAuditService = aclAuditService;
        this.outboxService = outboxService;
        this.aiClient = aiClient;
        this.indexSchemaService = indexSchemaService;
        this.aclRebuildOrchestrator = aclRebuildOrchestrator;
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestionObservabilityService = ingestionObservabilityService;
        this.knowledgeLifecycleService = knowledgeLifecycleService;
        this.entityGovernanceService = entityGovernanceService;
        this.purgeService = purgeService;
    }

    @PostMapping("/lifecycle/{kb}/expire")
    @Operation(summary = "失效到期的知识对象（设计 V1.1 §5.5 / §17 有效期到期自动失效）")
    public ApiResponse<Object> expireDueKnowledge(
            @PathVariable Long kb,
            @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean dryRun) {
        return ApiResponse.ok(knowledgeLifecycleService.expireDueClaims(kb, dryRun));
    }

    @GetMapping("/lifecycle/{kb}/summary")
    @Operation(summary = "知识生命周期看板：生效/过期/替代分布与即将到期数量（V1.0 §5.8）")
    public ApiResponse<Object> lifecycleSummary(
            @PathVariable Long kb,
            @RequestParam(value = "withinDays", required = false) Integer withinDays) {
        return ApiResponse.ok(knowledgeLifecycleService.summary(kb, withinDays));
    }

    @GetMapping("/lifecycle/{kb}/expiring")
    @Operation(summary = "即将过期的断言清单，用于复核续期（V1.0 §5.8）")
    public ApiResponse<Object> lifecycleExpiring(
            @PathVariable Long kb,
            @RequestParam(value = "withinDays", required = false) Integer withinDays) {
        return ApiResponse.ok(knowledgeLifecycleService.expiringClaims(kb, withinDays));
    }

    @GetMapping("/entities/{kb}")
    @Operation(summary = "实体与别名词典：查看归一结果（V1.0 §5.4.1）")
    public ApiResponse<Object> entities(@PathVariable Long kb) {
        return ApiResponse.ok(entityGovernanceService.entities(kb));
    }

    @GetMapping("/entities/{kb}/{entityCode}")
    @Operation(summary = "实体详情：别名、影响面与归一依据（V1.0 §5.4.1）")
    public ApiResponse<Object> entityDetail(@PathVariable Long kb, @PathVariable String entityCode) {
        return ApiResponse.ok(entityGovernanceService.detail(kb, entityCode));
    }

    @PostMapping("/entities/{kb}/{entityCode}/aliases")
    @Operation(summary = "为实体补充人工别名（V1.0 §5.4.1）")
    public ApiResponse<Object> addEntityAlias(
            @PathVariable Long kb, @PathVariable String entityCode,
            @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.addAlias(
                kb, entityCode,
                request.get("alias") == null ? null : String.valueOf(request.get("alias")),
                request.get("aliasType") == null ? null : String.valueOf(request.get("aliasType")),
                request.get("confidence") instanceof Number
                        ? ((Number) request.get("confidence")).doubleValue() : null));
    }

    @PostMapping("/entities/{kb}/{entityCode}/aliases/remove")
    @Operation(summary = "解绑实体的错误别名（V1.0 §5.4.1）")
    public ApiResponse<Object> removeEntityAlias(
            @PathVariable Long kb, @PathVariable String entityCode,
            @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.removeAlias(
                kb, entityCode,
                request.get("alias") == null ? null : String.valueOf(request.get("alias"))));
    }

    @PostMapping("/entities/{kb}/merge")
    @Operation(summary = "人工确认实体合并（V1.0 §5.4.1）")
    public ApiResponse<Object> mergeEntity(@PathVariable Long kb, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.merge(
                kb,
                request.get("from") == null ? null : String.valueOf(request.get("from")),
                request.get("to") == null ? null : String.valueOf(request.get("to"))));
    }

    @PostMapping("/entities/{kb}/{entityCode}/status")
    @Operation(summary = "调整实体状态，可将误合并的实体恢复为 ACTIVE（V1.0 §5.4.1）")
    public ApiResponse<Object> updateEntityStatus(
            @PathVariable Long kb, @PathVariable String entityCode,
            @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(entityGovernanceService.updateStatus(
                kb, entityCode,
                request.get("status") == null ? null : String.valueOf(request.get("status"))));
    }

    @PostMapping("/lifecycle/{kb}/supersede")
    @Operation(summary = "用新版本替代旧版本知识对象（设计 V1.1 §5.5 版本管理）")
    public ApiResponse<Object> supersedeKnowledge(@PathVariable Long kb, @RequestBody Map<String, Object> request) {
        return ApiResponse.ok(knowledgeLifecycleService.supersede(
                kb,
                String.valueOf(request.get("objectType")),
                String.valueOf(request.get("oldObjectCode")),
                String.valueOf(request.get("newObjectCode"))));
    }

    @GetMapping("/lifecycle/sources/{sourceId}/impact")
    @Operation(summary = "来源删除/撤销的影响分析（设计 V1.1 §12）")
    public ApiResponse<Object> sourceImpact(@PathVariable Long sourceId) {
        return ApiResponse.ok(knowledgeLifecycleService.sourceImpact(sourceId));
    }

    @GetMapping("/bases")
    @Operation(summary = "列出知识库工作区")
    public ApiResponse<Object> bases() { return ApiResponse.ok(knowledgeBaseService.list()); }

    @PostMapping("/bases")
    @Operation(summary = "创建知识库工作区")
    public ApiResponse<Object> createBase(@Valid @RequestBody KnowledgeBaseRequest request) {
        return ApiResponse.ok(knowledgeBaseService.create(request.getName(), request.getDescription()));
    }

    @PostMapping("/bases/{id}/archive")
    @Operation(summary = "归档知识库工作区")
    public ApiResponse<Object> archiveBase(@PathVariable Long id) {
        return ApiResponse.ok(knowledgeBaseService.archive(id));
    }

    @PostMapping("/bases/{id}/purge")
    @Operation(summary = "清空知识库内的全部知识数据（保留知识库本身）")
    public ApiResponse<Object> purgeBase(
            @PathVariable Long id,
            @RequestParam(value = "dryRun", required = false, defaultValue = "false") boolean dryRun,
            @RequestParam(value = "purgeIndex", required = false, defaultValue = "true") boolean purgeIndex) {
        if (dryRun) return ApiResponse.ok(purgeService.preview(id));
        Map<String, Object> result = purgeService.purgeKnowledge(id);
        @SuppressWarnings("unchecked")
        List<String> storagePaths = (List<String>) result.remove("sourceStoragePaths");
        result.putAll(purgeService.cleanup(id, storagePaths, purgeIndex));
        return ApiResponse.ok(result);
    }

    @GetMapping("/ingestion/{kb}/runs")
    public ApiResponse<Object> ingestionRuns(@PathVariable Long kb,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(ingestionObservabilityService.list(kb, limit));
    }

    @GetMapping("/ingestion/runs/{runId}")
    public ApiResponse<Object> ingestionRun(@PathVariable Long runId) {
        return ApiResponse.ok(ingestionObservabilityService.detail(runId));
    }

    @PostMapping(value = "/documents/upload", consumes = "multipart/form-data")
    @Operation(summary = "上传并处理知识源文档")
    public ApiResponse<Object> upload(
            @RequestParam("knowledgeBaseId") Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "authorityScore", required = false) Double authorityScore,
            @RequestParam(value = "permissionTags", required = false) String permissionTags)
            throws Exception {
        return ApiResponse.ok(service.upload(knowledgeBaseId, file, authorityScore, permissionTags));
    }

    @GetMapping("/sources/{kb}/{sourceId}/preview")
    @Operation(summary = "预览摄入的源文件（文本格式取原文，二进制回退解析文本）")
    public ApiResponse<Object> sourcePreview(@PathVariable Long kb, @PathVariable Long sourceId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.sourcePreview(kb, sourceId, limit, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/sources/{kb}/{sourceId}/file")
    @Operation(summary = "下载/在线打开摄入的源文件原件")
    public ResponseEntity<Resource> sourceFile(@PathVariable Long kb, @PathVariable Long sourceId,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        com.jh.knowledgeos.domain.SourceFile file =
                service.sourceFile(kb, sourceId, aclContextResolver.resolve(permissionTags));
        if (file == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, inlineDisposition(file.getName()))
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(file.getResource());
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

    @GetMapping("/overview/{kb}")
    @Operation(summary = "知识库工作台概览")
    public ApiResponse<Object> overview(@PathVariable Long kb,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.overview(kb, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/sources/{kb}")
    public ApiResponse<Object> sources(@PathVariable Long kb,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.sources(kb, aclContextResolver.resolve(permissionTags)));
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
    public ApiResponse<QueryResponse> query(
            @Valid @RequestBody QueryRequest request,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) throws Exception {
        request.setPermissionTags(aclContextResolver.resolve(permissionTags));
        return ApiResponse.ok(
                objectMapper.convertValue(service.query(request.toMap()), QueryResponse.class));
    }

    @GetMapping("/cards/{kb}")
    public ApiResponse<Object> cards(@PathVariable Long kb,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.cards(kb, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/claims/{kb}")
    public ApiResponse<Object> claims(@PathVariable Long kb,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.claims(kb, aclContextResolver.resolve(permissionTags)));
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
    public ApiResponse<Object> graph(@PathVariable Long kb, @PathVariable String code,
            @RequestParam(value = "depth", required = false) Integer depth,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.graph(kb, code, aclContextResolver.resolve(permissionTags), depth, limit));
    }

    @GetMapping("/objects/{kb}/{type}/{code}")
    @Operation(summary = "知识对象详情：Card / Evidence / Conflict / Review / Graph")
    public ApiResponse<Object> knowledgeDetail(@PathVariable Long kb, @PathVariable String type,
            @PathVariable String code,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        java.util.List<String> tags = aclContextResolver.resolve(permissionTags);
        Object result = service.knowledgeDetail(kb, type.toUpperCase(), code, tags);
        aclAuditService.record(kb, type.toUpperCase(), code, "READ_DETAIL",
                result == null ? "DENY" : "ALLOW", tags,
                result == null ? "ACL_FILTERED_OR_NOT_FOUND" : "ACL_MATCH");
        return ApiResponse.ok(result);
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

    @GetMapping("/index/outbox/{kb}/page")
    @Operation(summary = "分页查询索引 Outbox 事件")
    public ApiResponse<Object> outboxPage(
            @PathVariable Long kb,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size) {
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

    @GetMapping("/content-unit/{kb}/{contentUnitId}")
    @Operation(summary = "按检索 ContentUnit 定位原文")
    public ApiResponse<Object> contentUnitDetail(
            @PathVariable Long kb, @PathVariable Long contentUnitId,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.contentUnitDetail(kb, contentUnitId, aclContextResolver.resolve(permissionTags)));
    }

    @GetMapping("/evidence/{kb}/{evidenceCode}")
    @Operation(summary = "查看证据原文与来源定位")
    public ApiResponse<Object> evidenceDetail(
            @PathVariable Long kb, @PathVariable String evidenceCode,
            @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        return ApiResponse.ok(service.evidenceDetail(kb, evidenceCode, aclContextResolver.resolve(permissionTags)));
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
    public ApiResponse<Object> answerQualificationSummary(
            @PathVariable Long kb,
            @RequestParam(value = "hours", required = false) Integer hours) {
        return ApiResponse.ok(service.answerQualificationSummary(kb, hours));
    }

    @GetMapping("/traces/{kb}")
    @Operation(summary = "查询知识库问答历史")
    public ApiResponse<Object> queryHistory(
            @PathVariable Long kb,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.queryHistory(kb, limit));
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
    public ApiResponse<Object> listAclRebuilds(@PathVariable Long kb,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(aclRebuildOrchestrator.list(kb, limit));
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
