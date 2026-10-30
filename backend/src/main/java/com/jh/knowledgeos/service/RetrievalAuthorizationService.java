package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.RetrievalAuthorizationContext;
import com.jh.knowledgeos.domain.ApiKey;
import com.jh.knowledgeos.mapper.ExternalApiMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 外部检索的授权解析：把「调用方自声明标签」换成「服务端授权标签」。
 *
 * <p>背景：外部接口此前把请求里的 {@code permissionTags} 原样送进检索链路，ai-service 直接用它
 * 过滤 Elasticsearch / BM25 / Milvus / Neo4j，于是自填标签就能扩大召回——标签成了客户端
 * 可自我授权的能力。这里把授权收回服务端：有效标签 = 凭证授权范围 ∩ 请求标签。</p>
 *
 * <p>上线策略（{@code knowledge.acl.external-tag-mode}）：</p>
 * <ul>
 *   <li>{@code SHADOW}（默认）：仍按旧行为执行，但算出新策略结果并记录差异，用于迁移评估；</li>
 *   <li>{@code ENFORCE}：按服务端授权执行，未配置标签授权的凭证按默认拒绝（仅公开资料）；</li>
 *   <li>{@code CLIENT}：完全沿用旧行为，仅应急回滚用。</li>
 * </ul>
 */
@Service
public class RetrievalAuthorizationService {

    /** 外部请求标签的处理模式。 */
    public enum Mode { SHADOW, ENFORCE, CLIENT }

    private static final Logger log = LoggerFactory.getLogger(RetrievalAuthorizationService.class);

    private final ObjectMapper objectMapper;

    private final Mode mode;

    private final ExternalApiMapper externalApiMapper;

    public RetrievalAuthorizationService(ObjectMapper objectMapper,
        @Value("${knowledge.acl.external-tag-mode:SHADOW}") String mode) {
        this(objectMapper, mode, null);
    }

    @Autowired
    public RetrievalAuthorizationService(ObjectMapper objectMapper,
        @Value("${knowledge.acl.external-tag-mode:SHADOW}") String mode, ExternalApiMapper externalApiMapper) {
        this.objectMapper = objectMapper;
        this.mode = parseMode(mode);
        this.externalApiMapper = externalApiMapper;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * 解析本次调用真正可用的标签。
     *
     * @param key       外部凭证；null 表示内部调用（无凭证），保持原样不收窄
     * @param requested 调用方请求的标签，只允许收窄
     * @param source    调用渠道（REST / MCP / SSE），仅用于日志
     */
    public List<String> effectiveTags(ApiKey key, List<String> requested, String source) {
        return effectiveTags(key, key == null ? null : key.getKnowledgeBaseId(), requested, source);
    }

    /** Resolve the API Key ceiling together with an optional knowledge-base grant ceiling. */
    public List<String> effectiveTags(ApiKey key, Long knowledgeBaseId, List<String> requested, String source) {
        if (key == null) {
            return requested;
        }
        RetrievalAuthorizationContext context = forCredential(key, knowledgeBaseId);
        if (key.isWorkloadCredential()) return context.effectiveTags(requested);
        List<String> removed = context.removedTags(requested);
        if (mode == Mode.CLIENT) {
            return requested;
        }
        if (removed.isEmpty()) {
            return context.effectiveTags(requested);
        }
        // 有标签被服务端授权丢弃：影子模式只记录不执行，强制执行时记录并生效。
        String detail = "externalTag source=" + source + " key=" + key.getKeyCode() + " kb=" + context.knowledgeBaseId()
            + " mode=" + mode + " requested=" + requested + " removed=" + removed
            + " granted=" + context.grantedTagsForLog();
        if (mode == Mode.SHADOW) {
            log.warn("[ACL][SHADOW] 外部标签超出服务端授权，强制模式生效后将被收窄：{}", detail);
            return requested;
        }
        log.warn("[ACL][ENFORCED] 外部标签超出服务端授权，已按授权范围收窄：{}", detail);
        return context.effectiveTags(requested);
    }

    /** 按凭证标签与知识库 grant 标签构造上下文；知识库范围只会进一步收窄凭证授权。 */
    public RetrievalAuthorizationContext forCredential(ApiKey key) {
        return forCredential(key, key == null ? null : key.getKnowledgeBaseId());
    }

    public RetrievalAuthorizationContext forCredential(ApiKey key, Long knowledgeBaseId) {
        String code = key == null ? null : key.getKeyCode();
        Long kb = knowledgeBaseId;
        List<String> keyTags = parseTags(key == null ? null : key.getPermissionTags());
        List<String> kbTags = null;
        if (key != null && key.getPrincipalId() != null && kb != null && externalApiMapper != null) {
            String grantJson = externalApiMapper.getPrincipalKnowledgeBaseGrantPermissionTags(key.getPrincipalId(), kb);
            kbTags = parseTags(grantJson);
        }
        if (key != null && key.getDelegatedPrincipalId() != null && kb != null && externalApiMapper != null) {
            String employeeGrantJson = externalApiMapper.getPrincipalKnowledgeBaseGrantPermissionTags(key.getDelegatedPrincipalId(), kb);
            kbTags = intersectPolicies(kbTags, parseTags(employeeGrantJson));
        }
        if (key != null && key.isWorkloadCredential() && kb != null && externalApiMapper != null) {
            if (key.getWorkloadIdentity() == null) throw new SecurityException("缺少已认证 Agent");
            if (!key.getWorkloadIdentity().isServiceGrantsInherited()) {
                String agentTags = externalApiMapper.getPrincipalKnowledgeBaseGrantPermissionTags(key.getWorkloadIdentity().getAgentPrincipalId(), kb);
                kbTags = intersectPolicies(kbTags, parseTags(agentTags));
            }
        }
        List<String> granted = intersectPolicies(keyTags, kbTags);
        if (granted == null) {
            // 未配置标签授权：强制模式下按默认拒绝处理，其余模式不约束。
            return (mode == Mode.ENFORCE || (key != null && key.isWorkloadCredential()))
                ? RetrievalAuthorizationContext.denyByDefault(code, kb)
                : RetrievalAuthorizationContext.unconstrained(code, kb);
        }
        return RetrievalAuthorizationContext.granted(code, kb, granted);
    }

    private List<String> intersectPolicies(List<String> keyTags, List<String> kbTags) {
        if (keyTags == null) {
            return kbTags;
        }
        if (kbTags == null) {
            return keyTags;
        }
        List<String> intersection = new ArrayList<String>();
        for (String tag : keyTags) {
            if (kbTags.contains(tag) && !intersection.contains(tag)) {
                intersection.add(tag);
            }
        }
        return intersection;
    }

    /** JSON 数组文本 → 标签列表；null / 空串 / 解析失败都视为「未配置」。 */
    @SuppressWarnings("unchecked")
    private List<String> parseTags(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(json, List.class);
            if (parsed == null) {
                return null;
            }
            List<String> out = new ArrayList<String>();
            for (Object item : (List<Object>) parsed) {
                if (item != null) {
                    String tag = String.valueOf(item).trim();
                    if (!tag.isEmpty()) {
                        out.add(tag);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            // 配置坏了不能当成「无约束」放行：按最严格的空授权处理，并留下告警。
            log.error("[ACL] 凭证标签授权配置无法解析，按默认拒绝处理：{}", json, e);
            return Collections.emptyList();
        }
    }

    private static Mode parseMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Mode.SHADOW;
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("[ACL] 未知的外部标签模式 {}，回落到 SHADOW", raw);
            return Mode.SHADOW;
        }
    }
}
