package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.KnowledgeBaseVisibility;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.AuthMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

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
        if (kb == null) {
            return;
        }
        KnowledgeBase base = mapper.getKnowledgeBase(kb);
        if (base == null) {
            throw new IllegalArgumentException("知识库不存在：" + kb);
        }
        if (write) {
            if (!canWrite(user, base)) {
                throw new SecurityException("当前账号无权修改该知识库：" + base.getName());
            }
            return;
        }
        // 停用的库（禁用 / 归档）对读操作关闭——否则"禁用"只是列表上的一个标签，检索照旧能查到。
        // 写操作刻意放行：要能把它启用回来，也要能执行彻底删除。
        assertNotStopped(base);
        if (!canRead(user, base)) {
            throw new SecurityException("当前账号无权访问该知识库：" + base.getName());
        }
    }

    /**
     * 库被停用时拒绝对外使用。
     *
     * <p>外部 API / MCP 走的是另一条解析路径（只看授权关系），必须单独调这里，
     * 否则同一枚 Key 在内网管理接口被拦住、在对外接口却能查到。</p>
     */
    public void assertEnabled(Long kb) {
        if (kb == null) {
            return;
        }
        KnowledgeBase base = mapper.getKnowledgeBase(kb);
        if (base != null) {
            assertNotStopped(base);
        }
    }

    private void assertNotStopped(KnowledgeBase base) {
        String status = base.getStatus();
        if ("DISABLED".equalsIgnoreCase(status) || "ARCHIVED".equalsIgnoreCase(status)) {
            throw new IllegalStateException("知识库已停用：" + base.getName() + "（禁用或归档后不可检索，请先启用）");
        }
    }

    public boolean canRead(User user, KnowledgeBase base) {
        if (base == null) {
            return false;
        }
        if (user == null) {
            return base.visibility() == KnowledgeBaseVisibility.PUBLIC;
        }
        if (UserRole.of(user.getRole()) == UserRole.ADMIN) {
            return true;
        }
        switch(base.visibility()) {
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
        if (base == null || user == null) {
            return false;
        }
        UserRole role = UserRole.of(user.getRole());
        if (role == UserRole.ADMIN || role == UserRole.KNOWLEDGE_ADMIN) {
            return true;
        }
        if (isOwner(user, base)) {
            return true;
        }
        return role.canWriteKnowledge() && canRead(user, base);
    }

    /** Current read grants used when refreshing a user's personal-token Principal. */
    public List<Long> readableKnowledgeBaseIds(User user) {
        List<Long> ids = new ArrayList<Long>();
        if (user == null || user.getId() == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) return ids;
        boolean all = UserRole.of(user.getRole()) == UserRole.ADMIN;
        List<KnowledgeBase> bases = mapper.listKnowledgeBases(user.getId(), all);
        if (bases == null) return ids;
        for (KnowledgeBase base : bases) {
            if (base != null && base.getId() != null && canRead(user, base)) ids.add(base.getId());
        }
        return ids;
    }

    private boolean isOwner(User user, KnowledgeBase base) {
        return user != null && user.getId() != null && user.getId().equals(base.getCreatedBy());
    }

    private boolean isGroupMember(User user, Long groupId) {
        if (user == null || groupId == null) {
            return false;
        }
        return authMapper.countGroupMember(groupId, user.getId()) > 0;
    }
}
