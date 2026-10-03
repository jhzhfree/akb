package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.User;

/**
 * Resolves ACL tags from an authenticated enterprise identity.
 */
@Component
public class AclContextResolver {

    private final KnowledgeMapper mapper;

    private final boolean identityDbEnabled;

    private final boolean trustedHeaderEnabled;

    public AclContextResolver(KnowledgeMapper mapper, @Value("${knowledge.acl.identity-db-enabled:true}") boolean identityDbEnabled,
        @Value("${knowledge.acl.trusted-header-enabled:false}") boolean trustedHeaderEnabled) {
        this.mapper = mapper;
        this.identityDbEnabled = identityDbEnabled;
        this.trustedHeaderEnabled = trustedHeaderEnabled;
    }

    /**
     * 解析当前请求可见的权限标签。
     *
     * <p>返回值是三态的，调用方与 mapper 必须区分对待：
     * <ul>
     *   <li>{@code null}：不存在 ACL 约束主体（未开启认证，或平台管理员）→ 放行全部对象；</li>
     *   <li>空列表：有身份但一个标签都没有 → 只能看公开（无标签）对象；</li>
     *   <li>非空列表：公开对象 + 命中任一标签的对象。</li>
     * </ul>
     * 早期实现把「无约束」和「零标签」都压成空列表，于是带标签的知识对象对管理员与
     * 未认证调用方完全不可见，而统计 SQL 又没有 ACL 过滤——表现就是工作台显示数量、
     * 进入明细页却一条都没有。
     */
    public List<String> resolve(String trustedTagsHeader) {
        String subjectCode = currentSubjectCode();
        if (identityDbEnabled && subjectCode != null) {
            if (unrestricted()) {
                return null;
            }
            List<String> tags = mapper.resolveAclPermissionTags(subjectCode);
            return tags == null ? Collections.<String>emptyList() : tags;
        }
        if (!trustedHeaderEnabled) {
            return null;
        }
        List<String> headerTags = parseTags(trustedTagsHeader);
        return headerTags.isEmpty() ? null : headerTags;
    }

    /**
     * 平台管理员与未开启认证的请求不受对象级 ACL 约束：
     * 与 PermissionInterceptor 对空 AuthContext 的放行保持一致。
     */
    private boolean unrestricted() {
        User user = AuthContext.get();
        if (user == null) {
            return true;
        }
        UserRole role = UserRole.of(user.getRole());
        return role == UserRole.ADMIN || role == UserRole.KNOWLEDGE_ADMIN;
    }

    public String currentSubjectCode() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes)) {
            return null;
        }
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String value = request.getHeader("X-Authenticated-User-Id");
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        // 控制台登录用户：账号编码与 ACL 主体编码一致，登录即沿用既有的权限标签体系。
        User user = AuthContext.get();
        return user == null ? null : user.getUserCode();
    }

    private List<String> parseTags(String header) {
        if (header == null || header.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tags = new ArrayList<String>();
        for (String raw : header.split(",")) {
            String tag = raw.trim();
            if (!tag.isEmpty() && !tags.contains(tag)) {
                tags.add(tag);
            }
        }
        return tags;
    }
}
