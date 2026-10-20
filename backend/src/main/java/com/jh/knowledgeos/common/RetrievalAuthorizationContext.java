package com.jh.knowledgeos.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索授权上下文：服务端根据凭证与授权关系算出的检索范围，只在服务端内部传递。
 *
 * <p>设计要点（对应《AKB 知识库检索权限设计》§4.4 权限只收窄）：</p>
 *
 * <pre>
 * effectiveResources = serverAuthorizedResources ∩ callerRequestedFilters
 * </pre>
 *
 * <p>调用方请求里的 {@code permissionTags} 只能作为收窄条件：请求为空表示不收窄（用授权全集），
 * 请求非空时超出授权范围的部分一律丢弃。无论哪种情况都不会比服务端授权范围更大。</p>
 *
 * <p>三态语义沿用 {@code AclContextResolver}，与检索链路既有约定保持一致：</p>
 * <ul>
 *   <li>{@code grantedTags == null}：无服务端约束（未开启标签授权）→ 不收窄；</li>
 *   <li>空列表：已配置授权但一个标签都没有 → 只能检索无标签（公开）资料；</li>
 *   <li>非空：公开资料 + 命中任一标签的资料。</li>
 * </ul>
 */
public final class RetrievalAuthorizationContext {

    /** 凭证码，仅用于审计与影子模式日志，不参与判定。 */
    private final String credentialCode;

    /** 已解析的目标知识库：由 ExternalAccessService 校验过绑定关系。 */
    private final Long knowledgeBaseId;

    /** 服务端授权的资料标签；null 表示未配置标签授权。 */
    private final List<String> grantedTags;

    private RetrievalAuthorizationContext(String credentialCode, Long knowledgeBaseId, List<String> grantedTags) {
        this.credentialCode = credentialCode;
        this.knowledgeBaseId = knowledgeBaseId;
        this.grantedTags = grantedTags == null ? null : Collections.unmodifiableList(new ArrayList<String>(grantedTags));
    }

    /** 已配置标签授权的凭证。 */
    public static RetrievalAuthorizationContext granted(String credentialCode, Long knowledgeBaseId, List<String> tags) {
        return new RetrievalAuthorizationContext(credentialCode, knowledgeBaseId, tags);
    }

    /** 未配置标签授权：不收窄（兼容模式，或确实没有标签约束的内部调用）。 */
    public static RetrievalAuthorizationContext unconstrained(String credentialCode, Long knowledgeBaseId) {
        return new RetrievalAuthorizationContext(credentialCode, knowledgeBaseId, null);
    }

    /** 默认拒绝：已配置授权但范围为空，只能检索公开资料。 */
    public static RetrievalAuthorizationContext denyByDefault(String credentialCode, Long knowledgeBaseId) {
        return new RetrievalAuthorizationContext(credentialCode, knowledgeBaseId, Collections.<String>emptyList());
    }

    public String credentialCode() {
        return credentialCode;
    }

    public Long knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public boolean configured() {
        return grantedTags != null;
    }

    /** 日志用：区分「未配置」与「空授权」，否则两者在日志里都显示成 []。 */
    public String grantedTagsForLog() {
        return grantedTags == null ? "<未配置>" : grantedTags.toString();
    }

    /**
     * 本次检索真正可用的标签：服务端授权 ∩ 调用方请求。
     * 请求为空或 null 时不收窄，直接返回授权全集。
     */
    public List<String> effectiveTags(List<String> requested) {
        if (grantedTags == null) {
            return requested == null ? null : new ArrayList<String>(requested);
        }
        if (requested == null || requested.isEmpty()) {
            return new ArrayList<String>(grantedTags);
        }
        Set<String> allowed = new LinkedHashSet<String>(grantedTags);
        List<String> out = new ArrayList<String>();
        for (String tag : requested) {
            // 保留请求顺序，去重，只留下被授权的标签。
            if (tag != null && allowed.contains(tag) && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return out;
    }

    /** 请求里被服务端授权丢弃的标签：影子模式与审计用（判断有没有人在越权试探）。 */
    public List<String> removedTags(List<String> requested) {
        if (grantedTags == null || requested == null || requested.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> allowed = new LinkedHashSet<String>(grantedTags);
        List<String> out = new ArrayList<String>();
        for (String tag : requested) {
            if (tag != null && !allowed.contains(tag) && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return out;
    }
}
