package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.dto.MemoryCaptureRequest;
import com.jh.knowledgeos.dto.QueryFeedbackRequest;
import com.jh.knowledgeos.dto.QueryFeedbackReviewRequest;
import com.jh.knowledgeos.exception.UnauthorizedException;
import com.jh.knowledgeos.mapper.QueryFeedbackMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final AgentMemoryService memories;
    private final AclContextResolver aclContext;

    private static final Logger log = LoggerFactory.getLogger(QueryFeedbackService.class);

    public QueryFeedbackService(QueryFeedbackMapper mapper, KnowledgeBaseAccessService access, AgentMemoryService memories,
        AclContextResolver aclContext) {
        this.mapper = mapper;
        this.access = access;
        this.memories = memories;
        this.aclContext = aclContext;
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
        rememberCorrection(actor, queryId, request, reason, suggestion);
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

    /**
     * 把用户主动写的改进建议存成他自己的记忆——"纠正过一次，下次不再犯"。
     *
     * <p>只在用户确实写了建议时存：只点了"没帮助"没有可存的信息，存一条"用户说不好"
     * 只会让下次的上下文变脏。</p>
     *
     * <p>内容里带上问题原文是必要的：召回是子串匹配，只有建议没有原文的话，下次换个措辞
     * 提问就命中不了这条记忆——而"换个措辞"恰恰是这类纠正最容易发生的场景。</p>
     *
     * <p>失败不影响反馈本身。反馈已经落库，为了一条记忆让用户看到"提交失败"不值得；
     * 这里在事务内吞掉异常，反馈照常返回。</p>
     */
    private void rememberCorrection(User actor, String queryId, QueryFeedbackRequest request, String reason,
        String suggestion) {
        if (suggestion == null) {
            return;
        }
        try {
            String subject = subjectCode(actor);
            if (subject == null) {
                return;
            }
            StringBuilder text = new StringBuilder("[问答改进]");
            String question = trimToNull(request.getQuestion());
            if (question != null) {
                text.append(" 问题：").append(question);
            }
            text.append("\n用户纠正：").append(suggestion);
            if (reason != null) {
                text.append("（原因：").append(reason).append("）");
            }
            MemoryCaptureRequest memory = new MemoryCaptureRequest();
            memory.setMemoryLevel("L1");
            memory.setScopeType("AGENT");
            memory.setContent(text.length() > 4000 ? text.substring(0, 4000) : text.toString());
            memory.setTags(Arrays.asList("问答改进"));
            memory.setSourceType("USER_CONFIRMED");
            memory.setSourceRef("QUERY:" + queryId);
            // 同一条问答重复提交只留一份记忆，而不是越积越多。
            memory.setIdempotencyKey("feedback:" + queryId);
            memory.setConfidence(Double.valueOf(0.9));
            memory.setImportance(Integer.valueOf(4));
            memories.captureForIdentity("user:" + subject, subject, memory);
        } catch (RuntimeException e) {
            log.warn("改进建议写入记忆失败，反馈本身不受影响：{}", e.getMessage());
        }
    }

    /**
     * 记忆身份：与问答读取侧（KnowledgeQueryService）取同一个来源，存和读才对得上。
     * 取不到登录态里的账号编码就退回用户自己的编码。
     */
    private String subjectCode(User actor) {
        String fromContext = aclContext == null ? null : trimToNull(aclContext.currentSubjectCode());
        return fromContext != null ? fromContext : trimToNull(actor.getUserCode());
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
