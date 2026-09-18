package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity resolution (Entity Resolution) orchestration described by design V1.1 section 5.4.
 *
 * <p>The AI service decides which mentions describe the same real-world entity. This service
 * applies those decisions to authoritative storage: duplicate entity rows are marked MERGED so
 * the graph only exposes canonical nodes, mention links (card/claim) are re-pointed, and the
 * surface form is stored as an alias for future dictionary-based matching.</p>
 *
 * <p>Entity resolution is a quality-improving step, never a correctness gate: if the AI service
 * is unavailable the batch is reported degraded and ingestion continues with unresolved entities.</p>
 */
@Service
public class EntityResolutionService {
    private final KnowledgeMapper mapper;
    private final AiClient aiClient;
    private final TransactionRunner transactionRunner;

    public EntityResolutionService(
            KnowledgeMapper mapper, AiClient aiClient, TransactionRunner transactionRunner) {
        this.mapper = mapper;
        this.aiClient = aiClient;
        this.transactionRunner = transactionRunner;
    }

    /**
     * Resolve extracted mentions onto canonical entities and rewrite the authoritative links.
     *
     * @return summary of applied merges/aliases, or a degraded summary when the AI service fails
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveAndMerge(Long knowledgeBaseId, Object entitiesObject) {
        List<Map<String, Object>> mentions = new ArrayList<Map<String, Object>>();
        if (entitiesObject instanceof List) {
            for (Object item : (List<Object>) entitiesObject) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<String, Object> entity = (Map<String, Object>) item;
                String code = stringValue(first(entity, "entity_code", "code"));
                String name = stringValue(first(entity, "canonical_name", "name"));
                if (code.isEmpty() || name.isEmpty()) {
                    continue;
                }
                Map<String, Object> mention = new LinkedHashMap<String, Object>();
                mention.put("code", code);
                mention.put("name", name);
                mention.put("type", stringValue(first(entity, "entity_type", "type")));
                mentions.add(mention);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("knowledgeBaseId", knowledgeBaseId);
        summary.put("mentions", mentions.size());
        if (mentions.isEmpty()) {
            summary.put("merged", 0);
            summary.put("aliases", 0);
            return summary;
        }

        List<Map<String, Object>> canonical = mapper.listEntityNames(knowledgeBaseId);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("knowledgeBaseId", knowledgeBaseId);
        request.put("entities", mentions);
        request.put("canonicalEntities", canonical);

        Map<String, Object> result;
        try {
            result = aiClient.post(AiApiPaths.ENTITY_RESOLVE, request);
        } catch (RuntimeException ex) {
            summary.put("degraded", true);
            summary.put("reason", "实体归一服务不可用：" + ex.getMessage());
            summary.put("merged", 0);
            summary.put("aliases", 0);
            return summary;
        }

        Object mergesObject = result.get("merges");
        Object aliasesObject = result.get("aliases");
        List<Map<String, Object>> merges = asMaps(mergesObject);
        List<Map<String, Object>> aliases = asMaps(aliasesObject);

        transactionRunner.required(() -> {
            for (Map<String, Object> merge : merges) {
                String from = stringValue(merge.get("from"));
                String to = stringValue(merge.get("to"));
                if (from.isEmpty() || to.isEmpty() || from.equals(to)) {
                    continue;
                }
                // Re-point every object link first, then retire the duplicate node.
                mapper.mergeEntityLinks(knowledgeBaseId, from, to);
                mapper.mergeClaimEntityLinks(knowledgeBaseId, from, to);
                mapper.updateEntityStatus(knowledgeBaseId, from, "MERGED");
            }
            for (Map<String, Object> alias : aliases) {
                String entityCode = stringValue(alias.get("entityCode"));
                String aliasValue = stringValue(alias.get("alias"));
                if (entityCode.isEmpty() || aliasValue.isEmpty()) {
                    continue;
                }
                mapper.upsertEntityAlias(
                        knowledgeBaseId,
                        entityCode,
                        aliasValue,
                        stringValue(alias.get("aliasType")),
                        doubleValue(alias.get("confidence"), 0.0D));
            }
            return null;
        });

        summary.put("merged", merges.size());
        summary.put("aliases", aliases.size());
        summary.put("mergeDetails", merges);
        summary.put("degraded", false);
        return summary;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMaps(Object value) {
        List<Map<String, Object>> output = new ArrayList<Map<String, Object>>();
        if (value instanceof List) {
            for (Object item : (List<Object>) value) {
                if (item instanceof Map) {
                    output.add((Map<String, Object>) item);
                }
            }
        }
        return output;
    }

    private Object first(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.get(key) != null) {
                return source.get(key);
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleValue(Object value, Double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
