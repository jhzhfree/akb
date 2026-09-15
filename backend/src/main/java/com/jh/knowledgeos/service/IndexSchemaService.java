package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.domain.IndexSchemaRelease;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates authoritative MySQL snapshot rebuilds, integrity validation, blue/green activation, and rollback.
 */
@Service
public class IndexSchemaService {
    private static final int SNAPSHOT_PAGE_SIZE = 500;
    private final KnowledgeMapper mapper;
    private final AiClient ai;
    private final ObjectMapper objectMapper;

    public IndexSchemaService(KnowledgeMapper mapper, AiClient ai, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.ai = ai;
        this.objectMapper = objectMapper;
    }

    /**
     * Builds a green retrieval schema from a paged MySQL authoritative snapshot and
     * refuses activation when count or source high-watermark validation fails.
     */
    public Map<String, Object> rebuild(Long kb, String schemaVersion) {
        String version = normalize(schemaVersion);
        String releaseId = UUID.randomUUID().toString();
        long highWatermark = mapper.maxIndexOutboxId(kb);
        long expected = mapper.countIndexableObjects(kb);
        IndexSchemaRelease release = new IndexSchemaRelease();
        release.setReleaseId(releaseId);
        release.setKnowledgeBaseId(kb);
        release.setSchemaVersion(version);
        release.setState("BUILDING");
        release.setExpectedCount(expected);
        mapper.insertIndexSchemaRelease(release);

        try {
            SnapshotResult snapshot = streamAuthoritativeSnapshot(kb, version, releaseId);
            long endWatermark = mapper.maxIndexOutboxId(kb);
            boolean stable = highWatermark == endWatermark;
            boolean countsMatch = snapshot.count == expected
                    && snapshot.elasticsearchCount == expected
                    && snapshot.milvusCount == expected;
            String integrityState = stable && countsMatch ? "VERIFIED" : stable ? "COUNT_MISMATCH" : "SOURCE_CHANGED";
            mapper.updateIndexSchemaIntegrity(
                    releaseId,
                    snapshot.checksum,
                    highWatermark,
                    endWatermark,
                    snapshot.count,
                    integrityState);
            Map<String, Object> validation = map(
                    "releaseId", releaseId,
                    "snapshotChecksum", snapshot.checksum,
                    "snapshotCount", snapshot.count,
                    "sourceHighWatermark", highWatermark,
                    "sourceEndWatermark", endWatermark,
                    "sourceStable", stable,
                    "elasticsearchCount", snapshot.elasticsearchCount,
                    "milvusCount", snapshot.milvusCount);
            String state = stable && countsMatch ? "READY" : "VALIDATION_FAILED";
            mapper.completeIndexSchemaRelease(
                    releaseId,
                    state,
                    snapshot.elasticsearchCount,
                    snapshot.milvusCount,
                    snapshot.elasticsearchTarget,
                    snapshot.milvusTarget,
                    write(validation),
                    "READY".equals(state) ? null : integrityState);
            if (!"READY".equals(state)) {
                throw new IllegalStateException("Index snapshot integrity validation failed: " + integrityState);
            }
            validation.put("state", state);
            validation.put("schemaVersion", version);
            return validation;
        } catch (Exception ex) {
            IndexSchemaRelease current = mapper.findIndexSchemaRelease(releaseId);
            if (current != null && !"VALIDATION_FAILED".equals(current.getState())) {
                mapper.completeIndexSchemaRelease(
                        releaseId, "FAILED", null, null, null, null, null, abbreviate(ex.getMessage()));
            }
            throw new IllegalStateException("Index schema rebuild failed", ex);
        }
    }

    private SnapshotResult streamAuthoritativeSnapshot(Long kb, String version, String releaseId) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long offset = 0L;
        int pageNo = 0;
        long elasticsearchCount = 0L;
        long milvusCount = 0L;
        long snapshotCount = 0L;
        String elasticsearchTarget = null;
        String milvusTarget = null;
        boolean reset = true;
        // Offset paging is intentionally bounded to keep JVM heap stable during large rebuilds.
        while (true) {
            List<AuthoritativeIndexSnapshotRow> rows = mapper.listAuthoritativeIndexSnapshot(kb, offset, SNAPSHOT_PAGE_SIZE);
            if (rows.isEmpty()) {
                break;
            }
            pageNo++;
            String pageChecksum = checksumPage(rows);
            digest.update(pageChecksum.getBytes(StandardCharsets.UTF_8));
            mapper.insertIndexSnapshotAudit(
                    releaseId,
                    kb,
                    pageNo,
                    rows.size(),
                    pageChecksum,
                    rows.get(0).getObjectKey(),
                    rows.get(rows.size() - 1).getObjectKey());
            Map<String, Object> request = map(
                    "schemaVersion", version,
                    "releaseId", releaseId,
                    "reset", reset,
                    "documents", toPayloads(rows));
            Map<String, Object> response = ai.post(
                    AiApiPaths.INDEX_SCHEMA_REBUILD_BATCH_PREFIX + kb,
                    request);
            elasticsearchCount += longValue(response, "elasticsearchCount", 0L);
            milvusCount += longValue(response, "milvusCount", 0L);
            elasticsearchTarget = nestedString(response, "elasticsearch", "target", elasticsearchTarget);
            milvusTarget = nestedString(response, "milvus", "target", milvusTarget);
            snapshotCount += rows.size();
            offset += rows.size();
            reset = false;
        }
        return new SnapshotResult(
                hex(digest.digest()), snapshotCount, elasticsearchCount, milvusCount, elasticsearchTarget, milvusTarget);
    }

    /** Activate a READY/ACTIVE/RETIRED release by switching external read aliases. */
    public Map<String, Object> activate(Long kb, String releaseId) {
        IndexSchemaRelease release = requireRelease(kb, releaseId);
        Map<String, Object> response = ai.post(
                AiApiPaths.INDEX_SCHEMA_ACTIVATE_PREFIX + kb,
                map("schemaVersion", release.getSchemaVersion(), "releaseId", releaseId));
        mapper.activateIndexSchemaRelease(kb, releaseId);
        return response;
    }

    /** Roll back by re-activating a retained previous release; no full rebuild is required. */
    public Map<String, Object> rollback(Long kb, String releaseId) {
        return activate(kb, releaseId);
    }

    public List<IndexSchemaRelease> list(Long kb) {
        return mapper.listIndexSchemaReleases(kb, 20);
    }

    private IndexSchemaRelease requireRelease(Long kb, String releaseId) {
        IndexSchemaRelease release = mapper.findIndexSchemaRelease(releaseId);
        if (release == null || !kb.equals(release.getKnowledgeBaseId())) {
            throw new IllegalArgumentException("schema release not found");
        }
        if (!("READY".equals(release.getState())
                || "ACTIVE".equals(release.getState())
                || "RETIRED".equals(release.getState()))) {
            throw new IllegalArgumentException("schema release is not activatable: " + release.getState());
        }
        return release;
    }

    private List<Map<String, Object>> toPayloads(List<AuthoritativeIndexSnapshotRow> rows) {
        List<Map<String, Object>> payloads = new ArrayList<Map<String, Object>>(rows.size());
        for (AuthoritativeIndexSnapshotRow row : rows) {
            payloads.add(row.toPayload());
        }
        return payloads;
    }

    private String checksumPage(List<AuthoritativeIndexSnapshotRow> rows) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Map<String, Object>> canonical = new ArrayList<Map<String, Object>>(rows.size());
        for (AuthoritativeIndexSnapshotRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("objectKey", row.getObjectKey());
            item.put("contentHash", row.getContentHash());
            item.put("status", row.getStatus());
            canonical.add(item);
        }
        digest.update(objectMapper.writeValueAsBytes(canonical));
        return hex(digest.digest());
    }

    private String normalize(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,40}")) {
            throw new IllegalArgumentException("invalid schemaVersion");
        }
        return value.toLowerCase();
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null ? fallback : Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private String nestedString(Map<String, Object> map, String parent, String key, String fallback) {
        Object value = map == null ? null : map.get(parent);
        if (!(value instanceof Map)) {
            return fallback;
        }
        Object nested = ((Map<String, Object>) value).get(key);
        return nested == null ? fallback : String.valueOf(nested);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.substring(0, Math.min(4000, value.length()));
    }

    private String hex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format("%02x", b & 0xff));
        }
        return builder.toString();
    }

    private static final class SnapshotResult {
        private final String checksum;
        private final long count;
        private final long elasticsearchCount;
        private final long milvusCount;
        private final String elasticsearchTarget;
        private final String milvusTarget;

        private SnapshotResult(
                String checksum,
                long count,
                long elasticsearchCount,
                long milvusCount,
                String elasticsearchTarget,
                String milvusTarget) {
            this.checksum = checksum;
            this.count = count;
            this.elasticsearchCount = elasticsearchCount;
            this.milvusCount = milvusCount;
            this.elasticsearchTarget = elasticsearchTarget;
            this.milvusTarget = milvusTarget;
        }
    }
}
