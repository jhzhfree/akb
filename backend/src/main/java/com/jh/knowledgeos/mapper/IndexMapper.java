package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.*;
import com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow;
import com.jh.knowledgeos.projection.AuthoritativeGraphSnapshotRow;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Domain persistence mapper for index concerns. */
public interface IndexMapper {

    int incrementIndexAggregateVersion(@Param("aggregateKey") String aggregateKey);

    Long getIndexAggregateVersion(@Param("aggregateKey") String aggregateKey);

    int insertIndexOutbox(IndexOutboxEvent event);

    IndexOutboxEvent findOutboxByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    IndexOutboxEvent findOutboxByEventId(@Param("eventId") String eventId);

    List<IndexOutboxEvent> selectDueIndexOutbox(@Param("limit") Integer limit);

    int claimIndexOutbox(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    int markIndexOutboxSucceeded(@Param("id") Long id, @Param("resultJson") String resultJson,
        @Param("leaseToken") String leaseToken);

    int markIndexOutboxRetry(@Param("id") Long id, @Param("error") String error,
        @Param("nextAttemptAt") java.time.LocalDateTime nextAttemptAt, @Param("leaseToken") String leaseToken);

    int markIndexOutboxDead(@Param("id") Long id, @Param("error") String error, @Param("leaseToken") String leaseToken);

    int recoverStaleIndexOutbox(@Param("threshold") java.time.LocalDateTime threshold);

    int insertIndexDeliveryAttempt(@Param("eventId") String eventId, @Param("attemptNo") Integer attemptNo, @Param("status") String status,
        @Param("durationMs") Long durationMs, @Param("responseJson") String responseJson, @Param("errorText") String errorText);

    List<IndexOutboxEvent> listIndexOutbox(@Param("kb") Long kb, @Param("status") String status, @Param("limit") Integer limit, @Param("offset")
        Integer offset);

    Long countIndexOutbox(@Param("kb") Long kb, @Param("status") String status);

    Map<String, Object> indexOutboxStats(@Param("kb") Long kb);

    int replayIndexOutbox(@Param("eventId") String eventId);

    int skipIndexOutbox(@Param("eventId") String eventId);

    long countIndexableObjects(@Param("kb") Long kb);

    Map<String, Object> getDocumentIndexHeader(@Param("documentId") Long documentId);

    List<Map<String, Object>> listDocumentIndexChunks(@Param("documentId") Long documentId);

    Map<String, Object> indexOutboxGlobalStats();

    int archiveCompletedIndexOutbox(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") Integer limit);

    int archiveIndexDeliveryAttempts(@Param("cutoff") java.time.LocalDateTime cutoff);

    int deleteArchivedIndexDeliveryAttempts(@Param("cutoff") java.time.LocalDateTime cutoff);

    int deleteArchivedIndexOutbox(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") Integer limit);

    int insertIndexSchemaRelease(IndexSchemaRelease release);

    IndexSchemaRelease findIndexSchemaRelease(@Param("releaseId") String releaseId);

    List<IndexSchemaRelease> listIndexSchemaReleases(@Param("kb") Long kb, @Param("limit") Integer limit);

    int completeIndexSchemaRelease(@Param("releaseId") String releaseId, @Param("state") String state, @Param("elasticsearchCount") Long
        elasticsearchCount, @Param("milvusCount") Long milvusCount, @Param("elasticsearchTarget") String elasticsearchTarget, @Param("milvusTarget")
        String milvusTarget, @Param("validationJson") String validationJson, @Param("errorText") String errorText);

    int activateIndexSchemaRelease(@Param("kb") Long kb, @Param("releaseId") String releaseId);

    int insertAclManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId,
        @Param("manifestHash") String manifestHash, @Param("objectCount") Long objectCount, @Param("sourceHighWatermark") Long sourceHighWatermark);

    int updateSchemaManifest(@Param("releaseId") String releaseId, @Param("generationId") String generationId, @Param("manifestHash") String
        manifestHash, @Param("objectCount") Long objectCount);

    long maxIndexOutboxId(@Param("kb") Long kb);

    List<AuthoritativeIndexSnapshotRow> listAuthoritativeIndexSnapshot(@Param("kb") Long kb, @Param("offset") Long offset, @Param("limit") Integer limit);

    int insertIndexSnapshotAudit(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("pageNo") Integer pageNo, @Param("rowCount")
        Integer rowCount, @Param("pageChecksum") String pageChecksum, @Param("firstObjectKey") String firstObjectKey, @Param("lastObjectKey") String
        lastObjectKey);

    int updateIndexSchemaIntegrity(@Param("releaseId") String releaseId, @Param("snapshotChecksum") String snapshotChecksum,
        @Param("sourceHighWatermark") Long sourceHighWatermark, @Param("sourceEndWatermark") Long sourceEndWatermark, @Param("snapshotCount") Long
        snapshotCount, @Param("integrityState") String integrityState);

    int purgeIndexDeliveryAttempts(@Param("kb") Long kb);

    int purgeArchivedIndexDeliveryAttempts(@Param("kb") Long kb);
}
