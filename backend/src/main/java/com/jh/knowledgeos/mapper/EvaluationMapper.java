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
public interface EvaluationMapper {

    int insertEvaluationDataset(EvaluationDataset dataset);

    List<Map<String, Object>> listEvaluationDatasets(@Param("kb") Long kb);

    Map<String, Object> getEvaluationDataset(@Param("code") String code);

    int updateEvaluationDataset(@Param("code") String code, @Param("name") String name, @Param("description") String description, @Param("status")
            String status);

    int insertEvaluationCase(EvaluationCase evaluationCase);

    List<Map<String, Object>> listEvaluationCases(@Param("datasetId") Long datasetId);

    /**
     * 当前知识库最新评测数据集的用例，供检索面板作为场景示例问题展示。
     */
    List<Map<String, Object>> listSampleQuestions(@Param("kb") Long kb, @Param("limit") int limit);

    int deleteEvaluationCase(@Param("id") Long id);

    int insertEvaluationRun(EvaluationRun run);

    int finishEvaluationRun(EvaluationRun run);

    int failEvaluationRun(@Param("runCode") String runCode, @Param("errorMessage") String errorMessage);

    Map<String, Object> getEvaluationRun(@Param("runCode") String runCode);

    List<Map<String, Object>> listEvaluationRuns(@Param("kb") Long kb, @Param("datasetId") Long datasetId, @Param("limit") Integer limit);

    int insertEvaluationResult(EvaluationResult result);

    List<Map<String, Object>> listEvaluationResults(@Param("runId") Long runId);
}
