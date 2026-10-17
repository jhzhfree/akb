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

    /**
     * Drop only the cards and claims that were never published.
     *
     * <p>Regenerating a knowledge base extracts every document again, which would otherwise
     * leave the older output alongside the new one. Objects a reviewer approved or marked
     * effective are human work and survive; candidates still sitting in DRAFT, UNCERTAIN,
     * PENDING_REVIEW or CONFLICTED were never retrievable and are safe to replace.</p>
     *
     * <p>Links are removed first because these tables carry no foreign keys, so dropping the
     * parent rows alone would leave edges pointing at codes that no longer exist.</p>
     */
    int purgeUnpublishedCardEvidence(@Param("kb") Long kb);

    int purgeUnpublishedCardEntities(@Param("kb") Long kb);

    int purgeUnpublishedClaimEvidence(@Param("kb") Long kb);

    int purgeUnpublishedClaimEntities(@Param("kb") Long kb);

    int purgeUnpublishedCards(@Param("kb") Long kb);

    int purgeUnpublishedClaims(@Param("kb") Long kb);

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
