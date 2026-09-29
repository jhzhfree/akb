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
public interface SchemaMapper {

    int upsertSchemaType(@Param("kb") Long kb, @Param("kind") String kind, @Param("typeCode") String typeCode, @Param("label") String label,
            @Param("description") String description, @Param("propertiesJson") String propertiesJson, @Param("status") String status, @Param("updatedBy")
            String updatedBy);

    int deleteSchemaType(@Param("kb") Long kb, @Param("kind") String kind, @Param("typeCode") String typeCode);

    Map<String, Object> getSchemaType(@Param("kb") Long kb, @Param("kind") String kind, @Param("typeCode") String typeCode);

    List<Map<String, Object>> listSchemaTypes(@Param("kb") Long kb, @Param("kind") String kind);

    int insertSchemaRelease(@Param("releaseCode") String releaseCode, @Param("kb") Long kb, @Param("version") String version, @Param("state") String
            state, @Param("definitionJson") String definitionJson, @Param("validationJson") String validationJson, @Param("checksum") String checksum,
            @Param("publishedBy") String publishedBy);

    List<Map<String, Object>> listSchemaReleases(@Param("kb") Long kb);

    Map<String, Object> getPublishedSchemaRelease(@Param("kb") Long kb);

    int retireSchemaReleases(@Param("kb") Long kb);

    List<Map<String, Object>> listEntityTypeUsage(@Param("kb") Long kb);

    List<Map<String, Object>> listRelationTypeUsage(@Param("kb") Long kb);

    List<Map<String, Object>> listKnowledgeTypeUsage(@Param("kb") Long kb);
}
