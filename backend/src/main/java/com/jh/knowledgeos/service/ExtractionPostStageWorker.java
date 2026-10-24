package com.jh.knowledgeos.service;

import com.jh.knowledgeos.domain.ExtractionPostStage;
import com.jh.knowledgeos.mapper.ExtractionPostStageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Recovers post-extraction work without rerunning the extraction model. */
@Service
public class ExtractionPostStageWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionPostStageWorker.class);
    private final ExtractionPostStageMapper mapper;
    private final KnowledgeExtractionService extractionService;
    private final TransactionRunner transactionRunner;
    private final int batchSize;
    private final int lockTimeoutSeconds;
    private final String workerId = "post-stage-" + UUID.randomUUID().toString();

    public ExtractionPostStageWorker(ExtractionPostStageMapper mapper, KnowledgeExtractionService extractionService,
        TransactionRunner transactionRunner, @Value("${extraction.post-stage.batch-size:20}") int batchSize,
        @Value("${extraction.post-stage.lock-timeout-seconds:300}") int lockTimeoutSeconds) {
        this.mapper = mapper;
        this.extractionService = extractionService;
        this.transactionRunner = transactionRunner;
        this.batchSize = Math.max(1, Math.min(200, batchSize));
        this.lockTimeoutSeconds = Math.max(30, lockTimeoutSeconds);
    }

    @Scheduled(fixedDelayString = "${extraction.post-stage.poll-interval-ms:2000}")
    public void poll() {
        transactionRunner.required(() -> mapper.recoverStaleStages(LocalDateTime.now().minusSeconds(lockTimeoutSeconds)));
        List<ExtractionPostStage> due = transactionRunner.required(() -> mapper.selectDueStages(batchSize));
        for (ExtractionPostStage stage : due) {
            if (!extractionService.claimPostStage(stage.getId(), workerId)) { continue; }
            try {
                extractionService.processClaimedPostStage(stage.getId());
            } catch (RuntimeException ex) {
                LOG.warn("Extraction post-processing retry scheduled stage={} run={} status updated: {}", stage.getStageCode(),
                    stage.getRunId(), ex.getMessage());
            }
        }
    }
}
