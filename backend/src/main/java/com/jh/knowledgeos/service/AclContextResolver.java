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

    public List<String> resolve(String trustedTagsHeader) {
        String subjectCode = currentSubjectCode();
        if (identityDbEnabled && subjectCode != null) {
            List<String> tags = mapper.resolveAclPermissionTags(subjectCode);
            return tags == null ? Collections.<String>emptyList() : tags;
        }
        if (!trustedHeaderEnabled) {
            return Collections.emptyList();
        }
        return parseTags(trustedTagsHeader);
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
