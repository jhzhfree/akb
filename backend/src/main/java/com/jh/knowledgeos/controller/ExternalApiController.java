package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.KbRetrievalException;
import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.dto.ExternalIngestRequest;
import com.jh.knowledgeos.dto.ExternalQueryRequest;
import com.jh.knowledgeos.dto.MemoryCaptureRequest;
import com.jh.knowledgeos.dto.MemoryRecallRequest;
import com.jh.knowledgeos.exception.RateLimitException;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.ExternalKnowledgeFacade;
import com.jh.knowledgeos.service.AgentMemoryService;
import com.jh.knowledgeos.service.KbRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.LinkedHashMap;
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
@Tag(name = "External RESTful", description = "对外知识服务：版本化 RESTful 接口（API Key 或嵌入令牌鉴权）；跨域由 EmbedCorsFilter 统一处理")
public class ExternalApiController {

    private final ExternalAccessService accessService;

    private final ExternalKnowledgeFacade facade;

    private final ExternalAuditService auditService;

    private final AgentMemoryService memoryService;

    private final KbRetrievalService retrievals;

    private final KnowledgeProperties.External properties;

    /** 第三方检索协议用请求头指库（NovaWork standard）：数字 id 或库编码。 */
    private static final String KB_ID_HEADER = "X-Knowledge-Base-Id";

    public ExternalApiController(ExternalAccessService accessService, ExternalKnowledgeFacade facade, ExternalAuditService auditService,
        AgentMemoryService memoryService, KbRetrievalService retrievals, KnowledgeProperties properties) {
        this.accessService = accessService;
        this.facade = facade;
        this.auditService = auditService;
        this.memoryService = memoryService;
        this.retrievals = retrievals;
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
        // 记忆身份来自凭证，所以要了记忆就走带凭证的入口：不带凭证那组会在记忆注入时丢掉身份。
        return call(request, "POST /query", body.getKnowledgeBaseId(), (key, kb) -> Boolean.TRUE.equals(body.getIncludeMemory())
            ? facade.queryWithMemory(key, kb, body.getQuestion(), body.getTopK(), body.getPermissionTags(),
                body.getAnswerFormat(), body.getMinScore(), body.getResponseMode())
            : facade.query(kb, body.getQuestion(), body.getTopK(), body.getPermissionTags(),
                body.getAnswerFormat(), body.getMinScore(), body.getResponseMode()));
    }

    @PostMapping("/search")
    @Operation(summary = "只检索不生成答案：返回候选、重排结果与引用")
    public ApiResponse<Object> search(HttpServletRequest request, @Valid @RequestBody ExternalQueryRequest body) throws Exception {
        return call(request, "POST /search", body.getKnowledgeBaseId(), (key, kb) -> facade.search(kb, body.getQuestion(), body.getTopK(),
            body.getPermissionTags(), body.getMinScore(), body.getResponseMode()));
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

    @PostMapping("/memories/capture")
    @Operation(summary = "Capture：保存有来源且有 TTL 的 Agent 记忆")
    public ApiResponse<Object> captureMemory(HttpServletRequest request, @Valid @RequestBody MemoryCaptureRequest body) throws Exception {
        return call(request, "POST /memories/capture", null,
            (key, kb) -> {
                requireDirectMemoryCredential(request);
                return memoryService.capture(key, body);
            });
    }

    @PostMapping("/memories/recall")
    @Operation(summary = "Recall：按 Agent / 项目 / 任务作用域召回记忆")
    public ApiResponse<Object> recallMemories(HttpServletRequest request, @Valid @RequestBody MemoryRecallRequest body) throws Exception {
        return call(request, "POST /memories/recall", null,
            (key, kb) -> {
                requireDirectMemoryCredential(request);
                return memoryService.recall(key, body);
            });
    }

    @DeleteMapping("/memories/{memoryCode}")
    @Operation(summary = "Forget：按记忆编码立即删除")
    public ApiResponse<Object> forgetMemory(HttpServletRequest request, @PathVariable String memoryCode) throws Exception {
        return call(request, "DELETE /memories/{memoryCode}", null,
            (key, kb) -> {
                requireDirectMemoryCredential(request);
                return memoryService.forget(key, memoryCode);
            });
    }

    /**
     * 第三方检索协议（NovaWork standard）：一次召回，返回协议要的 {@code chunks[]}，
     * 调用方显式要求时再附带本系统生成的答案。
     *
     * <p>这个端点不走 {@link #call}，因为错误形状不同：协议要 {@code {code,message}} + 状态码，
     * 不是控制台那套 {@code {success,status,message}}。但门是同一道——鉴权、配额、库可见性、
     * 审计调用的都是同一批服务，不存在"第三方协议走一条更宽松的路"。</p>
     */
    @PostMapping("/kb/retrievals")
    @Operation(summary = "第三方检索协议：召回片段，可选附带答案",
        description = "请求头 X-Knowledge-Base-Id 指定知识库（数字 id 或库编码）；"
            + "请求体 includeAnswer=true 时额外返回 answer（会走答案生成，耗时更长）")
    public ResponseEntity<Object> retrievals(HttpServletRequest request,
        @RequestBody(required = false) Map<String, Object> body) {
        long start = System.currentTimeMillis();
        ApiKey key = null;
        Long kb = null;
        Object params = body == null ? new LinkedHashMap<String, Object>() : body;
        try {
            if (!properties.isEnabled()) {
                throw new SecurityException("对外接口服务已关闭");
            }
            key = accessService.authenticateEmbed(request);
            String question = textParam(body, "question");
            if (question == null) {
                throw KbRetrievalException.invalidArgument("question 不能为空");
            }
            Integer topK = topKParam(body);
            boolean withAnswer = flagParam(body, "includeAnswer");
            boolean includeMemory = flagParam(body, "includeMemory");
            accessService.assertScope(key, ApiKey.SCOPE_SEARCH);
            if (withAnswer) {
                accessService.assertScope(key, ApiKey.SCOPE_QUERY);
            }
            kb = retrievals.resolveKnowledgeBase(key, request.getHeader(KB_ID_HEADER),
                body == null ? null : body.get("knowledgeBaseId"));
            Long resolved = accessService.resolveKnowledgeBase(key, kb);
            Map<String, Object> data = retrievals.retrieve(key, resolved, question, topK, withAnswer, includeMemory);
            auditService.record(key, ApiCallAudit.CHANNEL_REST, "POST /kb/retrievals", resolved, 200,
                System.currentTimeMillis() - start, null, params, data, ExternalAccessService.embedTokenOf(request));
            return ResponseEntity.ok(data);
        } catch (UnauthorizedException e) {
            return fail(401, "UNAUTHORIZED", e, key, kb, start, params, request);
        } catch (SecurityException e) {
            return fail(403, "FORBIDDEN", e, key, kb, start, params, request);
        } catch (RateLimitException e) {
            return fail(429, "RESOURCE_EXHAUSTED", e, key, kb, start, params, request);
        } catch (KbRetrievalException e) {
            return fail(e.getStatus(), e.getCode(), e, key, kb, start, params, request);
        } catch (IllegalArgumentException e) {
            return fail(400, "INVALID_ARGUMENT", e, key, kb, start, params, request);
        } catch (Exception e) {
            return fail(500, "INTERNAL", e, key, kb, start, params, request);
        }
    }

    /** 协议形状的错误：code 给机器、message 给人，审计里记的是同一份。 */
    private ResponseEntity<Object> fail(int status, String code, Exception e, ApiKey key, Long kb, long start,
        Object params, HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("code", code);
        payload.put("message", e.getMessage());
        auditService.record(key, ApiCallAudit.CHANNEL_REST, "POST /kb/retrievals", kb, status,
            System.currentTimeMillis() - start, e.getMessage(), params, payload,
            ExternalAccessService.embedTokenOf(request));
        return ResponseEntity.status(status).body(payload);
    }

    private static String textParam(Map<String, Object> body, String name) {
        Object raw = body == null ? null : body.get(name);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    /** topK 超出内部上限取上限（协议说不视为错误），小于等于 0 才是调用方的参数问题。 */
    private static Integer topKParam(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("topK");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number)) {
            throw KbRetrievalException.invalidArgument("topK 必须是数字");
        }
        int value = ((Number)raw).intValue();
        if (value < 1) {
            throw KbRetrievalException.invalidArgument("topK 必须大于 0");
        }
        return Integer.valueOf(Math.min(value, 50));
    }

    /**
     * 默认只要片段：多一次生成就多几秒，而这类调用方是拿着片段自己去生成的。
     * 撞上它的超时不是"答案慢一点"，而是协议侧整库降级为空结果——比少给答案严重得多。
     */
    private static boolean flagParam(Map<String, Object> body, String name) {
        Object raw = body == null ? null : body.get(name);
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean) {
            return ((Boolean)raw).booleanValue();
        }
        return Boolean.parseBoolean(String.valueOf(raw).trim());
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
        boolean memoryCall = method.contains("/memories/");
        Object params = memoryCall ? memoryAuditRequest(method) : auditService.requestParamsOf(request);
        try {
            if (!properties.isEnabled()) {
                throw new SecurityException("对外接口服务已关闭");
            }
            key = accessService.authenticateEmbed(request);
            String requiredScope = requiredScope(method);
            if (requiredScope != null) {
                accessService.assertScope(key, requiredScope);
            }
            Long kb = requestedKb == null ? null : accessService.resolveKnowledgeBase(key, requestedKb);
            Object data = body.run(key, kb);
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, kb, 200, System.currentTimeMillis() - start, null, params,
                memoryCall ? memoryAuditResponse(method, data) : data, ExternalAccessService.embedTokenOf(request));
            return ApiResponse.ok(data);
        } catch (UnauthorizedException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 401, System.currentTimeMillis() - start, e.getMessage(), params,
                errorBody(401, e), ExternalAccessService.embedTokenOf(request));
            throw e;
        } catch (SecurityException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 403, System.currentTimeMillis() - start, e.getMessage(), params,
                errorBody(403, e), ExternalAccessService.embedTokenOf(request));
            throw e;
        } catch (RateLimitException e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 429, System.currentTimeMillis() - start, e.getMessage(), params,
                errorBody(429, e), ExternalAccessService.embedTokenOf(request));
            throw e;
        } catch (IllegalArgumentException e) {
            // 调用方漏传 knowledgeBaseId 或参数非法属于 400，不应记成服务端故障。
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 400, System.currentTimeMillis() - start, e.getMessage(), params,
                errorBody(400, e), ExternalAccessService.embedTokenOf(request));
            throw e;
        } catch (Exception e) {
            auditService.record(key, ApiCallAudit.CHANNEL_REST, method, requestedKb, 500, System.currentTimeMillis() - start, e.getMessage(), params,
                errorBody(500, e), ExternalAccessService.embedTokenOf(request));
            throw e;
        }
    }

    /**
     * The answer a failed call really returned, not just the status code.
     *
     * <p>A rejection is the call an operator has to explain to the caller, so the audit row carries
     * the same body the caller received: the code it can be quoted back by, and the message that
     * says which parameter was missing. Nothing here is secret — it is what the caller already saw.</p>
     */
    private Map<String, Object> errorBody(int status, Exception e) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("success", Boolean.FALSE);
        payload.put("status", status);
        payload.put("message", e.getMessage());
        return payload;
    }

    private Map<String, Object> memoryAuditRequest(String method) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("operation", method);
        payload.put("payloadOmitted", Boolean.TRUE);
        return payload;
    }

    private Map<String, Object> memoryAuditResponse(String method, Object response) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("operation", method);
        payload.put("contentOmitted", Boolean.TRUE);
        if (response instanceof Map) {
            Map<?, ?> data = (Map<?, ?>) response;
            if (data.containsKey("count")) { payload.put("count", data.get("count")); }
            if (data.containsKey("accepted")) { payload.put("accepted", data.get("accepted")); }
            if (data.containsKey("forgotten")) { payload.put("forgotten", data.get("forgotten")); }
        }
        return payload;
    }

    private void requireDirectMemoryCredential(HttpServletRequest request) {
        if (ExternalAccessService.embedTokenOf(request) != null) {
            throw new SecurityException("记忆接口不接受嵌入令牌，请使用服务端 API Key");
        }
    }

    private String requiredScope(String method) {
        if ("GET /bases".equals(method)) {
            return ApiKey.SCOPE_LIST;
        }
        if ("POST /query".equals(method)) {
            return ApiKey.SCOPE_QUERY;
        }
        if ("POST /search".equals(method)) {
            return ApiKey.SCOPE_SEARCH;
        }
        if ("GET /cards".equals(method) || "GET /cards/{code}".equals(method) || "GET /claims".equals(method)
            || "GET /claims/{code}".equals(method) || "GET /evidence/{code}".equals(method) || "GET /graph/{code}".equals(method)
            || "GET /queries/{queryId}".equals(method) || "GET /overview".equals(method)) {
            return ApiKey.SCOPE_READ_OBJECT;
        }
        return null;
    }

    private interface ExternalCall {

        Object run(ApiKey key, Long knowledgeBaseId) throws Exception;
    }
}
