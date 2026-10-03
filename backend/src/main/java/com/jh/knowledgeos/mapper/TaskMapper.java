package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.EvaluationCase;
import com.jh.knowledgeos.domain.EvaluationDataset;
import com.jh.knowledgeos.domain.EvaluationResult;
import com.jh.knowledgeos.domain.EvaluationRun;
import com.jh.knowledgeos.domain.KnowledgeDocument;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.IngestionRun;
import com.jh.knowledgeos.domain.IndexOutboxEvent;
import com.jh.knowledgeos.domain.IndexSchemaRelease;
import com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow;
import com.jh.knowledgeos.projection.AuthoritativeGraphSnapshotRow;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Focused persistence contract extracted from the legacy KnowledgeMapper. */
public interface TaskMapper {

    int insertTaskRun(@Param("taskCode") String taskCode, @Param("kb") Long kb, @Param("taskType") String taskType, @Param("taskName") String
            taskName, @Param("targetType") String targetType, @Param("targetCode") String targetCode, @Param("triggerType") String triggerType,
            @Param("triggeredBy") String triggeredBy, @Param("maxAttempts") int maxAttempts, @Param("payloadJson") String payloadJson);

    int updateTaskRun(@Param("taskCode") String taskCode, @Param("status") String status, @Param("stage") String stage, @Param("progress") int
            progress, @Param("errorMessage") String errorMessage, @Param("resultJson") String resultJson);

    Map<String, Object> getTaskRun(@Param("taskCode") String taskCode);

    List<Map<String, Object>> listTaskRuns(@Param("kb") Long kb, @Param("status") String status, @Param("taskType") String taskType,
            @Param("triggerType") String triggerType, @Param("limit") int limit, @Param("offset") int offset);

    long countTaskRuns(@Param("kb") Long kb, @Param("status") String status, @Param("taskType") String taskType,
            @Param("triggerType") String triggerType);

    List<Map<String, Object>> taskStats(@Param("kb") Long kb);

    int countTaskOutcome(@Param("kb") Long kb, @Param("since") String since, @Param("statuses") List<String> statuses);
}
