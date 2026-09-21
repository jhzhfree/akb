package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.EvaluationCase;
import com.jh.knowledgeos.domain.EvaluationDataset;
import com.jh.knowledgeos.domain.EvaluationResult;
import com.jh.knowledgeos.domain.EvaluationRun;
import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import com.jh.knowledgeos.domain.KnowledgeDocument;
import com.jh.knowledgeos.domain.KnowledgeSource;
import com.jh.knowledgeos.domain.KnowledgeBase;
import com.jh.knowledgeos.domain.IngestionRun;
import com.jh.knowledgeos.domain.QueryTraceRecord;
import com.jh.knowledgeos.domain.IndexOutboxEvent;
import com.jh.knowledgeos.domain.IndexSchemaRelease;
import com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow;
import com.jh.knowledgeos.projection.AuthoritativeGraphSnapshotRow;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper contract for authoritative knowledge, outbox, release, and trace persistence.
 */
public interface KnowledgeMapper {

    List<String> resolveAclPermissionTags(@Param("subjectCode") String subjectCode);

    List<Map<String, Object>> listAclSubjects();

    List<Map<String, Object>> listAclRoles();

    List<Map<String, Object>> listAclAudits(@Param("kb") Long kb, @Param("limit") Integer limit);

    List<Map<String, Object>> listAclChanges(@Param("kb") Long kb, @Param("limit") Integer limit);

    String getResourcePermissionTags(@Param("kb") Long kb, @Param("type") String type, @Param("code") String code);

    int updateResourcePermissionTags(@Param("kb") Long kb, @Param("type") String type, @Param("code") String code, @Param("tagsJson") String tagsJson);

    int insertAclChange(@Param("changeCode") String changeCode, @Param("kb") Long kb, @Param("type") String type, @Param("code") String code, @Param("oldTags") String oldTags, @Param("newTags") String newTags, @Param("changedBy") String changedBy, @Param("impactJson") String impactJson);

    int updateAclChangePropagation(@Param("changeCode") String changeCode, @Param("state") String state, @Param("resultJson") String resultJson, @Param("lastError") String lastError);

    int insertAclAudit(@Param("subjectCode") String subjectCode, @Param("kb") Long kb, @Param("resourceType") String resourceType, @Param("resourceCode") String resourceCode, @Param("actionName") String actionName, @Param("decision") String decision, @Param("tagsJson") String tagsJson, @Param("reason") String reason, @Param("traceId") String traceId, @Param("requestUri") String requestUri);

    Map<String, Object> getAclChange(@Param("changeCode") String changeCode);

    int insertAclRebuildJob(@Param("jobCode") String jobCode, @Param("changeCode") String changeCode, @Param("kb") Long kb, @Param("schemaVersion") String schemaVersion);

    Map<String, Object> getAclRebuildJob(@Param("jobCode") String jobCode);

    List<Map<String, Object>> listAclRebuildJobs(@Param("kb") Long kb, @Param("limit") Integer limit);

    int updateAclRebuildJob(@Param("jobCode") String jobCode, @Param("state") String state, @Param("releaseId") String releaseId, @Param("qualificationState") String qualificationState, @Param("qualificationJson") String qualificationJson, @Param("errorText") String errorText);

    int insertAclManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId, @Param("manifestHash") String manifestHash, @Param("objectCount") Long objectCount, @Param("sourceHighWatermark") Long sourceHighWatermark);

    int updateSchemaManifest(@Param("releaseId") String releaseId, @Param("generationId") String generationId, @Param("manifestHash") String manifestHash, @Param("objectCount") Long objectCount);

    /**
     * 按可见性过滤：管理员看全部，登录用户看公开 / 自己创建 / 所属用户组的库，匿名只看到公开库。
     */
    List<KnowledgeBase> listKnowledgeBases(@Param("userId") Long userId, @Param("all") boolean all);

    KnowledgeBase getKnowledgeBase(@Param("id") Long id);

    int insertKnowledgeBase(KnowledgeBase base);

    int updateKnowledgeBaseStatus(@Param("id") Long id, @Param("status") String status);

    int updateKnowledgeBaseAccess(@Param("id") Long id, @Param("visibility") String visibility, @Param("ownerGroupId") Long ownerGroupId);

    int updateKnowledgeBaseOwner(@Param("id") Long id, @Param("createdBy") Long createdBy);

    int insertIngestionRun(IngestionRun run);

    int insertIngestionEvent(@Param("runId") Long runId, @Param("eventType") String eventType, @Param("stage") String stage, @Param("status") String status, @Param("message") String message, @Param("detailJson") String detailJson);

    int updateIngestionRunStage(@Param("runId") Long runId, @Param("stage") String stage);

    int completeIngestionRun(@Param("runId") Long runId, @Param("status") String status, @Param("stage") String stage, @Param("chunkCount") Integer chunkCount, @Param("cardCount") Integer cardCount, @Param("claimCount") Integer claimCount, @Param("errorMessage") String errorMessage);

    List<IngestionRun> listIngestionRuns(@Param("kb") Long kb, @Param("limit") Integer limit);

    IngestionRun getIngestionRun(@Param("runId") Long runId);

    List<Map<String, Object>> listIngestionEvents(@Param("runId") Long runId);

    int insertSource(KnowledgeSource source);

    int insertSourceVersion(@Param("sourceId") Long sourceId, @Param("versionNo") Integer versionNo, @Param("sourceHash") String hash, @Param("objectUri") String uri);

    Long findSourceVersionId(@Param("sourceId") Long sourceId, @Param("versionNo") Integer versionNo);

    int insertDocument(KnowledgeDocument document);

    int updateDocumentParsed(@Param("id") Long id, @Param("rawText") String rawText, @Param("chunkCount") Integer chunkCount, @Param("status") String status);

    /**
     * Moves a document along the authoritative lifecycle. {@code retryCount} is passed by the
     * caller because only it knows whether this transition is a first attempt or another retry.
     */
    int updateDocumentState(@Param("id") Long id, @Param("status") String status, @Param("stage") String stage, @Param("error") String error, @Param("retryCount") Integer retryCount);

    /**
     * Everything a retry needs: the owning source, its stored bytes and the current chunk revision.
     */
    Map<String, Object> findDocumentRetryContext(@Param("id") Long id);

    /**
     * Retires the current chunk revision instead of overwriting content Evidence already points at.
     */
    int supersedeDocumentChunks(@Param("documentId") Long documentId);

    int nextChunkRevision(@Param("documentId") Long documentId);

    /**
     * Parent links can only be written once the generated chunk ids are known, hence one batch update.
     */
    int batchUpdateContentUnitParents(@Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> listDocumentChunks(@Param("documentId") Long documentId);

    int insertContentUnit(@Param("documentId") Long documentId, @Param("unitCode") String unitCode, @Param("headingPath") String heading, @Param("pageNo") Integer pageNo, @Param("content") String content, @Param("contentHash") String hash);

    Long findContentUnitId(@Param("unitCode") String unitCode);

    int batchInsertContentUnits(@Param("rows") List<Map<String, Object>> rows);

    List<Map<String, Object>> findContentUnitIdsByCodes(@Param("codes") List<String> codes);

    int batchInsertEvidence(@Param("rows") List<Map<String, Object>> rows);

    int insertEvidence(@Param("evidenceCode") String evidenceCode, @Param("kb") Long kb, @Param("contentUnitId") Long contentUnitId, @Param("sourceId") Long sourceId, @Param("quoteText") String quoteText, @Param("locationJson") String locationJson, @Param("authorityScore") Double authorityScore);

    int insertCard(KnowledgeCard card);

    int insertClaim(Claim claim);

    int linkCardEvidenceFromDocument(@Param("knowledgeCode") String knowledgeCode, @Param("documentId") Long documentId);

    int linkClaimEvidenceFromDocument(@Param("claimCode") String claimCode, @Param("documentId") Long documentId);

    List<Map<String, Object>> listAdjudicationCandidates(@Param("kb") Long kb);

    Map<String, Object> getKnowledgeObject(@Param("kb") Long kb, @Param("objectType") String objectType, @Param("objectCode") String objectCode, @Param("permissionTags") List<String> permissionTags);

    List<Map<String, Object>> listReviewsForObject(@Param("objectType") String objectType, @Param("objectCode") String objectCode);

    List<Map<String, Object>> listAssessmentsForObject(@Param("kb") Long kb, @Param("objectType") String objectType, @Param("objectCode") String objectCode);

    int snapshotKnowledgeObject(@Param("kb") Long kb, @Param("objectType") String objectType, @Param("objectCode") String objectCode, @Param("changeType") String changeType, @Param("changedBy") String changedBy);

    List<Map<String, Object>> listKnowledgeVersions(@Param("kb") Long kb, @Param("objectType") String objectType, @Param("objectCode") String objectCode);

    Map<String, Object> getContentUnitDetail(@Param("kb") Long kb, @Param("contentUnitId") Long contentUnitId, @Param("permissionTags") List<String> permissionTags);

    Map<String, Object> getEvidenceDetail(@Param("kb") Long kb, @Param("evidenceCode") String evidenceCode, @Param("permissionTags") List<String> permissionTags);

    List<Map<String, Object>> listEvidenceForObject(@Param("objectType") String objectType, @Param("objectCode") String objectCode);

    /**
     * Full traceability chain of one knowledge object: object → evidence → chunk → document →
     * source. Without it a citation can only be shown, never walked back to the authority it came
     * from, which is exactly what the design requires a reviewer to do (§9.4).
     */
    List<Map<String, Object>> listLineageForObject(@Param("kb") Long kb, @Param("objectType") String objectType, @Param("objectCode") String objectCode);

    /**
     * Primary evidence anchor of one knowledge object: the evidence code plus the content hash of the
     * unit it quotes. Retrieval indexes need it because the answer gate refuses any citation whose
     * evidence pointer cannot be resolved back to an immutable unit.
     */
    Map<String, Object> findPrimaryEvidenceAnchor(@Param("objectType") String objectType, @Param("objectCode") String objectCode);

    List<Map<String, Object>> listObjectConflicts(@Param("objectCode") String objectCode);

    int insertAssessment(@Param("kb") Long kb, @Param("objectType") String objectType, @Param("objectCode") String objectCode, @Param("policyVersion") String policyVersion, @Param("confidence") Double confidence, @Param("riskLevel") String riskLevel, @Param("decision") String decision, @Param("requiresHuman") Boolean requiresHuman, @Param("scoreJson") String scoreJson, @Param("aiJson") String aiJson, @Param("reason") String reason);

    List<Map<String, Object>> listAssessments(@Param("kb") Long kb, @Param("limit") Integer limit);

    List<KnowledgeCard> listCards(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    List<Claim> listClaims(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    List<Claim> listClaimsBySubjects(@Param("kb") Long kb, @Param("subjects") List<String> subjects);

    List<KnowledgeSource> listSources(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    /**
     * Source plus its first document and parsed text, scoped by the caller's permission tags.
     */
    Map<String, Object> getSourcePreview(@Param("kb") Long kb, @Param("sourceId") Long sourceId, @Param("permissionTags") List<String> permissionTags);

    List<Map<String, Object>> listDocuments(@Param("kb") Long kb);

    int updateCardStatus(@Param("code") String code, @Param("status") String status);

    int updateClaimStatus(@Param("code") String code, @Param("status") String status);

    /**
     * Appends a judgment record. A decision is only auditable when it also names what was compared,
     * what came out of it, and under which policy version, so all of them are stored here.
     */
    int insertJudgment(@Param("type") String type, @Param("code") String code, @Param("decision") String decision, @Param("reviewer") String reviewer, @Param("comment") String comment, @Param("inputObjectCodes") String inputObjectCodes, @Param("resultObjectCode") String resultObjectCode, @Param("policyVersion") String policyVersion);

    int insertConflict(@Param("kb") Long kb, @Param("a") String a, @Param("b") String b, @Param("type") String type, @Param("score") Double score);

    List<Map<String, Object>> listConflicts(@Param("kb") Long kb, @Param("status") String status);

    /**
     * Single conflict record including its resolution text; needed to turn a conflict into a judgment.
     */
    Map<String, Object> getConflict(@Param("id") Long id);

    /**
     * Side-by-side view of the objects being compared during adjudication.
     */
    List<Map<String, Object>> listAdjudicationTargets(@Param("codes") List<String> codes);

    /**
     * Resolution is stored next to the status: "resolved" alone says nothing about how.
     */
    int resolveConflict(@Param("id") Long id, @Param("status") String status, @Param("resolution") String resolution);

    List<Map<String, Object>> listPendingReviews(@Param("kb") Long kb);

    int insertTrace(QueryTraceRecord trace);

    int insertAnswerEvidenceAssessment(@Param("queryId") String queryId, @Param("kb") Long kb, @Param("gateStatus") String gateStatus, @Param("completeness") Double completeness, @Param("faithfulness") Double faithfulness, @Param("claimCount") Integer claimCount, @Param("supportedCount") Integer supportedCount, @Param("citationCount") Integer citationCount, @Param("unsupportedJson") String unsupportedJson, @Param("verificationJson") String verificationJson);

    int insertAnswerQualification(@Param("queryId") String queryId, @Param("kb") Long kb, @Param("policyVersion") String policyVersion, @Param("gateStatus") String gateStatus, @Param("evidenceGateStatus") String evidenceGateStatus, @Param("structuredGateStatus") String structuredGateStatus, @Param("reasonsJson") String reasonsJson, @Param("originalHash") String originalHash, @Param("deliveredHash") String deliveredHash, @Param("claimCount") Integer claimCount);

    int insertAnswerClaimAssessment(@Param("queryId") String queryId, @Param("claimId") String claimId, @Param("policyVersion") String policyVersion, @Param("statement") String statement, @Param("decision") String decision, @Param("confidence") Double confidence, @Param("entailmentScore") Double entailmentScore, @Param("citationIndexesJson") String citationIndexesJson, @Param("evidenceCodesJson") String evidenceCodesJson, @Param("evidenceStatusesJson") String evidenceStatusesJson, @Param("included") Boolean included);

    Map<String, Object> getAnswerQualification(@Param("queryId") String queryId);

    List<Map<String, Object>> listAnswerClaimAssessments(@Param("queryId") String queryId);

    Map<String, Object> summarizeAnswerQualifications(@Param("kb") Long kb, @Param("hours") Integer hours);

    List<Map<String, Object>> listAnswerQualificationPolicyStats(@Param("kb") Long kb, @Param("hours") Integer hours);

    Map<String, Object> getTrace(@Param("qid") String queryId);

    List<Map<String, Object>> listQueryTraces(@Param("kb") Long kb, @Param("limit") Integer limit);

    int incrementIndexAggregateVersion(@Param("aggregateKey") String aggregateKey);

    Long getIndexAggregateVersion(@Param("aggregateKey") String aggregateKey);

    int insertIndexOutbox(IndexOutboxEvent event);

    IndexOutboxEvent findOutboxByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    IndexOutboxEvent findOutboxByEventId(@Param("eventId") String eventId);

    List<IndexOutboxEvent> selectDueIndexOutbox(@Param("limit") Integer limit);

    int claimIndexOutbox(@Param("id") Long id, @Param("workerId") String workerId);

    int markIndexOutboxSucceeded(@Param("id") Long id, @Param("resultJson") String resultJson);

    int markIndexOutboxRetry(@Param("id") Long id, @Param("error") String error, @Param("nextAttemptAt") java.time.LocalDateTime nextAttemptAt);

    int markIndexOutboxDead(@Param("id") Long id, @Param("error") String error);

    int recoverStaleIndexOutbox(@Param("threshold") java.time.LocalDateTime threshold);

    int insertIndexDeliveryAttempt(@Param("eventId") String eventId, @Param("attemptNo") Integer attemptNo, @Param("status") String status, @Param("durationMs") Long durationMs, @Param("responseJson") String responseJson, @Param("errorText") String errorText);

    List<IndexOutboxEvent> listIndexOutbox(@Param("kb") Long kb, @Param("status") String status, @Param("limit") Integer limit, @Param("offset") Integer offset);

    Long countIndexOutbox(@Param("kb") Long kb, @Param("status") String status);

    Map<String, Object> indexOutboxStats(@Param("kb") Long kb);

    int replayIndexOutbox(@Param("eventId") String eventId);

    int skipIndexOutbox(@Param("eventId") String eventId);

    Long findKnowledgeBaseIdForObject(@Param("objectType") String objectType, @Param("objectCode") String objectCode);

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

    int completeIndexSchemaRelease(@Param("releaseId") String releaseId, @Param("state") String state, @Param("elasticsearchCount") Long elasticsearchCount, @Param("milvusCount") Long milvusCount, @Param("elasticsearchTarget") String elasticsearchTarget, @Param("milvusTarget") String milvusTarget, @Param("validationJson") String validationJson, @Param("errorText") String errorText);

    int activateIndexSchemaRelease(@Param("kb") Long kb, @Param("releaseId") String releaseId);

    long maxIndexOutboxId(@Param("kb") Long kb);

    List<AuthoritativeIndexSnapshotRow> listAuthoritativeIndexSnapshot(@Param("kb") Long kb, @Param("offset") Long offset, @Param("limit") Integer limit);

    int insertIndexSnapshotAudit(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("pageNo") Integer pageNo, @Param("rowCount") Integer rowCount, @Param("pageChecksum") String pageChecksum, @Param("firstObjectKey") String firstObjectKey, @Param("lastObjectKey") String lastObjectKey);

    int upsertExtractedEntity(@Param("kb") Long kb, @Param("sourceId") Long sourceId, @Param("code") String code, @Param("type") String type, @Param("name") String name, @Param("description") String description, @Param("confidence") Double confidence, @Param("status") String status, @Param("tagsJson") String tagsJson, @Param("aclFrom") String aclFrom);

    int insertEntityAclScope(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("sourceId") Long sourceId, @Param("documentId") Long documentId, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    int recomputeEntityAclMergePolicy(@Param("kb") Long kb, @Param("entityCode") String entityCode);

    int upsertEntityFacet(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("facetCode") String facetCode, @Param("sourceId") Long sourceId, @Param("documentId") Long documentId, @Param("type") String type, @Param("name") String name, @Param("description") String description, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    Long findEntityId(@Param("kb") Long kb, @Param("code") String code);

    /**
     * Resolve one entity by code or canonical name.
     *
     * <p>Extraction returns entity names while authoritative storage keys on codes, so
     * relation endpoints and mentions may arrive as either. Both are accepted here so a
     * relation is never dropped just because the model quoted the entity name.</p>
     */
    Map<String, Object> findEntityRef(@Param("kb") Long kb, @Param("value") String value);

    int upsertExtractedRelation(@Param("kb") Long kb, @Param("sourceId") Long sourceId, @Param("code") String code, @Param("sourceEntityId") Long sourceEntityId, @Param("type") String type, @Param("targetEntityId") Long targetEntityId, @Param("confidence") Double confidence, @Param("status") String status, @Param("tagsJson") String tagsJson, @Param("aclFrom") String aclFrom);

    int insertRelationAclScope(@Param("kb") Long kb, @Param("relationCode") String relationCode, @Param("sourceId") Long sourceId, @Param("documentId") Long documentId, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    int recomputeRelationAclMergePolicy(@Param("kb") Long kb, @Param("relationCode") String relationCode);

    int upsertRelationFacet(@Param("kb") Long kb, @Param("relationCode") String relationCode, @Param("facetCode") String facetCode, @Param("sourceId") Long sourceId, @Param("documentId") Long documentId, @Param("sourceCode") String sourceCode, @Param("targetCode") String targetCode, @Param("type") String type, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    int linkClaimEntityAcl(@Param("claimCode") String claimCode, @Param("entityCode") String entityCode, @Param("role") String role, @Param("tagsJson") String tagsJson);

    int linkCardEntityAcl(@Param("knowledgeCode") String knowledgeCode, @Param("entityCode") String entityCode, @Param("role") String role, @Param("tagsJson") String tagsJson);

    List<AuthoritativeGraphSnapshotRow> listAuthoritativeGraphSnapshot(@Param("kb") Long kb);

    List<Map<String, Object>> listAuthoritativeGraphEvidenceBindings(@Param("kb") Long kb);

    long countAuthoritativeGraphEntities(@Param("kb") Long kb);

    long countAuthoritativeGraphRelations(@Param("kb") Long kb);

    Map<String, Object> getGraphManifest(@Param("releaseId") String releaseId);

    int insertGraphManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId, @Param("manifestHash") String manifestHash, @Param("entityCount") Long entityCount, @Param("relationCount") Long relationCount);

    int insertGraphFacetManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId, @Param("manifestHash") String manifestHash, @Param("entityFacetCount") Long entityFacetCount, @Param("relationFacetCount") Long relationFacetCount);

    int insertEntityFacetEvidence(@Param("facetCode") String facetCode, @Param("evidenceId") Long evidenceId, @Param("evidenceHash") String evidenceHash);

    int insertRelationFacetEvidence(@Param("facetCode") String facetCode, @Param("evidenceId") Long evidenceId, @Param("evidenceHash") String evidenceHash);

    int insertGraphEvidenceManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId, @Param("manifestHash") String manifestHash, @Param("evidenceCount") Long evidenceCount, @Param("bindingCount") Long bindingCount);

    int updateIndexSchemaIntegrity(@Param("releaseId") String releaseId, @Param("snapshotChecksum") String snapshotChecksum, @Param("sourceHighWatermark") Long sourceHighWatermark, @Param("sourceEndWatermark") Long sourceEndWatermark, @Param("snapshotCount") Long snapshotCount, @Param("integrityState") String integrityState);

    // ---- Entity resolution (design V1.1 §5.4) ----
    /**
     * Canonical entities of a knowledge base, used as the merge targets for new surface forms.
     */
    List<Map<String, Object>> listEntityNames(@Param("kb") Long kb);

    int upsertEntityAlias(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("alias") String alias, @Param("aliasType") String aliasType, @Param("confidence") Double confidence);

    /**
     * Mention link ids to be re-pointed, ascending by primary key.
     *
     * <p>The ids are read before they are written, and in a fixed order, because moving a link with
     * {@code UPDATE ... JOIN} lets the optimiser choose which rows it locks first. Two concurrent
     * merges then take the same rows in opposite orders and neither can proceed; MySQL resolves it
     * by killing one transaction. Walking the ids in ascending order gives every merge the same
     * lock order, so batches running several documents at once interleave instead of colliding.</p>
     */
    List<Long> selectCardEntityLinkIds(@Param("kb") Long kb, @Param("fromCode") String fromCode);

    int updateCardEntityLinkTargets(@Param("ids") List<Long> ids, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

    List<Long> selectClaimEntityLinkIds(@Param("kb") Long kb, @Param("fromCode") String fromCode);

    int updateClaimEntityLinkTargets(@Param("ids") List<Long> ids, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

    /**
     * Re-point the card→entity links of a retiring entity to its canonical successor.
     */
    default void reassignCardEntityLinks(Long kb, String fromCode, String toCode) {
        List<Long> ids = selectCardEntityLinkIds(kb, fromCode);
        if (ids != null && !ids.isEmpty()) {
            updateCardEntityLinkTargets(ids, fromCode, toCode);
        }
    }

    /**
     * Re-point the claim→entity links of a retiring entity to its canonical successor.
     */
    default void reassignClaimEntityLinks(Long kb, String fromCode, String toCode) {
        List<Long> ids = selectClaimEntityLinkIds(kb, fromCode);
        if (ids != null && !ids.isEmpty()) {
            updateClaimEntityLinkTargets(ids, fromCode, toCode);
        }
    }

    /**
     * Retire an entity that was absorbed by a canonical one; graph queries only return live nodes.
     */
    int updateEntityStatus(@Param("kb") Long kb, @Param("code") String code, @Param("status") String status);

    // ---- Entity governance screen (V1.0): entity/alias inspection and manual correction ----
    /**
     * Entities of a knowledge base together with alias and mention cardinality.
     *
     * <p>Merged rows are returned as well: the governance screen has to explain where a surface
     * form went, otherwise "why is this entity gone" becomes unanswerable.</p>
     */
    List<Map<String, Object>> listEntitiesWithUsage(@Param("kb") Long kb);

    Map<String, Object> getEntity(@Param("kb") Long kb, @Param("code") String code);

    List<Map<String, Object>> listEntityAliases(@Param("kb") Long kb, @Param("entityCode") String entityCode);

    int deleteEntityAlias(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("alias") String alias);

    /**
     * Drop aliases already owned by the merge target so the move cannot violate the unique key.
     */
    int deleteConflictingAliases(@Param("kb") Long kb, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

    int moveEntityAliases(@Param("kb") Long kb, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

    // ---- Lifecycle dashboard (V1.0) ----
    List<Map<String, Object>> countClaimsByStatus(@Param("kb") Long kb);

    List<Map<String, Object>> countCardsByStatus(@Param("kb") Long kb);

    /**
     * Assertions whose validity window closes before {@code horizon}, used by the renewal queue.
     */
    List<Map<String, Object>> listExpiringClaims(@Param("kb") Long kb, @Param("horizon") Date horizon);

    long countCardEvidence(@Param("code") String code);

    long countClaimEvidence(@Param("code") String code);

    // ---- Knowledge lifecycle (design V1.1 §5.5 / §17) ----
    List<Map<String, Object>> listClaimsWithValidity(@Param("kb") Long kb);

    /**
     * Rows describing which knowledge objects lose their support when a source is revoked.
     */
    List<Map<String, Object>> listSourceImpactRows(@Param("sourceId") Long sourceId);

    // ---- Authoritative graph neighborhood (V1.0 visualization) ----
    /**
     * Resolve a user supplied code to a graph node so the neighborhood can start from a card,
     * a claim, an entity or an evidence unit.
     */
    Map<String, Object> resolveGraphNode(@Param("kb") Long kb, @Param("code") String code);

    /**
     * Card -> Evidence support edges, the primary visible relation of the knowledge graph.
     */
    List<Map<String, Object>> listGraphCardEvidenceEdges(@Param("kb") Long kb);

    /**
     * Claim -> Evidence support edges.
     */
    List<Map<String, Object>> listGraphClaimEvidenceEdges(@Param("kb") Long kb);

    /**
     * Evidence -> Source provenance edges.
     */
    List<Map<String, Object>> listGraphEvidenceSourceEdges(@Param("kb") Long kb);

    /**
     * Claim -> Entity mention edges produced by entity resolution.
     */
    List<Map<String, Object>> listGraphClaimEntityEdges(@Param("kb") Long kb);

    /**
     * Card -> Entity mention edges produced by entity resolution.
     */
    List<Map<String, Object>> listGraphCardEntityEdges(@Param("kb") Long kb);

    /**
     * Entity -> Entity typed relations.
     */
    List<Map<String, Object>> listGraphEntityRelationEdges(@Param("kb") Long kb);

    /**
     * Titles and statuses of cards referenced by the neighborhood.
     */
    List<Map<String, Object>> listGraphCards(@Param("kb") Long kb, @Param("codes") List<String> codes);

    /**
     * Subject/predicate/object rendering of claims referenced by the neighborhood.
     */
    List<Map<String, Object>> listGraphClaims(@Param("kb") Long kb, @Param("codes") List<String> codes);

    /**
     * Quote text of evidence referenced by the neighborhood.
     */
    List<Map<String, Object>> listGraphEvidence(@Param("kb") Long kb, @Param("codes") List<String> codes);

    /**
     * Canonical names of entities referenced by the neighborhood.
     */
    List<Map<String, Object>> listGraphEntities(@Param("kb") Long kb, @Param("codes") List<String> codes);

    /**
     * Names of sources referenced by the neighborhood.
     */
    List<Map<String, Object>> listGraphSources(@Param("kb") Long kb, @Param("codes") List<String> codes);

    // ---- Knowledge base purge (console "clear all data") ----
    /**
     * Cardinality of the objects a purge would remove, shown to the operator before confirmation.
     */
    Map<String, Object> countPurgeable(@Param("kb") Long kb);

    /**
     * Stored original-file paths of a knowledge base, used to remove the uploaded bytes as well.
     */
    List<String> listSourceStoragePaths(@Param("kb") Long kb);

    /**
     * Delete every row of one knowledge-base scoped table.
     *
     * <p>The table name is passed as a literal because the purge covers thirty tables that all
     * share the same {@code knowledge_base_id} predicate. Values come from a fixed allow-list in
     * {@code KnowledgeBasePurgeService}, never from a request parameter.</p>
     */
    int purgeKnowledgeBaseTable(@Param("table") String table, @Param("kb") Long kb);

    int purgeSourceVersions(@Param("kb") Long kb);

    int purgeDocuments(@Param("kb") Long kb);

    int purgeContentUnits(@Param("kb") Long kb);

    int purgeKnowledgeEvidence(@Param("kb") Long kb);

    int purgeClaimEvidence(@Param("kb") Long kb);

    int purgeCardEntityLinks(@Param("kb") Long kb);

    int purgeClaimEntityLinks(@Param("kb") Long kb);

    int purgeReviews(@Param("kb") Long kb);

    int purgeIngestionEvents(@Param("kb") Long kb);

    int purgeIndexDeliveryAttempts(@Param("kb") Long kb);

    int purgeArchivedIndexDeliveryAttempts(@Param("kb") Long kb);

    int purgeAnswerClaimAssessments(@Param("kb") Long kb);

    int purgeEntityFacetEvidence(@Param("kb") Long kb);

    int purgeRelationFacetEvidence(@Param("kb") Long kb);

    /* ---- Evaluation center (design §14): dataset / case / run / result ---- */
    int insertEvaluationDataset(EvaluationDataset dataset);

    List<Map<String, Object>> listEvaluationDatasets(@Param("kb") Long kb);

    Map<String, Object> getEvaluationDataset(@Param("code") String code);

    int updateEvaluationDataset(@Param("code") String code, @Param("name") String name, @Param("description") String description, @Param("status") String status);

    int insertEvaluationCase(EvaluationCase evaluationCase);

    List<Map<String, Object>> listEvaluationCases(@Param("datasetId") Long datasetId);

    int deleteEvaluationCase(@Param("id") Long id);

    int insertEvaluationRun(EvaluationRun run);

    int finishEvaluationRun(EvaluationRun run);

    int failEvaluationRun(@Param("runCode") String runCode, @Param("errorMessage") String errorMessage);

    Map<String, Object> getEvaluationRun(@Param("runCode") String runCode);

    List<Map<String, Object>> listEvaluationRuns(@Param("kb") Long kb, @Param("datasetId") Long datasetId, @Param("limit") Integer limit);

    int insertEvaluationResult(EvaluationResult result);

    List<Map<String, Object>> listEvaluationResults(@Param("runId") Long runId);

    /**
     * Persisted chunking policy of a knowledge base; null when it still uses defaults.
     */
    Map<String, Object> getChunkPolicy(@Param("kb") Long kb);

    int saveChunkPolicy(@Param("kb") Long kb, @Param("strategy") String strategy, @Param("chunkSize") int chunkSize, @Param("chunkOverlap") int chunkOverlap, @Param("minChunkSize") int minChunkSize, @Param("measure") String measure, @Param("keepStructure") boolean keepStructure, @Param("respectSentence") boolean respectSentence, @Param("parentChild") boolean parentChild, @Param("updatedBy") String updatedBy);

    int resetChunkPolicy(@Param("kb") Long kb);

    /**
     * One content unit addressed by its unit code, for the chunk detail view.
     */
    Map<String, Object> getContentUnitObject(@Param("kb") Long kb, @Param("code") String code);

    /**
     * Evidence rows citing one content unit, for the chunk detail view.
     */
    List<Map<String, Object>> listEvidenceForContentUnit(@Param("kb") Long kb, @Param("code") String code);

    /**
     * The chunk before and after one chunk: reading a hit in its own context.
     */
    List<Map<String, Object>> listNeighborContentUnits(@Param("documentId") Long documentId, @Param("id") Long id);

    // ---------------------------------------------------------------- task centre
    int insertTaskRun(@Param("taskCode") String taskCode, @Param("kb") Long kb, @Param("taskType") String taskType, @Param("taskName") String taskName, @Param("targetType") String targetType, @Param("targetCode") String targetCode, @Param("triggerType") String triggerType, @Param("triggeredBy") String triggeredBy, @Param("maxAttempts") int maxAttempts, @Param("payloadJson") String payloadJson);

    int updateTaskRun(@Param("taskCode") String taskCode, @Param("status") String status, @Param("stage") String stage, @Param("progress") int progress, @Param("errorMessage") String errorMessage, @Param("resultJson") String resultJson);

    Map<String, Object> getTaskRun(@Param("taskCode") String taskCode);

    List<Map<String, Object>> listTaskRuns(@Param("kb") Long kb, @Param("status") String status, @Param("taskType") String taskType, @Param("triggerType") String triggerType, @Param("limit") int limit);

    /**
     * Task counts grouped by status: the four tiles of the task centre.
     */
    List<Map<String, Object>> taskStats(@Param("kb") Long kb);

    /**
     * Tasks in one of the given outcomes since a point in time, for alert metrics.
     */
    int countTaskOutcome(@Param("kb") Long kb, @Param("since") String since, @Param("statuses") List<String> statuses);

    int countDocumentFailed(@Param("kb") Long kb);

    int countOutboxDead(@Param("kb") Long kb);

    // ------------------------------------------------------------------- alerting
    int insertAlertRule(@Param("ruleCode") String ruleCode, @Param("kb") Long kb, @Param("name") String name, @Param("metric") String metric, @Param("severity") String severity, @Param("threshold") double threshold, @Param("windowMinutes") int windowMinutes, @Param("channels") String channels, @Param("createdBy") String createdBy);

    int updateAlertRule(@Param("ruleCode") String ruleCode, @Param("name") String name, @Param("metric") String metric, @Param("severity") String severity, @Param("threshold") double threshold, @Param("windowMinutes") int windowMinutes, @Param("channels") String channels, @Param("enabled") boolean enabled);

    int deleteAlertRule(@Param("ruleCode") String ruleCode);

    Map<String, Object> getAlertRule(@Param("ruleCode") String ruleCode);

    /**
     * Rules that apply to a knowledge base: its own plus the platform wide ones.
     */
    List<Map<String, Object>> listAlertRules(@Param("kb") Long kb);

    /**
     * Raise or re-raise: a repeated condition grows the occurrence count in place.
     */
    int upsertAlertEvent(@Param("alertCode") String alertCode, @Param("ruleCode") String ruleCode, @Param("kb") Long kb, @Param("severity") String severity, @Param("title") String title, @Param("detailJson") String detailJson, @Param("metricValue") double metricValue, @Param("dedupKey") String dedupKey);

    Map<String, Object> getAlertEvent(@Param("alertCode") String alertCode);

    Map<String, Object> getAlertEventByDedup(@Param("dedupKey") String dedupKey);

    List<Map<String, Object>> listAlertEvents(@Param("kb") Long kb, @Param("status") String status, @Param("severity") String severity, @Param("limit") int limit);

    List<Map<String, Object>> alertStats(@Param("kb") Long kb);

    int updateAlertStatus(@Param("alertCode") String alertCode, @Param("status") String status, @Param("operator") String operator, @Param("note") String note);

    /**
     * Clear an alert when the metric recovered: the condition no longer holds.
     */
    int resolveAlertByDedup(@Param("dedupKey") String dedupKey, @Param("note") String note);

    int countOpenAlerts(@Param("kb") Long kb);

    // ---------------------------------------------------------------- derivation
    int insertDerivationRule(@Param("ruleCode") String ruleCode, @Param("kb") Long kb, @Param("name") String name, @Param("derivationType") String derivationType, @Param("scheduleType") String scheduleType, @Param("cronExpr") String cronExpr, @Param("nextFireAt") String nextFireAt, @Param("sourceScopeJson") String sourceScopeJson, @Param("definitionJson") String definitionJson, @Param("algorithmVersion") String algorithmVersion, @Param("createdBy") String createdBy);

    int updateDerivationRule(@Param("ruleCode") String ruleCode, @Param("name") String name, @Param("derivationType") String derivationType, @Param("scheduleType") String scheduleType, @Param("cronExpr") String cronExpr, @Param("nextFireAt") String nextFireAt, @Param("sourceScopeJson") String sourceScopeJson, @Param("definitionJson") String definitionJson, @Param("algorithmVersion") String algorithmVersion, @Param("enabled") boolean enabled);

    int updateDerivationRuleState(@Param("ruleCode") String ruleCode, @Param("status") String status, @Param("lastFireAt") String lastFireAt, @Param("nextFireAt") String nextFireAt, @Param("lastError") String lastError);

    int deleteDerivationRule(@Param("ruleCode") String ruleCode);

    Map<String, Object> getDerivationRule(@Param("ruleCode") String ruleCode);

    List<Map<String, Object>> listDerivationRules(@Param("kb") Long kb);

    /**
     * Scheduled rules whose next fire time has arrived.
     */
    List<Map<String, Object>> listDueDerivationRules(@Param("now") String now);

    int insertDerivedCandidate(@Param("candidateCode") String candidateCode, @Param("kb") Long kb, @Param("ruleCode") String ruleCode, @Param("derivationType") String derivationType, @Param("algorithmVersion") String algorithmVersion, @Param("title") String title, @Param("content") String content, @Param("confidence") double confidence, @Param("baseAssertionCodes") String baseAssertionCodes, @Param("graphPath") String graphPath, @Param("evidenceJson") String evidenceJson, @Param("derivedFromJson") String derivedFromJson, @Param("permissionTags") String permissionTags, @Param("authorityScore") double authorityScore, @Param("contentHash") String contentHash, @Param("createdBy") String createdBy);

    /**
     * Same rule, same content: refresh the derivation instead of duplicating it.
     */
    int touchDerivedCandidate(@Param("ruleCode") String ruleCode, @Param("contentHash") String contentHash, @Param("confidence") double confidence, @Param("status") String status);

    Map<String, Object> getDerivedCandidate(@Param("candidateCode") String candidateCode);

    Map<String, Object> getDerivedCandidateByHash(@Param("ruleCode") String ruleCode, @Param("contentHash") String contentHash);

    List<Map<String, Object>> listDerivedCandidates(@Param("kb") Long kb, @Param("status") String status, @Param("ruleCode") String ruleCode, @Param("limit") int limit);

    List<Map<String, Object>> derivedCandidateStats(@Param("kb") Long kb);

    int updateDerivedCandidateStatus(@Param("candidateCode") String candidateCode, @Param("status") String status, @Param("staleReason") String staleReason);

    /**
     * Candidates whose base assertions changed: they are stale, not wrong forever.
     */
    List<Map<String, Object>> listCandidatesByBaseAssertion(@Param("kb") Long kb, @Param("codes") List<String> codes);

    int insertDerivationRun(@Param("runCode") String runCode, @Param("kb") Long kb, @Param("ruleCode") String ruleCode, @Param("triggerType") String triggerType);

    int updateDerivationRun(@Param("runCode") String runCode, @Param("status") String status, @Param("scannedCount") int scannedCount, @Param("candidateCount") int candidateCount, @Param("staleCount") int staleCount, @Param("errorMessage") String errorMessage, @Param("finishedTime") String finishedTime);

    List<Map<String, Object>> listDerivationRuns(@Param("kb") Long kb, @Param("ruleCode") String ruleCode, @Param("limit") int limit);

    /**
     * Two-hop entity paths: the raw material of a GRAPH_PATH derivation.
     */
    List<Map<String, Object>> listGraphPathPairs(@Param("kb") Long kb, @Param("firstType") String firstType, @Param("secondType") String secondType, @Param("minConfidence") double minConfidence, @Param("limit") int limit);

    /**
     * Assertions a TEMPLATE derivation can build on.
     */
    List<Map<String, Object>> listDerivationSourceClaims(@Param("kb") Long kb, @Param("minConfidence") double minConfidence, @Param("limit") int limit);

    // ------------------------------------------------------------ knowledge schema
    int upsertSchemaType(@Param("kb") Long kb, @Param("kind") String kind, @Param("typeCode") String typeCode, @Param("label") String label, @Param("description") String description, @Param("propertiesJson") String propertiesJson, @Param("status") String status, @Param("updatedBy") String updatedBy);

    int deleteSchemaType(@Param("kb") Long kb, @Param("kind") String kind, @Param("typeCode") String typeCode);

    Map<String, Object> getSchemaType(@Param("kb") Long kb, @Param("kind") String kind, @Param("typeCode") String typeCode);

    List<Map<String, Object>> listSchemaTypes(@Param("kb") Long kb, @Param("kind") String kind);

    int insertSchemaRelease(@Param("releaseCode") String releaseCode, @Param("kb") Long kb, @Param("version") String version, @Param("state") String state, @Param("definitionJson") String definitionJson, @Param("validationJson") String validationJson, @Param("checksum") String checksum, @Param("publishedBy") String publishedBy);

    List<Map<String, Object>> listSchemaReleases(@Param("kb") Long kb);

    Map<String, Object> getPublishedSchemaRelease(@Param("kb") Long kb);

    int retireSchemaReleases(@Param("kb") Long kb);

    /**
     * How the data actually uses entity / relation types: the schema conformance check.
     */
    List<Map<String, Object>> listEntityTypeUsage(@Param("kb") Long kb);

    List<Map<String, Object>> listRelationTypeUsage(@Param("kb") Long kb);

    List<Map<String, Object>> listKnowledgeTypeUsage(@Param("kb") Long kb);
}
