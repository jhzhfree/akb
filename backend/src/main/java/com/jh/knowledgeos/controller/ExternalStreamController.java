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
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Server-Sent Events surface: progressive answers and workspace activity push.
 *
 * <p>SSE is used rather than WebSocket because it is plain HTTP: it survives gateways and
 * reuses the very same API-key authentication, and clients reconnect on their own.</p>
 */
@RestController
@RequestMapping("/api/v1/external/stream")
@CrossOrigin
@Tag(name = "External SSE", description = "对外 SSE 推送：流式问答与知识库事件订阅")
public class ExternalStreamController {
    private final ExternalAccessService accessService;
    private final ExternalStreamService streamService;
    private final ExternalAuditService auditService;
    private final PlatformEventHub hub;
    private final PlatformEventVisibilityService visibility;
    private final KnowledgeProperties.External properties;

    public ExternalStreamController(
            ExternalAccessService accessService,
            ExternalStreamService streamService,
            ExternalAuditService auditService,
            PlatformEventHub hub,
            PlatformEventVisibilityService visibility,
            KnowledgeProperties properties) {
        this.accessService = accessService;
        this.streamService = streamService;
        this.auditService = auditService;
        this.hub = hub;
        this.visibility = visibility;
        this.properties = properties.external();
    }

    @GetMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式问答：依次推送 stage / delta / citation / done 事件")
    public SseEmitter streamQuery(
            HttpServletRequest request,
            @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId,
            @RequestParam("question") String question,
            @RequestParam(value = "topK", required = false) Integer topK) {
        ApiKey key = open(request, "/stream/query", knowledgeBaseId);
        Long kb = accessService.resolveKnowledgeBase(key, knowledgeBaseId);
        accessService.assertScope(key, ApiKey.SCOPE_READ);
        SseEmitter emitter = new SseEmitter(0L);
        streamService.streamQuery(key, kb, question, topK, emitter);
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
    public SseEmitter streamEvents(
            HttpServletRequest request,
            @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId,
            @RequestParam(value = "types", required = false) String types,
            @RequestParam(value = "sinceEventId", required = false) Long sinceEventId,
            @RequestParam(value = "legacy", defaultValue = "false") boolean legacy) {
        ApiKey key = open(request, "/stream/events", knowledgeBaseId);
        Long kb = accessService.resolveKnowledgeBase(key, knowledgeBaseId);
        accessService.assertScope(key, ApiKey.SCOPE_READ);
        if (legacy) {
            SseEmitter emitter = new SseEmitter(0L);
            streamService.streamEvents(key, kb, emitter);
            return emitter;
        }
        Long since = sinceEventId != null ? sinceEventId : lastEventId(request);
        return hub.subscribe("key:" + key.getId(), kb, splitTypes(types), since,
                visibility.forApiKey(key, kb));
    }

    /** Last-Event-ID is the browser's resume handle; the parameter is there for curl-style clients. */
    private Long lastEventId(HttpServletRequest request) {
        String header = request.getHeader("Last-Event-ID");
        if (header == null || header.trim().isEmpty()) return null;
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Set<String> splitTypes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> values = new HashSet<String>();
        for (String part : raw.split(",")) {
            String token = part.trim().toUpperCase();
            if (!token.isEmpty()) values.add(token);
        }
        return values;
    }

    /** Same credential gate as REST, but audited once when the stream opens. */
    private ApiKey open(HttpServletRequest request, String method, Long knowledgeBaseId) {
        if (!properties.isEnabled() || !properties.isSseEnabled()) {
            throw new SecurityException("SSE 推送服务已关闭");
        }
        long start = System.currentTimeMillis();
        ApiKey key = accessService.authenticate(request);
        auditService.record(key, ApiCallAudit.CHANNEL_SSE, method, knowledgeBaseId, 200,
                System.currentTimeMillis() - start, "stream opened");
        return key;
    }
}
