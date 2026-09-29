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
public interface ContentUnitMapper {

    int purgeContentUnits(@Param("kb") Long kb);

    Map<String, Object> getChunkPolicy(@Param("kb") Long kb);

    int saveChunkPolicy(@Param("kb") Long kb, @Param("strategy") String strategy, @Param("chunkSize") int chunkSize, @Param("chunkOverlap") int
            chunkOverlap, @Param("minChunkSize") int minChunkSize, @Param("measure") String measure, @Param("keepStructure") boolean keepStructure,
            @Param("respectSentence") boolean respectSentence, @Param("parentChild") boolean parentChild, @Param("updatedBy") String updatedBy);

    int resetChunkPolicy(@Param("kb") Long kb);

    Map<String, Object> getContentUnitObject(@Param("kb") Long kb, @Param("code") String code);

    List<Map<String, Object>> listEvidenceForContentUnit(@Param("kb") Long kb, @Param("code") String code);

    List<Map<String, Object>> listNeighborContentUnits(@Param("documentId") Long documentId, @Param("id") Long id);
}
