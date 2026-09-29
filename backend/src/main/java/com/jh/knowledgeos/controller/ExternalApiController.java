package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.dto.ExternalIngestRequest;
import com.jh.knowledgeos.dto.ExternalQueryRequest;
import com.jh.knowledgeos.exception.RateLimitException;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.ExternalKnowledgeFacade;
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
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Versioned RESTful surface for external business systems.
 *
 * <p>Every endpoint is the console's capability published under a stable contract: an API key
 * decides which knowledge base and which verbs are reachable, and each call is audited under
 * the same trace id that the answer carries.</p>
 */
@RestController
@RequestMapping("/api/v1/external")
@CrossOrigin
@Tag(name = "External RESTful", description = "对外知识服务：版本化 RESTful 接口（API Key 鉴权）")
public class ExternalApiController {

    private final ExternalAccessService accessService;

    private final ExternalKnowledgeFacade facade;

    private final ExternalAuditService auditService;

    private final KnowledgeProperties.External properties;

    public ExternalApiController(ExternalAccessService accessService, ExternalKnowledgeFacade facade, ExternalAuditService auditService,
        KnowledgeProperties properties) {
        this.accessService = accessService;
        this.facade = facade;
        this.auditService = auditService;
        this.properties = properties.external();
    }

    @GetMapping("/bases")
    @Operation(summary = "列出当前 API Key 可见的知识库")
    public ApiResponse<Object> bases(HttpServletRequest request) throws Exception {
        return call(request, "GET /bases", null, (key, kb) -> facade.bases(key.getKnowledgeBaseId()));
    }

    @PostMapping("/query")
    @Operation(summary = "可追溯知识问答：返回答案、引用与 queryId")
    public ApiResponse<Object> query(HttpServletRequest request, @Valid @RequestBody ExternalQueryRequest body) throws Exception {
        return call(request, "POST /query", body.getKnowledgeBaseId(), (key, kb) -> facade.query(kb, body.getQuestion(), body.getTopK(),
            body.getPermissionTags()));
    }

    @PostMapping("/search")
    @Operation(summary = "只检索不生成答案：返回候选、重排结果与引用")
    public ApiResponse<Object> search(HttpServletRequest request, @Valid @RequestBody ExternalQueryRequest body) throws Exception {
        return call(request, "POST /search", body.getKnowledgeBaseId(), (key, kb) -> facade.search(kb, body.getQuestion(), body.getTopK(),
            body.getPermissionTags()));
    }

    @GetMapping("/cards")
    @Operation(summary = "知识卡片清单")
    public ApiResponse<Object> cards(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long kb) throws Exception {
        return call(request, "GET /cards", kb, (key, resolved) -> facade.cards(resolved, null));
    }

    @GetMapping("/cards/{code}")
    @Operation(summary = "知识卡片详情（含证据链）")
    public ApiResponse<Object> card(HttpServletRequest request, @PathVariable String code, @RequestParam(value = "knowledgeBaseId", required = false)
        Long kb) throws Exception {
        return call(request, "GET /cards/{code}", kb, (key, resolved) -> facade.objectDetail(resolved, "CARD", code, null));
    }

    @GetMapping("/claims")
    @Operation(summary = "断言清单")
    public ApiResponse<Object> claims(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long kb) throws Exception {
        return call(request, "GET /claims", kb, (key, resolved) -> facade.claims(resolved, null));
    }

    @GetMapping("/claims/{code}")
    @Operation(summary = "断言详情")
    public ApiResponse<Object> claim(HttpServletRequest request, @PathVariable String code, @RequestParam(value = "knowledgeBaseId", required = false)
        Long kb) throws Exception {
        return call(request, "GET /claims/{code}", kb, (key, resolved) -> facade.objectDetail(resolved, "CLAIM", code, null));
    }

    @GetMapping("/evidence/{code}")
    @Operation(summary = "证据原文与出处")
    public ApiResponse<Object> evidence(HttpServletRequest request, @PathVariable String code, @RequestParam(value = "knowledgeBaseId", required =
        false) Long kb) throws Exception {
        return call(request, "GET /evidence/{code}", kb, (key, resolved) -> facade.objectDetail(resolved, "EVIDENCE", code, null));
    }

    @GetMapping("/graph/{code}")
    @Operation(summary = "实体 / 知识对象的图谱邻域")
    public ApiResponse<Object> graph(HttpServletRequest request, @PathVariable String code, @RequestParam(value = "knowledgeBaseId", required = false)
        Long kb, @RequestParam(value = "depth", required = false) Integer depth, @RequestParam(value = "limit", required = false) Integer limit)
        throws Exception {
        return call(request, "GET /graph/{code}", kb, (key, resolved) -> facade.graph(resolved, code, depth, limit, null));
    }

    @GetMapping("/queries/{queryId}")
    @Operation(summary = "问答链路回放：按 queryId 取回完整检索与生成轨迹")
    public ApiResponse<Object> trace(HttpServletRequest request, @PathVariable String queryId) throws Exception {
        return call(request, "GET /queries/{queryId}", null, (key, kb) -> facade.trace(queryId));
    }

    @GetMapping("/overview")
    @Operation(summary = "知识库概览计数")
    public ApiResponse<Object> overview(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long kb) throws Exception {
        return call(request, "GET /overview", kb, (key, resolved) -> facade.overview(resolved, null));
    }

    @PostMapping("/documents/text")
    @Operation(summary = "文本接入：走与控制台上传一致的解析与摄入管道")
    public ApiResponse<Object> ingestText(HttpServletRequest request, @Valid @RequestBody ExternalIngestRequest body) throws Exception {
        return call(request, "POST /documents/text", body.getKnowledgeBaseId(), (key, kb) -> {
            accessService.assertScope(key, ApiKey.SCOPE_INGEST);
            List<String> tags = body.getPermissionTags();
            return facade.ingestText(kb, body.getTitle(), body.getText(), tags == null || tags.isEmpty() ? null : String.join(",", tags));
        });
    }

    /**
     * One gate for the whole channel: enabled, credential, scope, quota, then audit.
     */
    private ApiResponse<Object> call(HttpServletRequest request, String method, Long requestedKb, ExternalCall body) throws Exception {
        long start = System.currentTimeMillis();
        ApiKey key = null;
        // Read once, then hand it to every outcome below. Messages that "缺少 knowledgeBaseId" are
        // only actionable next to the parameters the caller actually sent, so failures carry the
        // same payload successes do.
        Map<String, Object> params = auditService.requestParamsOf(request);
        try {
            if (!properties.isEnabled()) {
                throw new SecurityException("对外接口服务已关闭");
            }
            key = accessService.authenticate(request);
            Long kb = requestedKb == null ? null : accessService.resolveKnowledgeBase(key, requestedKb);
            Object data = body.run(key, kb);
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, kb, 200, System.currentTimeMillis() - start, null, params, data);
            return ApiResponse.ok(data);
        } catch (UnauthorizedException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 401, System.currentTimeMillis() - start, e.getMessage(), params, null);
            throw e;
        } catch (SecurityException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 403, System.currentTimeMillis() - start, e.getMessage(), params, null);
            throw e;
        } catch (RateLimitException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 429, System.currentTimeMillis() - start, e.getMessage(), params, null);
            throw e;
        } catch (IllegalArgumentException e) {
            // 调用方漏传 knowledgeBaseId 或参数非法属于 400，不应记成服务端故障。
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 400, System.currentTimeMillis() - start, e.getMessage(), params, null);
            throw e;
        } catch (Exception e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 500, System.currentTimeMillis() - start, e.getMessage(), params, null);
            throw e;
        }
    }

    private interface ExternalCall {

        Object run(ApiKey key, Long knowledgeBaseId) throws Exception;
    }
}
