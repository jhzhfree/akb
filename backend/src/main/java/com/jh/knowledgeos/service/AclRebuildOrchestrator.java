package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.IndexMapper;
import com.jh.knowledgeos.mapper.GraphMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates ACL-triggered authoritative rebuild, qualification barrier, and activation.
 */
@Service
public class AclRebuildOrchestrator {

    private final KnowledgeMapper mapper;

    private final GraphMapper graphMapper;

    private final IndexMapper indexMapper;


    private final IndexSchemaService schemaService;

    private final ObjectMapper objectMapper;

    private final AiClient aiClient;

    public AclRebuildOrchestrator(KnowledgeMapper mapper, IndexSchemaService schemaService, ObjectMapper objectMapper, AiClient aiClient, GraphMapper
        graphMapper, IndexMapper indexMapper) {
        this.graphMapper = graphMapper;
        this.indexMapper = indexMapper;
        this.mapper = mapper;
        this.schemaService = schemaService;
        this.objectMapper = objectMapper;
        this.aiClient = aiClient;
    }

    @Transactional
    public Map<String, Object> start(String changeCode) {
        Map<String, Object> change = mapper.getAclChange(changeCode);
        if (change == null || !"REBUILD_REQUIRED".equals(String.valueOf(change.get("propagationState")))) {
            throw new IllegalArgumentException("ACL change is not REBUILD_REQUIRED");
        }
        Long kb = Long.valueOf(String.valueOf(change.get("knowledgeBaseId")));
        String jobCode = "ACLR-" + UUID.randomUUID().toString();
        String version = "acl-" + System.currentTimeMillis();
        mapper.insertAclRebuildJob(jobCode, changeCode, kb, version);
        mapper.updateAclRebuildJob(jobCode, "REBUILDING", null, null, null, null);
        try {
            Map<String, Object> rebuild = schemaService.rebuild(kb, version);
            String releaseId = String.valueOf(rebuild.get("releaseId"));
            mapper.updateAclRebuildJob(jobCode, "VALIDATING", releaseId, "PENDING", null, null);
            return mapper.getAclRebuildJob(jobCode);
        } catch (RuntimeException ex) {
            mapper.updateAclRebuildJob(jobCode, "FAILED", null, "FAILED", null, abbreviate(ex.getMessage()));
            throw ex;
        }
    }

    @Transactional
    public Map<String, Object> qualify(String jobCode) {
        Map<String, Object> job = requireJob(jobCode);
        if (!"VALIDATING".equals(String.valueOf(job.get("state")))) {
            throw new IllegalArgumentException("ACL rebuild job is not VALIDATING");
        }
        Long kb = Long.valueOf(String.valueOf(job.get("knowledgeBaseId")));
        String schemaVersion = String.valueOf(job.get("schemaVersion"));
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("schemaVersion", schemaVersion);
        com.jh.knowledgeos.domain.IndexSchemaRelease release = indexMapper.findIndexSchemaRelease(String.valueOf(job.get("releaseId")));
        request.put("expectedManifestHash", release == null ? null : release.getSnapshotChecksum());
        request.put("expectedManifestCount", release == null ? null : release.getSnapshotCount());
        Map<String, Object> graphManifest = graphMapper.getGraphManifest(String.valueOf(job.get("releaseId")));
        request.put("expectedGraphManifestHash", graphManifest == null ? null : graphManifest.get("manifestHash"));
        request.put("expectedGraphEntityCount", graphManifest == null ? null : graphManifest.get("entityCount"));
        request.put("expectedGraphRelationCount", graphManifest == null ? null : graphManifest.get("relationCount"));
        Map<String, Object> evidence = aiClient.post(AiApiPaths.INDEX_SCHEMA_ACL_QUALIFY_PREFIX + kb, request);
        boolean passed = Boolean.TRUE.equals(evidence.get("qualified"));
        try {
            String json = objectMapper.writeValueAsString(evidence);
            mapper.updateAclRebuildJob(jobCode, passed ? "READY" : "FAILED", null, passed ? "PASSED" : "FAILED", json, passed ? null :
                "Machine ACL leakage qualification failed");
            return mapper.getAclRebuildJob(jobCode);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot persist ACL qualification", ex);
        }
    }

    @Transactional
    public Map<String, Object> activate(String jobCode) {
        Map<String, Object> job = requireJob(jobCode);
        if (!"READY".equals(String.valueOf(job.get("state"))) || !"PASSED".equals(String.valueOf(job.get("qualificationState")))) {
            throw new IllegalArgumentException("ACL rebuild activation barrier not satisfied");
        }
        Long kb = Long.valueOf(String.valueOf(job.get("knowledgeBaseId")));
        String releaseId = String.valueOf(job.get("releaseId"));
        mapper.updateAclRebuildJob(jobCode, "ACTIVATING", null, null, null, null);
        schemaService.activate(kb, releaseId);
        mapper.updateAclRebuildJob(jobCode, "COMPLETED", null, null, null, null);
        mapper.updateAclChangePropagation(String.valueOf(job.get("changeCode")), "COMPLETED", "{\"aclRebuildJob\":\"" + jobCode +
            "\",\"releaseId\":\"" + releaseId + "\"}", null);
        return mapper.getAclRebuildJob(jobCode);
    }

    public List<Map<String, Object>> list(Long kb, Integer limit) {
        return mapper.listAclRebuildJobs(kb, Math.min(100, Math.max(1, limit == null ? 30 : limit)));
    }

    private Map<String, Object> requireJob(String jobCode) {
        Map<String, Object> job = mapper.getAclRebuildJob(jobCode);
        if (job == null) {
            throw new IllegalArgumentException("ACL rebuild job not found");
        }
        return job;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
