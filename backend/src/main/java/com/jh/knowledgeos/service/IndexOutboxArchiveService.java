package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.KnowledgeMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Scheduled maintenance service that archives completed outbox events without touching unfinished work.
 */
@Service
public class IndexOutboxArchiveService {
    private final KnowledgeMapper mapper;
    private final TransactionRunner transactionRunner;
    private final IndexOutboxMetrics metrics;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;

    public IndexOutboxArchiveService(
            KnowledgeMapper mapper,
            TransactionRunner transactionRunner,
            IndexOutboxMetrics metrics,
            @Value("${indexing.outbox.archive.enabled:true}") boolean enabled,
            @Value("${indexing.outbox.archive.retention-days:14}") int retentionDays,
            @Value("${indexing.outbox.archive.batch-size:500}") int batchSize) {
        this.mapper = mapper;
        this.transactionRunner = transactionRunner;
        this.metrics = metrics;
        this.enabled = enabled;
        this.retentionDays = Math.max(1, retentionDays);
        this.batchSize = Math.max(1, Math.min(5000, batchSize));
    }

    @Scheduled(fixedDelayString = "${indexing.outbox.archive.interval-ms:3600000}")
    public void archive() {
        if (!enabled) {
            return;
        }
        final LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int moved = transactionRunner.required(() -> archiveBatch(cutoff));
        if (moved > 0) {
            metrics.archived(moved);
        }
    }

    private int archiveBatch(LocalDateTime cutoff) {
        int copied = mapper.archiveCompletedIndexOutbox(cutoff, batchSize);
        if (copied > 0) {
            mapper.archiveIndexDeliveryAttempts(cutoff);
            mapper.deleteArchivedIndexDeliveryAttempts(cutoff);
            mapper.deleteArchivedIndexOutbox(cutoff, batchSize);
        }
        return copied;
    }
}
