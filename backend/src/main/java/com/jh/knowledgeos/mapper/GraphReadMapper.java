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
public interface GraphReadMapper {

    List<Map<String, Object>> listSourceImpactRows(@Param("sourceId") Long sourceId);

    Map<String, Object> resolveGraphNode(@Param("kb") Long kb, @Param("code") String code);

    List<Map<String, Object>> listGraphCardEvidenceEdges(@Param("kb") Long kb);

    List<Map<String, Object>> listGraphClaimEvidenceEdges(@Param("kb") Long kb);

    List<Map<String, Object>> listGraphEvidenceSourceEdges(@Param("kb") Long kb);

    List<Map<String, Object>> listGraphClaimEntityEdges(@Param("kb") Long kb);

    List<Map<String, Object>> listGraphCardEntityEdges(@Param("kb") Long kb);

    List<Map<String, Object>> listGraphEntityRelationEdges(@Param("kb") Long kb);

    List<Map<String, Object>> listGraphCards(@Param("kb") Long kb, @Param("codes") List<String> codes);

    List<Map<String, Object>> listGraphClaims(@Param("kb") Long kb, @Param("codes") List<String> codes);

    List<Map<String, Object>> listGraphEvidence(@Param("kb") Long kb, @Param("codes") List<String> codes);

    List<Map<String, Object>> listGraphEntities(@Param("kb") Long kb, @Param("codes") List<String> codes);

    List<Map<String, Object>> listGraphSources(@Param("kb") Long kb, @Param("codes") List<String> codes);
}
