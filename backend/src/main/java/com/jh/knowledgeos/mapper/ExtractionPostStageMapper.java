package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.ExtractionPostStage;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/** Persistence operations for durable extraction post-processing stages. */
public interface ExtractionPostStageMapper {
    int insertStage(ExtractionPostStage stage);
    List<ExtractionPostStage> selectDueStages(@Param("limit") Integer limit);
    List<ExtractionPostStage> selectDueStagesByRun(@Param("runId") String runId, @Param("limit") Integer limit);
    ExtractionPostStage findStage(@Param("id") Long id);
    int claimStage(@Param("id") Long id, @Param("workerId") String workerId);
    int markStageSucceeded(@Param("id") Long id);
    int markStageSkipped(@Param("id") Long id, @Param("reason") String reason);
    int markStageRetry(@Param("id") Long id, @Param("status") String status, @Param("error") String error,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
    int recoverStaleStages(@Param("threshold") LocalDateTime threshold);
    List<ExtractionPostStage> listStagesForRun(@Param("documentId") Long documentId, @Param("runId") String runId);
    int replayFailedStages(@Param("documentId") Long documentId, @Param("runId") String runId);
}
