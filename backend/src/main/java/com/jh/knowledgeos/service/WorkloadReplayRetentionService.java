package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.WorkloadIdentityRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded background retention; cleanup failures never invalidate an authenticated call. */
@Service
public class WorkloadReplayRetentionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkloadReplayRetentionService.class);
    private final WorkloadIdentityRepository repository;
    private final AtomicLong backlog = new AtomicLong();
    private final io.micrometer.core.instrument.Counter failures;
    public WorkloadReplayRetentionService(WorkloadIdentityRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        metrics.gauge("akb_workload_replay_expired_backlog", backlog);
        failures = metrics.counter("akb_workload_replay_cleanup_failures");
    }
    @Scheduled(fixedDelayString = "${knowledge.external.replay-cleanup-interval-ms:60000}")
    public void clean() {
        try {
            for (int batch = 0; batch < 10; batch++) if (repository.cleanV2() < 1000) break;
            backlog.set(repository.expiredV2Count());
            if (backlog.get() > 10000) log.warn("agent_replay_cleanup_backlog count={}", backlog.get());
        } catch (org.springframework.dao.DataAccessException ex) {
            failures.increment();
            log.warn("agent_replay_cleanup_failed exceptionType={}", ex.getClass().getSimpleName());
        }
    }
}
