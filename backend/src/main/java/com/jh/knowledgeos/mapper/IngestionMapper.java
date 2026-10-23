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
public interface IngestionMapper {

    int insertIngestionRun(IngestionRun run);

    int checkpointIngestionRun(@Param("runId") Long runId, @Param("chunks") int chunks,
        @Param("cards") int cards, @Param("claims") int claims);

    int insertIngestionEvent(@Param("runId") Long runId, @Param("eventType") String eventType, @Param("stage") String stage, @Param("status") String
            status, @Param("message") String message, @Param("detailJson") String detailJson);

    int updateIngestionRunStage(@Param("runId") Long runId, @Param("stage") String stage);

    int completeIngestionRun(@Param("runId") Long runId, @Param("status") String status, @Param("stage") String stage, @Param("chunkCount") Integer
            chunkCount, @Param("cardCount") Integer cardCount, @Param("claimCount") Integer claimCount, @Param("errorMessage") String errorMessage);

    List<IngestionRun> listIngestionRuns(@Param("kb") Long kb, @Param("limit") Integer limit);

    IngestionRun getIngestionRun(@Param("runId") Long runId);

    List<Map<String, Object>> listIngestionEvents(@Param("runId") Long runId);

    int purgeIngestionEvents(@Param("kb") Long kb);
}
