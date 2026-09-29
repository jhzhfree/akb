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
public interface KnowledgeBaseMapper {

    List<KnowledgeBase> listKnowledgeBases(@Param("userId") Long userId, @Param("all") boolean all);

    KnowledgeBase getKnowledgeBase(@Param("id") Long id);

    int insertKnowledgeBase(KnowledgeBase base);

    int updateKnowledgeBaseStatus(@Param("id") Long id, @Param("status") String status);

    int updateKnowledgeBaseAccess(@Param("id") Long id, @Param("visibility") String visibility, @Param("ownerGroupId") Long ownerGroupId);

    int updateKnowledgeBaseOwner(@Param("id") Long id, @Param("createdBy") Long createdBy);
}
