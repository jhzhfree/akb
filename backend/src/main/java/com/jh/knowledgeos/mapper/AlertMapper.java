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
public interface AlertMapper {

    int countDocumentFailed(@Param("kb") Long kb);

    int countOutboxDead(@Param("kb") Long kb);

    int insertAlertRule(@Param("ruleCode") String ruleCode, @Param("kb") Long kb, @Param("name") String name, @Param("metric") String metric,
            @Param("severity") String severity, @Param("threshold") double threshold, @Param("windowMinutes") int windowMinutes, @Param("channels") String
            channels, @Param("createdBy") String createdBy);

    int updateAlertRule(@Param("ruleCode") String ruleCode, @Param("name") String name, @Param("metric") String metric, @Param("severity") String
            severity, @Param("threshold") double threshold, @Param("windowMinutes") int windowMinutes, @Param("channels") String channels,
            @Param("enabled") boolean enabled);

    int deleteAlertRule(@Param("ruleCode") String ruleCode);

    Map<String, Object> getAlertRule(@Param("ruleCode") String ruleCode);

    List<Map<String, Object>> listAlertRules(@Param("kb") Long kb);

    int upsertAlertEvent(@Param("alertCode") String alertCode, @Param("ruleCode") String ruleCode, @Param("kb") Long kb, @Param("severity") String
            severity, @Param("title") String title, @Param("detailJson") String detailJson, @Param("metricValue") double metricValue, @Param("dedupKey")
            String dedupKey);

    Map<String, Object> getAlertEvent(@Param("alertCode") String alertCode);

    Map<String, Object> getAlertEventByDedup(@Param("dedupKey") String dedupKey);

    List<Map<String, Object>> listAlertEvents(@Param("kb") Long kb, @Param("status") String status, @Param("severity") String severity,
            @Param("limit") int limit, @Param("offset") int offset);

    long countAlertEvents(@Param("kb") Long kb, @Param("status") String status, @Param("severity") String severity);

    int updateAlertStatus(@Param("alertCode") String alertCode, @Param("status") String status, @Param("operator") String operator, @Param("note") String note);

    int resolveAlertByDedup(@Param("dedupKey") String dedupKey, @Param("note") String note);

    int countOpenAlerts(@Param("kb") Long kb);
}
