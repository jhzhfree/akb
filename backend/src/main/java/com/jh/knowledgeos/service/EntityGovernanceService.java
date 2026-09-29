package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.GraphMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator-facing entity governance: inspect canonical nodes and their surface forms, and correct
 * the entity resolution decisions that were applied automatically (design V1.2 section 5.4.1).
 *
 * <p>Entity resolution is a quality-improving step, so a wrong merge must be reversible by a human.
 * Corrections never delete rows: a merged entity is retired through its status, and the aliases move
 * to the canonical node, which keeps "where did this surface form go" answerable and keeps the
 * correction itself auditable.</p>
 */
@Service
public class EntityGovernanceService {

    /**
     * Statuses an operator may assign to a canonical node, besides retiring a wrong merge.
     */
    private static final Set<String> ALLOWED_STATUSES = new HashSet<String>(Arrays.asList("ACTIVE", "APPROVED", "EFFECTIVE", "MERGED", "DEPRECATED"));

    private final KnowledgeMapper mapper;

    private final GraphMapper graphMapper;


    private final TransactionRunner transactionRunner;

    public EntityGovernanceService(KnowledgeMapper mapper, TransactionRunner transactionRunner, GraphMapper graphMapper) {
        this.graphMapper = graphMapper;
        this.mapper = mapper;
        this.transactionRunner = transactionRunner;
    }

    /**
     * Entities of a knowledge base with alias and mention cardinality, merged ones included.
     */
    public List<Map<String, Object>> entities(Long knowledgeBaseId) {
        return graphMapper.listEntitiesWithUsage(knowledgeBaseId);
    }

    /**
     * One entity with its alias dictionary and its impact surface.
     *
     * <p>Impact matters before a correction: an operator has to see how many assertions and cards
     * will follow a merge before confirming it.</p>
     */
    public Map<String, Object> detail(Long knowledgeBaseId, String entityCode) {
        Map<String, Object> entity = requireEntity(knowledgeBaseId, entityCode);
        Map<String, Object> result = new LinkedHashMap<String, Object>(entity);
        result.put("aliases", graphMapper.listEntityAliases(knowledgeBaseId, entityCode));
        Map<String, Object> usage = findUsage(knowledgeBaseId, entityCode);
        result.put("aliasCount", usage.get("aliasCount"));
        result.put("claimCount", usage.get("claimCount"));
        result.put("cardCount", usage.get("cardCount"));
        return result;
    }

    public Map<String, Object> addAlias(Long knowledgeBaseId, String entityCode, String alias, String aliasType, Double confidence) {
        requireEntity(knowledgeBaseId, entityCode);
        String normalized = normalize(alias);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("别名不能为空");
        }
        graphMapper.upsertEntityAlias(knowledgeBaseId, entityCode, normalized, aliasType == null || aliasType.trim().isEmpty() ? "MANUAL" :
            aliasType.trim(), confidence == null ? 1.0D : confidence);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("entityCode", entityCode);
        summary.put("alias", normalized);
        summary.put("aliases", graphMapper.listEntityAliases(knowledgeBaseId, entityCode));
        return summary;
    }

    public Map<String, Object> removeAlias(Long knowledgeBaseId, String entityCode, String alias) {
        requireEntity(knowledgeBaseId, entityCode);
        String normalized = normalize(alias);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("别名不能为空");
        }
        int removed = graphMapper.deleteEntityAlias(knowledgeBaseId, entityCode, normalized);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("entityCode", entityCode);
        summary.put("alias", normalized);
        summary.put("removed", removed);
        summary.put("aliases", graphMapper.listEntityAliases(knowledgeBaseId, entityCode));
        return summary;
    }

    /**
     * Merge {@code fromCode} into {@code toCode}.
     *
     * <p>Links and aliases move first and the duplicate node is retired afterwards, so the graph
     * never exposes a node without aliases. Both steps are idempotent, which makes a retried merge
     * safe.</p>
     */
    public Map<String, Object> merge(Long knowledgeBaseId, String fromCode, String toCode) {
        if (fromCode == null || toCode == null || fromCode.trim().isEmpty() || toCode.trim().isEmpty()) {
            throw new IllegalArgumentException("合并需要源实体与目标实体");
        }
        if (fromCode.equals(toCode)) {
            throw new IllegalArgumentException("源实体与目标实体不能相同");
        }
        requireEntity(knowledgeBaseId, fromCode);
        requireEntity(knowledgeBaseId, toCode);
        Map<String, Object> impact = findUsage(knowledgeBaseId, fromCode);
        List<String> movedAliases = new ArrayList<String>();
        for (Map<String, Object> alias : graphMapper.listEntityAliases(knowledgeBaseId, fromCode)) {
            Object value = alias.get("alias");
            if (value != null) {
                movedAliases.add(String.valueOf(value));
            }
        }
        // Retried on deadlock for the same reason resolution is: the steps are idempotent and a
        // manual merge can race a batch that is retiring entities in the same knowledge base.
        transactionRunner.retryingOnDeadlock(() -> {
            graphMapper.reassignCardEntityLinks(knowledgeBaseId, fromCode, toCode);
            graphMapper.reassignClaimEntityLinks(knowledgeBaseId, fromCode, toCode);
            graphMapper.deleteConflictingAliases(knowledgeBaseId, fromCode, toCode);
            graphMapper.moveEntityAliases(knowledgeBaseId, fromCode, toCode);
            graphMapper.updateEntityStatus(knowledgeBaseId, fromCode, "MERGED");
            return null;
        });
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("knowledgeBaseId", knowledgeBaseId);
        summary.put("from", fromCode);
        summary.put("to", toCode);
        summary.put("movedAliases", movedAliases);
        summary.put("impact", impact);
        summary.put("targetAliases", graphMapper.listEntityAliases(knowledgeBaseId, toCode));
        return summary;
    }

    /**
     * Change the status of a canonical node.
     *
     * <p>Setting a merged node back to ACTIVE is the supported way to undo a wrong merge once the
     * retired aliases have been reviewed.</p>
     */
    public Map<String, Object> updateStatus(Long knowledgeBaseId, String entityCode, String status) {
        requireEntity(knowledgeBaseId, entityCode);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("不支持的实体状态：" + status);
        }
        graphMapper.updateEntityStatus(knowledgeBaseId, entityCode, normalized);
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("entityCode", entityCode);
        summary.put("status", normalized);
        return summary;
    }

    private Map<String, Object> requireEntity(Long knowledgeBaseId, String entityCode) {
        String code = entityCode == null ? "" : entityCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("实体编码不能为空");
        }
        Map<String, Object> entity = graphMapper.getEntity(knowledgeBaseId, code);
        if (entity == null || entity.isEmpty()) {
            throw new IllegalArgumentException("实体不存在：" + entityCode);
        }
        return entity;
    }

    private Map<String, Object> findUsage(Long knowledgeBaseId, String entityCode) {
        for (Map<String, Object> row : graphMapper.listEntitiesWithUsage(knowledgeBaseId)) {
            if (entityCode.equals(String.valueOf(row.get("entityCode")))) {
                return row;
            }
        }
        return new LinkedHashMap<String, Object>();
    }

    private String normalize(String alias) {
        return alias == null ? "" : alias.trim();
    }
}
