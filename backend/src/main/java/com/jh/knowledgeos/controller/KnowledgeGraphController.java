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
public class KnowledgeGraphController {
    private final KnowledgeService service;
    private final AclContextResolver aclContextResolver;
    private final AclAuditService aclAuditService;

    public KnowledgeGraphController(KnowledgeService service, AclContextResolver aclContextResolver, AclAuditService aclAuditService) {
        this.service = service;
        this.aclContextResolver = aclContextResolver;
        this.aclAuditService = aclAuditService;
    }


    @GetMapping("/graph/{kb}/{code}")
    public ApiResponse<Object> graph(@PathVariable Long kb, @PathVariable String code, @RequestParam(value = "depth", required = false) Integer depth,
        @RequestParam(value = "limit", required = false) Integer limit, @RequestHeader(value = "X-Authenticated-Permission-Tags", required = false)
        String permissionTags) {
        return ApiResponse.ok(service.graph(kb, code, aclContextResolver.resolve(permissionTags), depth, limit));
    }

    /**
     * The workbench shows the space before any object is picked, so it needs a picture of the
     * whole knowledge base rather than a neighborhood: the most connected objects and the
     * relations between them, with the size of the full graph reported alongside.
     */
    @GetMapping("/graph/{kb}/overview")
    @Operation(summary = "知识库关系视图概览：最关联对象与其关系")
    public ApiResponse<Object> graphOverview(@PathVariable Long kb, @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.graphOverview(kb, limit));
    }


    @GetMapping("/objects/{kb}/{type}/{code}")
    @Operation(summary = "知识对象详情：Card / Evidence / Conflict / Review / Graph")
    public ApiResponse<Object> knowledgeDetail(@PathVariable Long kb, @PathVariable String type, @PathVariable String code, @RequestHeader(value =
        "X-Authenticated-Permission-Tags", required = false) String permissionTags) {
        java.util.List<String> tags = aclContextResolver.resolve(permissionTags);
        Object result = service.knowledgeDetail(kb, type.toUpperCase(), code, tags);
        aclAuditService.record(kb, type.toUpperCase(), code, "READ_DETAIL", result == null ? "DENY" : "ALLOW", tags, result == null ?
            "ACL_FILTERED_OR_NOT_FOUND" : "ACL_MATCH");
        return ApiResponse.ok(result);
    }

    @GetMapping("/capabilities")
    public ApiResponse<Object> capabilities() {
        return ApiResponse.ok(service.capabilities());
    }
}
