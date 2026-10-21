package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.config.KnowledgeProperties;
import com.jh.knowledgeos.domain.ApiCallAudit;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.service.ExternalAccessService;
import com.jh.knowledgeos.service.ExternalAuditService;
import com.jh.knowledgeos.service.ExternalStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.jh.knowledgeos.service.PlatformEventHub;
import com.jh.knowledgeos.service.PlatformEventVisibilityService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Server-Sent Events surface: progressive answers and workspace activity push.
 *
 * <p>SSE is used rather than WebSocket because it is plain HTTP: it survives gateways and
 * reuses the very same API-key authentication, and clients reconnect on their own.</p>
 */
@RestController
@RequestMapping("/api/v1/external/stream")
@Tag(name = "External SSE", description = "对外 SSE 推送：流式问答与知识库事件订阅；跨域由 EmbedCorsFilter 按凭证白名单统一处理")
public class ExternalStreamController {

    private final ExternalAccessService accessService;

    private final ExternalStreamService streamService;

    private final ExternalAuditService auditService;

    private final PlatformEventHub hub;

    private final PlatformEventVisibilityService visibility;

    private final KnowledgeProperties.External properties;

    public ExternalStreamController(ExternalAccessService accessService, ExternalStreamService streamService, ExternalAuditService auditService,
        PlatformEventHub hub, PlatformEventVisibilityService visibility, KnowledgeProperties properties) {
        this.accessService = accessService;
        this.streamService = streamService;
        this.auditService = auditService;
        this.hub = hub;
        this.visibility = visibility;
        this.properties = properties.external();
    }

    /**
     * How the answer body travels: {@code stream} (default) pushes it slice by slice as the model
     * writes; {@code final} drops the slices and delivers it whole inside {@code done}, so the
     * stream carries retrieval progress only. A client that renders the finished answer — the way
     * the console renders its blocking query — wants {@code final}: what a slice holds is raw model
     * text, without the format processing (citation superscripts, the "引用原文" section).
     */
    private static final String CONTENT_FINAL = "final";

    @GetMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式问答：stage 推送检索过程；content=stream 时逐片推送 delta，content=final 时答案在 done 里整体交付")
    public SseEmitter streamQuery(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId,
        @RequestParam("question") String question, @RequestParam(value = "topK", required = false) Integer topK,
        @RequestParam(value = "answerFormat", required = false) String answerFormat,
        @RequestParam(value = "content", required = false) String content,
        @RequestParam(value = "minScore", required = false) Double minScore,
        @RequestParam(value = "useMemory", required = false) Boolean useMemory) {
        boolean wholeAnswer = CONTENT_FINAL.equalsIgnoreCase(content);
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        resolved.put("question", question);
        resolved.put("topK", topK);
        resolved.put("answerFormat", answerFormat);
        // Resolved, not echoed: an unrecognised value silently falls back to "stream", and the
        // client's rendering depends on which one it got — the handshake has to say which.
        resolved.put("content", wholeAnswer ? CONTENT_FINAL : "stream");
        resolved.put("minScore", minScore);
        ApiKey key = open(request, "/stream/query", knowledgeBaseId, handshake("/stream/query", resolved));
        // minScore 与 /query 同一语义：0~1 的最低相关度。校验放在凭证闸门之后——放在之前的话，
        // 一个不带凭证的请求也能靠"400 vs 401"探出参数规则；同时也让审计记下越界取值。
        if (minScore != null && (minScore < 0.0d || minScore > 1.0d)) {
            throw new IllegalArgumentException("minScore 必须在 0~1 之间");
        }
        Long kb = accessService.resolveKnowledgeBase(key, knowledgeBaseId);
        accessService.assertScope(key, ApiKey.SCOPE_READ);
        SseEmitter emitter = new SseEmitter(0L);
        streamService.streamQuery(key, kb, question, topK, answerFormat, wholeAnswer, minScore,
            Boolean.TRUE.equals(useMemory), emitter);
        return emitter;
    }

    /**
     * Knowledge-base event subscription.
     *
     * <p>Events now come from the durable platform bus: each occurrence carries its type, actor,
     * target object and full detail, and reconnects can resume from the last delivered id through
     * {@code Last-Event-ID}. {@code legacy=true} keeps the old counter-polling behaviour for
     * clients that depend on its {@code ingestion} / {@code knowledge} frames.</p>
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "知识库事件订阅：知识库/卡片/检索/权限等事件，支持 types、断线续传")
    public SseEmitter streamEvents(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId,
        @RequestParam(value = "types", required = false) String types, @RequestParam(value = "sinceEventId", required = false) Long sinceEventId,
        @RequestParam(value = "legacy", defaultValue = "false") boolean legacy) {
        Long since = sinceEventId != null ? sinceEventId : lastEventId(request);
        Map<String, Object> resolved = new LinkedHashMap<String, Object>();
        resolved.put("types", splitTypes(types));
        resolved.put("sinceEventId", since);
        resolved.put("legacy", legacy);
        ApiKey key = open(request, "/stream/events", knowledgeBaseId, handshake("/stream/events", resolved));
        Long kb = accessService.resolveKnowledgeBase(key, knowledgeBaseId);
        accessService.assertScope(key, ApiKey.SCOPE_READ);
        if (legacy) {
            SseEmitter emitter = new SseEmitter(0L);
            streamService.streamEvents(key, kb, emitter);
            return emitter;
        }
        return hub.subscribe("key:" + key.getId(), kb, splitTypes(types), since, visibility.forApiKey(key, kb));
    }

    /**
     * Last-Event-ID is the browser's resume handle; the parameter is there for curl-style clients.
     */
    private Long lastEventId(HttpServletRequest request) {
        String header = request.getHeader("Last-Event-ID");
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Set<String> splitTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> values = new HashSet<String>();
        for (String part : raw.split(",")) {
            String token = part.trim().toUpperCase();
            if (!token.isEmpty()) {
                values.add(token);
            }
        }
        return values;
    }

    /**
     * Same credential gate as REST, but audited once when the stream opens.
     */
    private ApiKey open(HttpServletRequest request, String method, Long knowledgeBaseId, Map<String, Object> responseData) {
        if (!properties.isEnabled() || !properties.isSseEnabled()) {
            throw new SecurityException("SSE 推送服务已关闭");
        }
        long start = System.currentTimeMillis();
        // Embed tokens are accepted here as well: this is the endpoint the chat widget uses, and
        // its credential arrives from a browser rather than from a server.
        ApiKey key = accessService.authenticateEmbed(request);
        // The stream itself is not captured — it never ends and would outgrow any column — but the
        // subscription parameters say which events the caller asked to follow, and the handshake
        // result says what the subscription actually resolved to (types, resume point). Without the
        // second half a row can only prove a stream was opened, never what it was opened for.
        Map<String, Object> params = auditService.requestParamsOf(request);
        auditService.record(key, ApiCallAudit.CHANNEL_SSE, method, knowledgeBaseId, 200, System.currentTimeMillis() - start, "stream opened", params,
            responseData, ExternalAccessService.embedTokenOf(request));
        return key;
    }

    /**
     * What the caller got back from the handshake: the stream it is now attached to and the
     * parameters the server resolved for it.
     */
    private Map<String, Object> handshake(String stream, Map<String, Object> resolved) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("result", "STREAM_OPENED");
        payload.put("stream", stream);
        payload.putAll(resolved);
        return payload;
    }
}
