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
public interface AclPersistenceMapper {

    List<String> resolveAclPermissionTags(@Param("subjectCode") String subjectCode);

    List<Map<String, Object>> listAclSubjects();

    List<Map<String, Object>> listAclRoles();

    List<Map<String, Object>> listAclAudits(@Param("kb") Long kb, @Param("limit") Integer limit, @Param("offset") int offset);

    long countAclAudits(@Param("kb") Long kb);

    List<Map<String, Object>> listAclChanges(@Param("kb") Long kb, @Param("limit") Integer limit, @Param("offset") int offset);

    long countAclChanges(@Param("kb") Long kb);

    String getResourcePermissionTags(@Param("kb") Long kb, @Param("type") String type, @Param("code") String code);

    int updateResourcePermissionTags(@Param("kb") Long kb, @Param("type") String type, @Param("code") String code, @Param("tagsJson") String tagsJson);

    int insertAclChange(@Param("changeCode") String changeCode, @Param("kb") Long kb, @Param("type") String type, @Param("code") String code,
            @Param("oldTags") String oldTags, @Param("newTags") String newTags, @Param("changedBy") String changedBy, @Param("impactJson") String impactJson);

    int updateAclChangePropagation(@Param("changeCode") String changeCode, @Param("state") String state, @Param("resultJson") String resultJson,
            @Param("lastError") String lastError);

    int insertAclAudit(@Param("subjectCode") String subjectCode, @Param("kb") Long kb, @Param("resourceType") String resourceType,
            @Param("resourceCode") String resourceCode, @Param("actionName") String actionName, @Param("decision") String decision, @Param("tagsJson")
            String tagsJson, @Param("reason") String reason, @Param("traceId") String traceId, @Param("requestUri") String requestUri);

    Map<String, Object> getAclChange(@Param("changeCode") String changeCode);

    int insertAclRebuildJob(@Param("jobCode") String jobCode, @Param("changeCode") String changeCode, @Param("kb") Long kb, @Param("schemaVersion")
            String schemaVersion);

    Map<String, Object> getAclRebuildJob(@Param("jobCode") String jobCode);

    List<Map<String, Object>> listAclRebuildJobs(@Param("kb") Long kb, @Param("limit") Integer limit);

    int updateAclRebuildJob(@Param("jobCode") String jobCode, @Param("state") String state, @Param("releaseId") String releaseId,
            @Param("qualificationState") String qualificationState, @Param("qualificationJson") String qualificationJson, @Param("errorText") String errorText);
}
