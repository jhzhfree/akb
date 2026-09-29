package com.jh.knowledgeos.controller;

import com.jh.knowledgeos.common.ApiResponse;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.domain.PlatformEvent;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.service.AuthContext;
import com.jh.knowledgeos.service.PlatformEventHub;
import com.jh.knowledgeos.service.PlatformEventPublisher;
import com.jh.knowledgeos.service.PlatformEventStore;
import com.jh.knowledgeos.service.PlatformEventVisibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session-authenticated half of the event bus: the console subscribes here without owning a key.
 *
 * <p>Separate from {@code /api/v1/external/stream/events} because the principal is a signed-in
 * account, filtered by knowledge-base read permission rather than by key scope.</p>
 */
@RestController
@RequestMapping("/api/internal/platform/events")
@Tag(name = "Platform events", description = "会话态平台事件：SSE 订阅与历史查询")
public class PlatformEventController {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventController.class);

    private static final int MAX_LIMIT = 200;

    private final PlatformEventHub hub;

    private final PlatformEventStore store;

    private final PlatformEventVisibilityService visibility;

    public PlatformEventController(PlatformEventHub hub, PlatformEventStore store, PlatformEventVisibilityService visibility) {
        this.hub = hub;
        this.store = store;
        this.visibility = visibility;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "平台事件 SSE 订阅：知识库生命周期、知识对象变更、检索调用、权限变更")
    public SseEmitter stream(HttpServletRequest request, @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId,
        @RequestParam(value = "types", required = false) String types, @RequestParam(value = "sinceId", required = false) Long sinceId) {
        User user = requireUser();
        Long since = sinceId != null ? sinceId : lastEventId(request);
        return hub.subscribe("user:" + user.getId(), knowledgeBaseId, parseTypes(types), since, visibility.forSession(user, knowledgeBaseId));
    }

    @GetMapping
    @Operation(summary = "事件历史：按知识库与类型回放，Same visibility rules as the stream")
    public ApiResponse<Object> history(@RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId, @RequestParam(value = "types",
        required = false) String types, @RequestParam(value = "limit", required = false) Integer limit) {
        User user = requireUser();
        int size = Math.max(1, Math.min(limit == null ? 50 : limit, MAX_LIMIT));
        PlatformEventHub.Visibility audit = visibility.forSession(user, knowledgeBaseId);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (PlatformEvent event : store.recent(knowledgeBaseId, parseTypesList(types), size)) {
            if (!audit.visible(event)) {
                continue;
            }
            items.add(PlatformEventPublisher.envelope(event, store.detailOf(event)));
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("items", items);
        payload.put("count", items.size());
        payload.put("activeSubscriptions", hub.activeSubscriptions());
        return ApiResponse.ok(payload);
    }

    @GetMapping("/types")
    @Operation(summary = "事件类型字典，供订阅端 filtering UI 使用")
    public ApiResponse<Object> types() {
        return ApiResponse.ok(Arrays.asList(PlatformEventTypes.all()));
    }

    /**
     * Last-Event-ID arrives as a header on browser reconnects and as a parameter for curl users.
     */
    private Long lastEventId(HttpServletRequest request) {
        String header = request.getHeader("Last-Event-ID");
        if (!StringUtils.hasText(header)) {
            return null;
        }
        try {
            return Long.valueOf(header.trim());
        } catch (NumberFormatException e) {
            log.debug("ignoring invalid Last-Event-ID: {}", header);
            return null;
        }
    }

    private User requireUser() {
        User user = AuthContext.get();
        if (user == null) {
            throw new SecurityException("请先登录后再订阅平台事件");
        }
        return user;
    }

    private java.util.Set<String> parseTypes(String raw) {
        List<String> values = parseTypesList(raw);
        return values == null ? new java.util.HashSet<String>() : new java.util.HashSet<String>(values);
    }

    private List<String> parseTypesList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        List<String> values = new ArrayList<String>();
        for (String part : raw.split(",")) {
            String token = part.trim().toUpperCase();
            if (!token.isEmpty()) {
                values.add(token);
            }
        }
        return values.isEmpty() ? null : values;
    }
}
