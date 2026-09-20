package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.KnowledgeBaseVisibility;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.AuthMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

/**
 * Knowledge-base sharing rules.
 *
 * <p>PUBLIC knowledge bases are readable by every signed-in account; PRIVATE ones only by
 * their creator; GROUP ones by the creator plus members of the owning group. Write access
 * additionally requires an editing role, knowledge-admin rights, or ownership, so viewers
 * cannot mutate knowledge even inside a public workspace.</p>
 */
@Service
public class KnowledgeBaseAccessService {
    private final KnowledgeMapper mapper;
    private final AuthMapper authMapper;

    public KnowledgeBaseAccessService(KnowledgeMapper mapper, AuthMapper authMapper) {
        this.mapper = mapper;
        this.authMapper = authMapper;
    }

    public void assertRead(Long kb) {
        assertAccess(kb, false);
    }

    public void assertWrite(Long kb) {
        assertAccess(kb, true);
    }

    public void assertAccess(Long kb, boolean write) {
        assertAccess(kb, write, AuthContext.get());
    }

    /**
     * External API-key callers have no console session: unbound keys fall back to the
     * anonymous rule (public workspaces only), while a key bound to a workspace is trusted
     * for that workspace because an administrator issued it.
     */
    public void assertExternalAccess(Long kb, boolean write) {
        assertAccess(kb, write, null);
    }

    public void assertAccess(Long kb, boolean write, User user) {
        if (kb == null) return;
        KnowledgeBase base = mapper.getKnowledgeBase(kb);
        if (base == null) {
            throw new IllegalArgumentException("知识库不存在：" + kb);
        }
        if (write) {
            if (!canWrite(user, base)) {
                throw new SecurityException("当前账号无权修改该知识库：" + base.getName());
            }
        } else if (!canRead(user, base)) {
            throw new SecurityException("当前账号无权访问该知识库：" + base.getName());
        }
    }

    public boolean canRead(User user, KnowledgeBase base) {
        if (base == null) return false;
        if (user == null) return base.visibility() == KnowledgeBaseVisibility.PUBLIC;
        if (UserRole.of(user.getRole()) == UserRole.ADMIN) return true;
        switch (base.visibility()) {
            case PUBLIC:
                return true;
            case PRIVATE:
                return isOwner(user, base);
            case GROUP:
                return isOwner(user, base) || isGroupMember(user, base.getOwnerGroupId());
            default:
                return false;
        }
    }

    public boolean canWrite(User user, KnowledgeBase base) {
        if (base == null || user == null) return false;
        UserRole role = UserRole.of(user.getRole());
        if (role == UserRole.ADMIN || role == UserRole.KNOWLEDGE_ADMIN) return true;
        if (isOwner(user, base)) return true;
        return role.canWriteKnowledge() && canRead(user, base);
    }

    private boolean isOwner(User user, KnowledgeBase base) {
        return user != null && user.getId() != null && user.getId().equals(base.getCreatedBy());
    }

    private boolean isGroupMember(User user, Long groupId) {
        if (user == null || groupId == null) return false;
        return authMapper.countGroupMember(groupId, user.getId()) > 0;
    }
}
