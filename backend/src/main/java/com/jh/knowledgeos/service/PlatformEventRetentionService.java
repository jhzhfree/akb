package com.jh.knowledgeos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retention for the platform event log.
 *
 * <p>The bus is append-only by design: every mutation lands as a row, which is what makes replay and
 * the console history possible. Without a retention window that table grows without bound, so this
 * service deletes expired rows in bounded batches — a single DELETE over months of events would hold
 * one transaction open while it scans, and the bus keeps writing during that time.</p>
 */
@Service
public class PlatformEventRetentionService {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventRetentionService.class);

    /** Ceiling on batches per run: the next run continues rather than one run blocking for hours. */
    private static final int MAX_BATCHES = 50;

    private final PlatformEventStore store;

    private final boolean enabled;

    private final int retentionDays;

    private final int batchSize;

    private final AtomicLong purgedTotal = new AtomicLong();

    private volatile LocalDateTime lastRun;

    private volatile int lastPurged;

    public PlatformEventRetentionService(PlatformEventStore store,
        @Value("${knowledge.platform-event.retention.enabled:true}") boolean enabled,
        @Value("${knowledge.platform-event.retention.days:30}") int retentionDays,
        @Value("${knowledge.platform-event.retention.batch-size:1000}") int batchSize) {
        this.store = store;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchSize = Math.max(1, Math.min(5000, batchSize));
    }

    @Scheduled(fixedDelayString = "${knowledge.platform-event.retention.interval-ms:3600000}")
    public void purge() {
        if (!enabled || retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int removed = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            int batch = store.purgeBefore(cutoff, batchSize);
            removed += batch;
            if (batch < batchSize) {
                break;
            }
        }
        lastRun = LocalDateTime.now();
        lastPurged = removed;
        if (removed > 0) {
            purgedTotal.addAndGet(removed);
            log.info("platform event retention removed {} rows older than {} days", removed, retentionDays);
        }
    }

    /**
     * What the console shows next to the history: how long events are kept and what the last run did.
     * {@code days <= 0} means keep forever, which is a supported configuration and must be visible.
     */
    public Map<String, Object> policy() {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("enabled", enabled && retentionDays > 0);
        policy.put("retentionDays", retentionDays);
        policy.put("batchSize", batchSize);
        policy.put("purgedTotal", purgedTotal.get());
        policy.put("lastRun", lastRun == null ? null : lastRun.toString());
        policy.put("lastPurged", lastPurged);
        return policy;
    }
}
