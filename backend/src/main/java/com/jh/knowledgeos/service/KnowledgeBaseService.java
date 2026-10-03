package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.KnowledgeBaseVisibility;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.common.UserRole;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.User;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeMapper mapper;

    private final IdGenerator ids;

    private final PlatformEventPublisher publisher;

    private final KnowledgeDerivationService derivation;

    public KnowledgeBaseService(KnowledgeMapper mapper, IdGenerator ids, PlatformEventPublisher publisher, KnowledgeDerivationService derivation) {
        this.mapper = mapper;
        this.ids = ids;
        this.publisher = publisher;
        this.derivation = derivation;
    }

    public List<KnowledgeBase> list() {
        User user = AuthContext.get();
        boolean all = user != null && UserRole.of(user.getRole()) == UserRole.ADMIN;
        return mapper.listKnowledgeBases(user == null ? null : user.getId(), all);
    }

    /**
     * Single workspace lookup, used by external callers bound to one knowledge base.
     */
    public KnowledgeBase get(Long id) {
        return id == null ? null : mapper.getKnowledgeBase(id);
    }

    public KnowledgeBase create(String name, String description, String visibility, Long ownerGroupId, User creator) {
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
        seedDerivationPresets(base.getId());
        Map<String, Object> detail = describe(base);
        detail.put("description", base.getDescription());
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_CREATED, base.getId(), PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE",
            base.getBaseCode(), "创建知识库：" + base.getName(), detail);
        return base;
    }

    /**
     * Edit a workspace: rename it, rewrite its purpose and move it to another sharing scope
     * in one request, so the console edits a knowledge base the way it created it.
     */
    public KnowledgeBase update(Long id, String name, String description, String visibility, Long ownerGroupId) {
        User user = AuthContext.get();
        KnowledgeBase base = mapper.getKnowledgeBase(id);
        if (base == null) {
            throw new IllegalArgumentException("知识库不存在：" + id);
        }
        if ("ARCHIVED".equalsIgnoreCase(base.getStatus())) {
            throw new IllegalStateException("已归档的知识库不可编辑");
        }
        if (!canManage(user, base)) {
            throw new SecurityException("只有知识库创建人或管理员可以编辑该知识库");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        KnowledgeBaseScope scope = visibility == null
            ? resolveScope(base.getVisibility(), base.getOwnerGroupId())
            : resolveScope(visibility, ownerGroupId);
        String previousName = base.getName();
        String previousVisibility = base.getVisibility();
        mapper.updateKnowledgeBase(id, name.trim(), description, scope.visibility.name(), scope.ownerGroupId);
        KnowledgeBase updated = mapper.getKnowledgeBase(id);
        Map<String, Object> detail = describe(updated);
        detail.put("description", updated.getDescription());
        detail.put("previousName", previousName);
        detail.put("previousVisibility", previousVisibility);
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_UPDATED, id, PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE",
            updated.getBaseCode(), "编辑知识库：" + updated.getName(), detail);
        return updated;
    }

    /**
     * Editing follows the same rule as listing: platform and knowledge administrators manage
     * every workspace, everybody else only the ones they created. A missing creator (workspace
     * seeded before ownership was recorded) stays editable by administrators only.
     */
    private boolean canManage(User user, KnowledgeBase base) {
        if (user == null) {
            return true;
        }
        if (UserRole.of(user.getRole()).canManageKnowledgeBases()) {
            return true;
        }
        return base.getCreatedBy() != null && base.getCreatedBy().equals(user.getId());
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
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_ACCESS_UPDATED, updated.getId(), PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE",
            updated.getBaseCode(), "调整知识库可见范围：" + updated.getName() + " → " + scope.visibility.name(), detail);
        return updated;
    }

    public KnowledgeBase archive(Long id) {
        KnowledgeBase base = mapper.getKnowledgeBase(id);
        mapper.updateKnowledgeBaseStatus(id, "ARCHIVED");
        KnowledgeBase updated = mapper.getKnowledgeBase(id);
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_BASE_ARCHIVED, id, PlatformEventPublisher.SOURCE_UI, "KNOWLEDGE_BASE", base == null ?
            String.valueOf(id) : base.getBaseCode(), "归档知识库：" + (base == null ? String.valueOf(id) : base.getName()), describe(updated == null ? base
            : updated));
        return updated;
    }

    /**
     * A new workspace opens with the derivation rules almost everyone ends up wanting —
     * deployment chains, dependency impact, ownership — because a derivation console that
     * starts empty is a console nobody starts. They arrive disabled-by-schedule (manual
     * trigger) and switched on, so nothing runs unattended before anyone asked for it.
     *
     * <p>Seeding is idempotent and best effort: a workspace that could not be seeded still
     * works, and the same rules can be installed later from the console.</p>
     */
    private void seedDerivationPresets(Long kb) {
        try {
            derivation.seed(kb, null);
        } catch (RuntimeException e) {
            LOG.warn("知识库 {} 启用常用衍生规则失败：{}", kb, e.getMessage());
        }
    }

    /**
     * Shared detail block: what every workspace event must carry to be actionable downstream.
     */
    private Map<String, Object> describe(KnowledgeBase base) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        if (base == null) {
            return detail;
        }
        detail.put("knowledgeBaseCode", base.getBaseCode());
        detail.put("name", base.getName());
        detail.put("visibility", base.getVisibility());
        detail.put("ownerGroupId", base.getOwnerGroupId());
        detail.put("ownerGroupName", base.getOwnerGroupName());
        detail.put("status", base.getStatus());
        detail.put("createdBy", base.getCreatedBy());
        return detail;
    }

    /**
     * GROUP scope without a group would silently exclude everyone, so it degrades to PRIVATE.
     */
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
