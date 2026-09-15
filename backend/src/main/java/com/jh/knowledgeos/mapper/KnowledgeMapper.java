package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.domain.KnowledgeDocument;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.domain.QueryTraceRecord;
import com.jh.knowledgeos.domain.IndexOutboxEvent;
import com.jh.knowledgeos.domain.IndexSchemaRelease;
import com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper contract for authoritative knowledge, outbox, release, and trace persistence.
 */
public interface KnowledgeMapper {
    int insertSource(KnowledgeSource source);

    int insertSourceVersion(
            @Param("sourceId") Long sourceId,
            @Param("versionNo") Integer versionNo,
            @Param("sourceHash") String hash,
            @Param("objectUri") String uri);

    Long findSourceVersionId(
            @Param("sourceId") Long sourceId,
            @Param("versionNo") Integer versionNo);

    int insertDocument(KnowledgeDocument document);

    int updateDocumentParsed(
            @Param("id") Long id,
            @Param("rawText") String rawText,
            @Param("chunkCount") Integer chunkCount,
            @Param("status") String status);

    int insertContentUnit(
            @Param("documentId") Long documentId,
            @Param("unitCode") String unitCode,
            @Param("headingPath") String heading,
            @Param("pageNo") Integer pageNo,
            @Param("content") String content,
            @Param("contentHash") String hash);

    Long findContentUnitId(@Param("unitCode") String unitCode);

    int batchInsertContentUnits(@Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> findContentUnitIdsByCodes(@Param("codes") List<String> codes);

    int batchInsertEvidence(@Param("rows") List<Map<String, Object>> rows);

    int insertEvidence(
            @Param("evidenceCode") String evidenceCode,
            @Param("kb") Long kb,
            @Param("contentUnitId") Long contentUnitId,
            @Param("sourceId") Long sourceId,
            @Param("quoteText") String quoteText,
            @Param("locationJson") String locationJson,
            @Param("authorityScore") Double authorityScore);

    int insertCard(KnowledgeCard card);

    int insertClaim(Claim claim);

    int linkCardEvidenceFromDocument(
            @Param("knowledgeCode") String knowledgeCode,
            @Param("documentId") Long documentId);

    int linkClaimEvidenceFromDocument(
            @Param("claimCode") String claimCode,
            @Param("documentId") Long documentId);

    List<Map<String, Object>> listAdjudicationCandidates(@Param("kb") Long kb);

    List<Map<String, Object>> listEvidenceForObject(
            @Param("objectType") String objectType,
            @Param("objectCode") String objectCode);

    List<Map<String, Object>> listObjectConflicts(@Param("objectCode") String objectCode);

    int insertAssessment(
            @Param("kb") Long kb,
            @Param("objectType") String objectType,
            @Param("objectCode") String objectCode,
            @Param("policyVersion") String policyVersion,
            @Param("confidence") Double confidence,
            @Param("riskLevel") String riskLevel,
            @Param("decision") String decision,
            @Param("requiresHuman") Boolean requiresHuman,
            @Param("scoreJson") String scoreJson,
            @Param("aiJson") String aiJson,
            @Param("reason") String reason);

    List<Map<String, Object>> listAssessments(
            @Param("kb") Long kb,
            @Param("limit") Integer limit);

    List<KnowledgeCard> listCards(@Param("kb") Long kb);

    List<Claim> listClaims(@Param("kb") Long kb);

    List<Claim> listClaimsBySubjects(
            @Param("kb") Long kb,
            @Param("subjects") List<String> subjects);

    List<KnowledgeSource> listSources(@Param("kb") Long kb);

    List<Map<String, Object>> listDocuments(@Param("kb") Long kb);

    int updateCardStatus(@Param("code") String code, @Param("status") String status);

    int updateClaimStatus(@Param("code") String code, @Param("status") String status);

    int insertReview(
            @Param("type") String type,
            @Param("code") String code,
            @Param("decision") String decision,
            @Param("reviewer") String reviewer,
            @Param("comment") String comment);

    int insertConflict(
            @Param("kb") Long kb,
            @Param("a") String a,
            @Param("b") String b,
            @Param("type") String type,
            @Param("score") Double score);

    List<Map<String, Object>> listConflicts(
            @Param("kb") Long kb,
            @Param("status") String status);

    int resolveConflict(@Param("id") Long id, @Param("status") String status);

    List<Map<String, Object>> listPendingReviews(@Param("kb") Long kb);

    int insertTrace(QueryTraceRecord trace);

    Map<String, Object> getTrace(@Param("qid") String queryId);

    int incrementIndexAggregateVersion(@Param("aggregateKey") String aggregateKey);

    Long getIndexAggregateVersion(@Param("aggregateKey") String aggregateKey);

    int insertIndexOutbox(IndexOutboxEvent event);

    IndexOutboxEvent findOutboxByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    IndexOutboxEvent findOutboxByEventId(@Param("eventId") String eventId);

    List<IndexOutboxEvent> selectDueIndexOutbox(@Param("limit") Integer limit);

    int claimIndexOutbox(@Param("id") Long id, @Param("workerId") String workerId);

    int markIndexOutboxSucceeded(@Param("id") Long id, @Param("resultJson") String resultJson);

    int markIndexOutboxRetry(
            @Param("id") Long id,
            @Param("error") String error,
            @Param("nextAttemptAt") java.time.LocalDateTime nextAttemptAt);

    int markIndexOutboxDead(@Param("id") Long id, @Param("error") String error);

    int recoverStaleIndexOutbox(@Param("threshold") java.time.LocalDateTime threshold);

    int insertIndexDeliveryAttempt(
            @Param("eventId") String eventId,
            @Param("attemptNo") Integer attemptNo,
            @Param("status") String status,
            @Param("durationMs") Long durationMs,
            @Param("responseJson") String responseJson,
            @Param("errorText") String errorText);

    List<IndexOutboxEvent> listIndexOutbox(
            @Param("kb") Long kb,
            @Param("status") String status,
            @Param("limit") Integer limit);

    Map<String, Object> indexOutboxStats(@Param("kb") Long kb);

    int replayIndexOutbox(@Param("eventId") String eventId);

    int skipIndexOutbox(@Param("eventId") String eventId);

    Long findKnowledgeBaseIdForObject(
            @Param("objectType") String objectType,
            @Param("objectCode") String objectCode);

    long countIndexableObjects(@Param("kb") Long kb);

    Map<String, Object> getDocumentIndexHeader(@Param("documentId") Long documentId);

    List<Map<String, Object>> listDocumentIndexChunks(@Param("documentId") Long documentId);

    Map<String, Object> indexOutboxGlobalStats();
    int archiveCompletedIndexOutbox(
            @Param("cutoff") java.time.LocalDateTime cutoff,
            @Param("limit") Integer limit);
    int archiveIndexDeliveryAttempts(@Param("cutoff") java.time.LocalDateTime cutoff);
    int deleteArchivedIndexDeliveryAttempts(@Param("cutoff") java.time.LocalDateTime cutoff);
    int deleteArchivedIndexOutbox(@Param("cutoff") java.time.LocalDateTime cutoff,@Param("limit") Integer limit);
    int insertIndexSchemaRelease(IndexSchemaRelease release);
    IndexSchemaRelease findIndexSchemaRelease(@Param("releaseId") String releaseId);
    List<IndexSchemaRelease> listIndexSchemaReleases(@Param("kb") Long kb,@Param("limit") Integer limit);
    int completeIndexSchemaRelease(
            @Param("releaseId") String releaseId,
            @Param("state") String state,
            @Param("elasticsearchCount") Long elasticsearchCount,
            @Param("milvusCount") Long milvusCount,
            @Param("elasticsearchTarget") String elasticsearchTarget,
            @Param("milvusTarget") String milvusTarget,
            @Param("validationJson") String validationJson,
            @Param("errorText") String errorText);
    int activateIndexSchemaRelease(@Param("kb") Long kb,@Param("releaseId") String releaseId);

    long maxIndexOutboxId(@Param("kb") Long kb);

    List<AuthoritativeIndexSnapshotRow> listAuthoritativeIndexSnapshot(
            @Param("kb") Long kb,
            @Param("offset") Long offset,
            @Param("limit") Integer limit);

    int insertIndexSnapshotAudit(
            @Param("releaseId") String releaseId,
            @Param("kb") Long kb,
            @Param("pageNo") Integer pageNo,
            @Param("rowCount") Integer rowCount,
            @Param("pageChecksum") String pageChecksum,
            @Param("firstObjectKey") String firstObjectKey,
            @Param("lastObjectKey") String lastObjectKey);

    int updateIndexSchemaIntegrity(
            @Param("releaseId") String releaseId,
            @Param("snapshotChecksum") String snapshotChecksum,
            @Param("sourceHighWatermark") Long sourceHighWatermark,
            @Param("sourceEndWatermark") Long sourceEndWatermark,
            @Param("snapshotCount") Long snapshotCount,
            @Param("integrityState") String integrityState);
}


