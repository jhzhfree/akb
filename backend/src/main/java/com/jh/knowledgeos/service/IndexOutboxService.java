package com.jh.knowledgeos.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.IndexOutboxStatus;
import com.jh.knowledgeos.domain.IndexOutboxEvent;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates versioned, idempotent transactional outbox events and exposes replay/skip operations.
 */
@Service
public class IndexOutboxService {
    private final KnowledgeMapper mapper;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public IndexOutboxService(
            KnowledgeMapper mapper,
            ObjectMapper objectMapper,
            @Value("${indexing.outbox.max-attempts:8}") int maxAttempts) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    /**
     * Appends an idempotent event and allocates the next aggregate version inside the
     * caller transaction. Duplicate idempotency keys return the existing event.
     */
    public Map<String, Object> enqueue(
            Long kb,
            String aggregateType,
            String aggregateKey,
            String eventType,
            String targetPath,
            Map<String, Object> payload,
            String idempotencyKey) {
        IndexOutboxEvent existing = mapper.findOutboxByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return summary(existing, true);
        }

        mapper.incrementIndexAggregateVersion(aggregateKey);
        Long version = mapper.getIndexAggregateVersion(aggregateKey);
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> versionedPayload = new LinkedHashMap<String, Object>(payload);
        versionedPayload.put("eventId", eventId);
        versionedPayload.put("indexVersion", version);

        IndexOutboxEvent event = new IndexOutboxEvent();
        event.setEventId(eventId);
        event.setIdempotencyKey(idempotencyKey);
        event.setKnowledgeBaseId(kb);
        event.setAggregateType(aggregateType);
        event.setAggregateKey(aggregateKey);
        event.setEventType(eventType);
        event.setObjectVersion(version);
        event.setTargetPath(targetPath);
        event.setPayloadJson(writeJson(versionedPayload));
        event.setStatus(IndexOutboxStatus.PENDING.name());
        event.setAttemptCount(0);
        event.setMaxAttempts(maxAttempts);
        int inserted = mapper.insertIndexOutbox(event);
        if (inserted == 0) {
            IndexOutboxEvent raced = mapper.findOutboxByIdempotencyKey(idempotencyKey);
            if (raced != null) {
                return summary(raced, true);
            }
            throw new IllegalStateException("Outbox insert ignored but existing event was not found");
        }
        return summary(event, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> enqueueObjectStatus(
            Long kb, String objectType, String objectCode, String status, String operationId) {
        String type = objectType == null ? "CARD" : objectType.toUpperCase();
        String aggregateKey = "KB:" + kb + ":" + type + ":" + objectCode;
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("knowledgeBaseId", kb);
        payload.put("objectType", type);
        payload.put("objectId", objectCode);
        payload.put("status", status);
        return enqueue(
                kb,
                type,
                aggregateKey,
                "OBJECT_STATUS_CHANGED",
                AiApiPaths.INDEX_OBJECT_STATUS,
                payload,
                "STATUS:" + aggregateKey + ":" + status + ":"
                        + (operationId == null || operationId.trim().isEmpty()
                                ? UUID.randomUUID().toString() : operationId));
    }

    public List<IndexOutboxEvent> list(Long kb, String status, Integer limit) {
        int normalized = limit == null ? 100 : Math.min(500, Math.max(1, limit));
        return mapper.listIndexOutbox(kb, status, normalized);
    }

    public Map<String, Object> stats(Long kb) {
        return mapper.indexOutboxStats(kb);
    }

    /** Requeue a failed event without changing its aggregate ordering/version. */
    public Map<String, Object> replay(String eventId) {
        mapper.replayIndexOutbox(eventId);
        IndexOutboxEvent event = mapper.findOutboxByEventId(eventId);
        return event == null ? new LinkedHashMap<String, Object>() : summary(event, false);
    }

    /** Explicitly skip an unrecoverable event so later aggregate versions may proceed. */
    public Map<String, Object> skip(String eventId) {
        mapper.skipIndexOutbox(eventId);
        IndexOutboxEvent event = mapper.findOutboxByEventId(eventId);
        return event == null ? new LinkedHashMap<String, Object>() : summary(event, false);
    }

    public Map<String, Object> drift(Long kb, AiClient aiClient) {
        Map<String, Object> external = aiClient.get(AiApiPaths.INDEX_EXTERNAL_STATS_PREFIX + kb);
        long authoritative = mapper.countIndexableObjects(kb);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("authoritativeCount", authoritative);
        result.put("external", external);
        Object os = external == null ? null : external.get("elasticsearch");
        Object qd = external == null ? null : external.get("milvus");
        result.put("elasticsearchDelta", delta(authoritative, os));
        result.put("milvusDelta", delta(authoritative, qd));
        return result;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> payload(IndexOutboxEvent event) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    event.getPayloadJson(),
                    new TypeReference<Map<String, Object>>() {});
            if ("DOCUMENT_CHUNKS_UPSERT".equals(event.getEventType())) {
                return hydrateDocumentPayload(event, payload);
            }
            return payload;
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid outbox payload: " + event.getEventId(), ex);
        }
    }

    private Map<String, Object> hydrateDocumentPayload(
            IndexOutboxEvent event, Map<String, Object> reference) {
        Long documentId = Long.valueOf(String.valueOf(reference.get("documentId")));
        Map<String, Object> header = mapper.getDocumentIndexHeader(documentId);
        if (header == null) {
            throw new IllegalStateException("Document not found for index event: " + documentId);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>(header);
        payload.put("eventId", event.getEventId());
        payload.put("indexVersion", event.getObjectVersion());
        payload.put("chunks", mapper.listDocumentIndexChunks(documentId));
        return payload;
    }

    private Long delta(long authoritative, Object target) {
        if (!(target instanceof Map)) {
            return null;
        }
        Object count = ((Map<String, Object>) target).get("count");
        if (count == null) {
            return null;
        }
        return Long.valueOf(String.valueOf(count)) - authoritative;
    }

    private Map<String, Object> summary(IndexOutboxEvent event, boolean duplicate) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("accepted", true);
        result.put("duplicate", duplicate);
        result.put("eventId", event.getEventId());
        result.put("status", event.getStatus());
        result.put("aggregateKey", event.getAggregateKey());
        result.put("version", event.getObjectVersion());
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Serialize outbox payload failed", ex);
        }
    }
}
