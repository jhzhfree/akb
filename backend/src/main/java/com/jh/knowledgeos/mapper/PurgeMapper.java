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
public interface PurgeMapper {

    Map<String, Object> countPurgeable(@Param("kb") Long kb);

    List<String> listSourceStoragePaths(@Param("kb") Long kb);

    int purgeKnowledgeBaseTable(@Param("table") String table, @Param("kb") Long kb);

    int purgeSourceVersions(@Param("kb") Long kb);

    int purgeDocuments(@Param("kb") Long kb);

    int purgeKnowledgeEvidence(@Param("kb") Long kb);

    int purgeClaimEvidence(@Param("kb") Long kb);

    int purgeCardEntityLinks(@Param("kb") Long kb);

    int purgeClaimEntityLinks(@Param("kb") Long kb);

    int purgeReviews(@Param("kb") Long kb);

    int purgeAnswerClaimAssessments(@Param("kb") Long kb);

    int purgeEntityFacetEvidence(@Param("kb") Long kb);

    int purgeRelationFacetEvidence(@Param("kb") Long kb);
}
