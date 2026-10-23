package com.jh.knowledgeos.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jh.knowledgeos.common.IndexOutboxStatus;
import com.jh.knowledgeos.domain.IndexOutboxEvent;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.mapper.IndexMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Asynchronous worker that claims due outbox events, delivers index updates, and applies retry/dead-letter policy.
 */
@Service
public class IndexOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexOutboxWorker.class);

    private final KnowledgeMapper mapper;

    private final IndexMapper indexMapper;

    private final DocumentMapper documentMapper;


    private final IndexOutboxService outboxService;

    private final AiClient aiClient;

    private final ObjectMapper objectMapper;

    private final TransactionRunner transactionRunner;

    private final IndexOutboxMetrics metrics;

    private final int batchSize;

    private final int lockTimeoutSeconds;

    private final long retryBaseSeconds;

    private final boolean enabled;

    private final String workerId;

    public IndexOutboxWorker(KnowledgeMapper mapper, IndexOutboxService outboxService, AiClient aiClient, ObjectMapper objectMapper, TransactionRunner
        transactionRunner, IndexOutboxMetrics metrics, @Value("${indexing.outbox.enabled:true}") boolean enabled,
        @Value("${indexing.outbox.batch-size:20}") int batchSize, @Value("${indexing.outbox.lock-timeout-seconds:300}") int lockTimeoutSeconds,
        @Value("${indexing.outbox.retry-base-seconds:2}") long retryBaseSeconds, IndexMapper indexMapper, DocumentMapper documentMapper) {
        this.indexMapper = indexMapper;
        this.documentMapper = documentMapper;
        this.mapper = mapper;
        this.outboxService = outboxService;
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.transactionRunner = transactionRunner;
        this.metrics = metrics;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(200, batchSize));
        this.lockTimeoutSeconds = Math.max(30, lockTimeoutSeconds);
        this.retryBaseSeconds = Math.max(1L, retryBaseSeconds);
        this.workerId = buildWorkerId();
    }

    /**
     * Polls a bounded batch of due events. Claiming happens in a short transaction;
     * external delivery always occurs after the database lock has been released.
     */
    @Scheduled(fixedDelayString = "${indexing.outbox.poll-interval-ms:1000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        recoverStaleLocks();
        metrics.updateBacklog(indexMapper.indexOutboxGlobalStats());
        List<IndexOutboxEvent> events = claimBatch();
        for (IndexOutboxEvent event : events) {
            deliver(event);
        }
    }

    private List<IndexOutboxEvent> claimBatch() {
        return transactionRunner.required(() -> {
            List<IndexOutboxEvent> due = indexMapper.selectDueIndexOutbox(batchSize);
            List<IndexOutboxEvent> claimed = new ArrayList<IndexOutboxEvent>(due.size());
            for (IndexOutboxEvent event : due) {
                int updated = indexMapper.claimIndexOutbox(event.getId(), workerId);
                if (updated == 1) {
                    event.setStatus(IndexOutboxStatus.PROCESSING.name());
                    event.setAttemptCount((event.getAttemptCount() == null ? 0 : event.getAttemptCount()) + 1);
                    claimed.add(event);
                }
            }
            return claimed;
        });
    }

    private void deliver(IndexOutboxEvent event) {
        long started = System.currentTimeMillis();
        try {
            Map<String, Object> response = aiClient.post(event.getTargetPath(), outboxService.payload(event));
            failOnNestedError(response);
            long duration = System.currentTimeMillis() - started;
            String responseJson = objectMapper.writeValueAsString(response);
            transactionRunner.required(() -> {
                indexMapper.markIndexOutboxSucceeded(event.getId(), responseJson);
                refreshDocuments(event);
                updateAclPropagationAfterSuccess(event, response, responseJson);
                indexMapper.insertIndexDeliveryAttempt(event.getEventId(), event.getAttemptCount(), "SUCCEEDED", duration, responseJson, null);
                metrics.deliverySucceeded(duration);
            });
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - started;
            handleFailure(event, duration, ex);
        }
    }

    private void handleFailure(IndexOutboxEvent event, long duration, Exception ex) {
        int attempt = event.getAttemptCount() == null ? 1 : event.getAttemptCount();
        int maxAttempts = event.getMaxAttempts() == null ? 8 : event.getMaxAttempts();
        boolean dead = attempt >= maxAttempts;
        long delay = Math.min(900L, retryBaseSeconds * (1L << Math.min(10, Math.max(0, attempt - 1))));
        LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(delay);
        String message = abbreviate(ex.getMessage(), 4000);
        transactionRunner.required(() -> {
            if (dead) {
                indexMapper.markIndexOutboxDead(event.getId(), message);
                updateAclPropagation(event, "FAILED", null, message);
                // An index failure must not touch the authoritative model: the document is only
                // flagged DIRTY so a rebuild can pick it up, and the knowledge it produced stays
                // queryable from the relational store (design §16.3).
                markDocumentDirty(event, message);
            } else {
                indexMapper.markIndexOutboxRetry(event.getId(), message, nextAttempt);
            }
            refreshDocuments(event);
            indexMapper.insertIndexDeliveryAttempt(event.getEventId(), attempt, dead ? "DEAD" : "RETRY", duration, null, message);
            metrics.deliveryFailed(duration, dead);
        });
        log.warn("Index outbox delivery failed eventId={} attempt={}/{} status={}: {}", event.getEventId(), attempt, maxAttempts, dead ? "DEAD" :
            "RETRY", message);
    }

    private void refreshDocuments(IndexOutboxEvent event) {
        List<Long> documents = documentMapper.documentsForIndexAggregate(event.getKnowledgeBaseId(), event.getAggregateKey());
        for (Long document : documents) { documentMapper.refreshDocumentIndexStatus(document); }
    }

    private void updateAclPropagationAfterSuccess(IndexOutboxEvent event, Map<String, Object> response, String responseJson) {
        if (!"ACL_CHANGED".equals(event.getEventType())) {
            return;
        }
        boolean rebuild = Boolean.TRUE.equals(response.get("requiresAuthoritativeRebuild"));
        String state = rebuild ? "REBUILD_REQUIRED" : "COMPLETED";
        updateAclPropagation(event, state, responseJson, null);
    }

    private void updateAclPropagation(IndexOutboxEvent event, String state, String resultJson, String lastError) {
        if (!"ACL_CHANGED".equals(event.getEventType())) {
            return;
        }
        mapper.updateAclChangePropagation(event.getIdempotencyKey(), state, resultJson, lastError);
    }

    /**
     * Flags the owning document DIRTY when its index update is dead-lettered.
     */
    private void markDocumentDirty(IndexOutboxEvent event, String message) {
        Long documentId = documentIdOf(event.getAggregateKey());
        if (documentId == null) {
            return;
        }
        documentMapper.updateDocumentState(documentId, "DIRTY", "INDEX", abbreviate(message, 900), 0);
    }

    /**
     * Aggregate keys look like "KB:1:DOCUMENT:12"; the segment after DOCUMENT is the object id.
     */
    private Long documentIdOf(String aggregateKey) {
        if (aggregateKey == null) {
            return null;
        }
        String[] parts = aggregateKey.split(":");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("DOCUMENT".equalsIgnoreCase(parts[i])) {
                try {
                    return Long.valueOf(parts[i + 1].trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private void recoverStaleLocks() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(lockTimeoutSeconds);
        int recovered = indexMapper.recoverStaleIndexOutbox(threshold);
        if (recovered > 0) {
            metrics.staleRecovered(recovered);
            log.warn("Recovered {} stale index outbox locks older than {}", recovered, threshold);
        }
    }

    @SuppressWarnings("unchecked")
    private void failOnNestedError(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) value;
            if (map.get("error") != null) {
                throw new IllegalStateException(String.valueOf(map.get("error")));
            }
            for (Object nested : map.values()) {
                failOnNestedError(nested);
            }
        } else if (value instanceof Iterable) {
            for (Object nested : (Iterable<Object>) value) {
                failOnNestedError(nested);
            }
        }
    }

    private String buildWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID().toString().substring(0, 8);
        } catch (Exception ex) {
            return "worker:" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "unknown error";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
