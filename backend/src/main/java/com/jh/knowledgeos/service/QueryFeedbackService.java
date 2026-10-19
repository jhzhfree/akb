package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.QueryFeedbackRequest;
import com.jh.knowledgeos.dto.QueryFeedbackReviewRequest;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.mapper.QueryFeedbackMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Captures explicit user answer feedback and routes it through knowledge operations review. */
@Service
public class QueryFeedbackService {

    private static final String NEW = "NEW";
    private static final String IN_REVIEW = "IN_REVIEW";
    private static final String ACTION_REQUIRED = "ACTION_REQUIRED";
    private static final String RESOLVED = "RESOLVED";
    private static final String REJECTED = "REJECTED";

    private final QueryFeedbackMapper mapper;
    private final KnowledgeBaseAccessService access;

    public QueryFeedbackService(QueryFeedbackMapper mapper, KnowledgeBaseAccessService access) {
        this.mapper = mapper;
        this.access = access;
    }

    @Transactional
    public Map<String, Object> submit(QueryFeedbackRequest request) {
        User actor = requireUser();
        String queryId = request.getQueryId().trim();
        Map<String, Object> trace = mapper.getTraceOwner(queryId);
        if (trace == null) {
            throw new IllegalArgumentException("问答记录不存在或已过期");
        }
        Long kb = longValue(trace.get("knowledgeBaseId"));
        access.assertRead(kb);
        Long owner = longValue(trace.get("actorUserId"));
        if (owner == null) {
            throw new SecurityException("无法确认该问答的发起人，请重新提问后评价");
        }
        if (!owner.equals(actor.getId())) {
            throw new SecurityException("只能评价本人发起的问答");
        }

        String rating = request.getRating().trim().toUpperCase(java.util.Locale.ROOT);
        String reason = trimToNull(request.getReasonCode());
        String comment = trimToNull(request.getComment());
        String suggestion = trimToNull(request.getSuggestedAnswer());
        if ("UNHELPFUL".equals(rating) && reason == null) {
            throw new IllegalArgumentException("请为需要改进的回答选择原因");
        }
        if ("OTHER".equals(reason) && comment == null) {
            throw new IllegalArgumentException("选择“其他”时请补充说明");
        }
        mapper.upsert(UUID.randomUUID().toString(), queryId, kb, actor.getId(), rating, reason, comment, suggestion);
        return mapper.findByQueryAndActor(queryId, actor.getId());
    }

    public List<Map<String, Object>> mine(Long kb, Integer requestedLimit) {
        User actor = requireUser();
        access.assertRead(kb);
        return mapper.listMine(kb, actor.getId(), boundedLimit(requestedLimit));
    }

    public Map<String, Object> operations(Long kb, String status, Integer requestedLimit) {
        access.assertWrite(kb);
        String normalized = trimToNull(status);
        if (normalized != null && !validStatus(normalized)) {
            throw new IllegalArgumentException("反馈状态不合法");
        }
        return mapOf("items", mapper.listOperations(kb, normalized, boundedLimit(requestedLimit)), "summary", mapper.summary(kb));
    }

    @Transactional
    public Map<String, Object> review(Long id, QueryFeedbackReviewRequest request) {
        User reviewer = requireUser();
        Map<String, Object> existing = mapper.getById(id);
        if (existing == null) {
            throw new IllegalArgumentException("反馈记录不存在：" + id);
        }
        access.assertWrite(longValue(existing.get("knowledgeBaseId")));
        String status = request.getStatus().trim().toUpperCase(java.util.Locale.ROOT);
        if (!validStatus(status)) {
            throw new IllegalArgumentException("反馈状态不合法");
        }
        String note = trimToNull(request.getReviewNote());
        if ((RESOLVED.equals(status) || REJECTED.equals(status) || ACTION_REQUIRED.equals(status)) && note == null) {
            throw new IllegalArgumentException("转为待整改、已解决或已驳回时必须填写处理说明");
        }
        mapper.review(id, status, reviewer.getId(), note);
        Map<String, Object> result = mapper.getById(id);
        result.put("reviewNote", note);
        result.put("reviewerUserId", reviewer.getId());
        return result;
    }

    private User requireUser() {
        User user = AuthContext.get();
        if (user == null || user.getId() == null) {
            throw new UnauthorizedException("请登录后提交或查看问答反馈");
        }
        return user;
    }

    private int boundedLimit(Integer requested) {
        int value = requested == null ? 50 : requested.intValue();
        return Math.max(1, Math.min(value, 200));
    }

    private boolean validStatus(String status) {
        return NEW.equals(status) || IN_REVIEW.equals(status) || ACTION_REQUIRED.equals(status)
            || RESOLVED.equals(status) || REJECTED.equals(status);
    }

    private Long longValue(Object value) {
        if (value == null) { return null; }
        if (value instanceof Number) { return Long.valueOf(((Number) value).longValue()); }
        try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException e) { return null; }
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) { return null; }
        return value.trim();
    }

    private Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put(key1, value1 == null ? Collections.emptyList() : value1);
        result.put(key2, value2);
        return result;
    }
}
