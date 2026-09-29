package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.GraphMapper;
import com.jh.knowledgeos.projection.AuthoritativeGraphSnapshotRow;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Entity/Relation graph from MySQL and records its graph manifest.
 */
@Service
public class AuthoritativeGraphGenerationService {

    private final KnowledgeMapper mapper;

    private final GraphMapper graphMapper;


    private final AiClient ai;

    private final ObjectMapper json;

    public AuthoritativeGraphGenerationService(KnowledgeMapper mapper, AiClient ai, ObjectMapper json, GraphMapper graphMapper) {
        this.graphMapper = graphMapper;
        this.mapper = mapper;
        this.ai = ai;
        this.json = json;
    }

    /**
     * Rebuild one green graph generation without changing its active pointer.
     */
    public Map<String, Object> rebuild(Long kb, String generation, String releaseId) {
        try {
            List<AuthoritativeGraphSnapshotRow> rows = graphMapper.listAuthoritativeGraphSnapshot(kb);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            MessageDigest facetDigest = MessageDigest.getInstance("SHA-256");
            long entityFacetCount = 0L;
            long relationFacetCount = 0L;
            List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();
            for (AuthoritativeGraphSnapshotRow row : rows) {
                Map<String, Object> item = toPayload(row);
                byte[] canonical = (json.writeValueAsString(item) + "\n").getBytes(StandardCharsets.UTF_8);
                digest.update(canonical);
                if ("ENTITY_FACET".equals(row.getGraphType()) || "RELATION_FACET".equals(row.getGraphType())) {
                    facetDigest.update(canonical);
                    if ("ENTITY_FACET".equals(row.getGraphType())) {
                        entityFacetCount++;
                    } else {
                        relationFacetCount++;
                    }
                }
                payload.add(item);
            }
            String hash = hex(digest.digest());
            long entityCount = graphMapper.countAuthoritativeGraphEntities(kb);
            long relationCount = graphMapper.countAuthoritativeGraphRelations(kb);
            Map<String, Object> request = new LinkedHashMap<String, Object>();
            request.put("generationId", generation);
            request.put("manifestHash", hash);
            request.put("entitiesAndRelations", payload);
            List<Map<String, Object>> bindings = graphMapper.listAuthoritativeGraphEvidenceBindings(kb);
            MessageDigest evidenceDigest = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> binding : bindings) {
                evidenceDigest.update((json.writeValueAsString(binding) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            request.put("evidenceBindings", bindings);
            Map<String, Object> response = ai.post(AiApiPaths.INDEX_SCHEMA_GRAPH_REBUILD_PREFIX + kb, request);
            graphMapper.insertGraphManifest(releaseId, kb, generation, hash, entityCount, relationCount);
            graphMapper.insertGraphFacetManifest(releaseId, kb, generation, hex(facetDigest.digest()), entityFacetCount, relationFacetCount);
            graphMapper.insertGraphEvidenceManifest(releaseId, kb, generation, hex(evidenceDigest.digest()), (long) bindings.size(), (long) bindings.size());
            response.put("expectedManifestHash", hash);
            response.put("expectedEntityCount", entityCount);
            response.put("expectedRelationCount", relationCount);
            response.put("expectedEvidenceBindingCount", bindings.size());
            return response;
        } catch (Exception ex) {
            throw new IllegalStateException("authoritative graph rebuild failed", ex);
        }
    }

    private Map<String, Object> toPayload(AuthoritativeGraphSnapshotRow row) throws Exception {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("graph_key", row.getGraphKey());
        item.put("graph_type", row.getGraphType());
        item.put("code", row.getCode());
        item.put("source_code", row.getSourceCode());
        item.put("target_code", row.getTargetCode());
        item.put("semantic_type", row.getSemanticType());
        item.put("name", row.getName());
        item.put("description", row.getDescription());
        item.put("status", row.getStatus());
        item.put("permission_tags", tags(row.getPermissionTags()));
        item.put("content_hash", row.getContentHash());
        return item;
    }

    private List<String> tags(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return json.readValue(raw, new TypeReference<List<String>>() {
        });
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }
}
