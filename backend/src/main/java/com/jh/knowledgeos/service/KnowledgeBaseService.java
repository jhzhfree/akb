package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.KnowledgeBaseVisibility;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace lifecycle service; archives instead of deleting authoritative knowledge bases.
 *
 * <p>Every workspace records its creator and a sharing scope, and listing is filtered by
 * that scope so a caller only ever sees workspaces it is allowed to open.</p>
 */
@Service
public class KnowledgeBaseService {
    private final KnowledgeMapper mapper;
    private final IdGenerator ids;
    private final PlatformEventPublisher publisher;

    public KnowledgeBaseService(KnowledgeMapper mapper, IdGenerator ids, PlatformEventPublisher publisher) {
        this.mapper = mapper;
        this.ids = ids;
        this.publisher = publisher;
    }

    public List<KnowledgeBase> list() {
        User user = AuthContext.get();
        boolean all = user != null && UserRole.of(user.getRole()) == UserRole.ADMIN;
        return mapper.listKnowledgeBases(user == null ? null : user.getId(), all);
    }

    /** Single workspace lookup, used by external callers bound to one knowledge base. */
    public KnowledgeBase get(Long id) {
        return id == null ? null : mapper.getKnowledgeBase(id);
    }

    public KnowledgeBase create(
            String name,
            String description,
            String visibility,
            Long ownerGroupId,
            User creator) {
        if (creator != null && !UserRole.of(creator.getRole()).canWriteKnowledge()) {
            throw new SecurityException("当前角色无权创建知识库");
        }
        KnowledgeBaseScope scope = resolveScope(visibility, ownerGroupId);
        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode(ids.next("KB_"));
        base.setName(name.trim());
        base.setDescription(description);
        base.setStatus("ACTIVE");
        base.setCreatedBy(creator == null ? null : creator.getId());
        base.setVisibility(scope.visibility.name());
        base.setOwnerGroupId(scope.ownerGroupId);
        mapper.insertKnowledgeBase(base);
        Map<String, Object> detail = describe(base);
        detail.put("description", base.getDescription());
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_CREATED, base.getId(),
                PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE", base.getBaseCode(),
                "创建知识库：" + base.getName(), detail);
        return base;
    }

    public KnowledgeBase updateAccess(Long id, String visibility, Long ownerGroupId) {
        KnowledgeBase base = mapper.getKnowledgeBase(id);
        if (base == null) {
            throw new IllegalArgumentException("知识库不存在：" + id);
        }
        KnowledgeBaseScope scope = resolveScope(visibility, ownerGroupId);
        String previousVisibility = base.getVisibility();
        Long previousGroup = base.getOwnerGroupId();
        mapper.updateKnowledgeBaseAccess(id, scope.visibility.name(), scope.ownerGroupId);
        KnowledgeBase updated = mapper.getKnowledgeBase(id);
        Map<String, Object> detail = describe(updated);
        detail.put("previousVisibility", previousVisibility);
        detail.put("previousOwnerGroupId", previousGroup);
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_ACCESS_UPDATED, updated.getId(),
                PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE", updated.getBaseCode(),
                "调整知识库可见范围：" + updated.getName() + " → " + scope.visibility.name(), detail);
        return updated;
    }

    public KnowledgeBase archive(Long id) {
        KnowledgeBase base = mapper.getKnowledgeBase(id);
        mapper.updateKnowledgeBaseStatus(id, "ARCHIVED");
        KnowledgeBase updated = mapper.getKnowledgeBase(id);
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_ARCHIVED, id,
                PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE",
                base == null ? String.valueOf(id) : base.getBaseCode(),
                "归档知识库：" + (base == null ? String.valueOf(id) : base.getName()),
                describe(updated == null ? base : updated));
        return updated;
    }

    /** Shared detail block: what every workspace event must carry to be actionable downstream. */
    private Map<String, Object> describe(KnowledgeBase base) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        if (base == null) return detail;
        detail.put("knowledgeBaseCode", base.getBaseCode());
        detail.put("name", base.getName());
        detail.put("visibility", base.getVisibility());
        detail.put("ownerGroupId", base.getOwnerGroupId());
        detail.put("ownerGroupName", base.getOwnerGroupName());
        detail.put("status", base.getStatus());
        detail.put("createdBy", base.getCreatedBy());
        return detail;
    }

    /** GROUP scope without a group would silently exclude everyone, so it degrades to PRIVATE. */
    private KnowledgeBaseScope resolveScope(String visibility, Long ownerGroupId) {
        KnowledgeBaseVisibility scope = KnowledgeBaseVisibility.of(visibility);
        Long groupId = scope == KnowledgeBaseVisibility.GROUP ? ownerGroupId : null;
        if (scope == KnowledgeBaseVisibility.GROUP && groupId == null) {
            scope = KnowledgeBaseVisibility.PRIVATE;
        }
        return new KnowledgeBaseScope(scope, groupId);
    }

    private static final class KnowledgeBaseScope {
        private final KnowledgeBaseVisibility visibility;
        private final Long ownerGroupId;

        private KnowledgeBaseScope(KnowledgeBaseVisibility visibility, Long ownerGroupId) {
            this.visibility = visibility;
            this.ownerGroupId = ownerGroupId;
        }
    }
}
