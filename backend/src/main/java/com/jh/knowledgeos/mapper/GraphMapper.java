package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.*;
import com.jh.knowledgeos.projection.AuthoritativeIndexSnapshotRow;
import com.jh.knowledgeos.projection.AuthoritativeGraphSnapshotRow;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Domain persistence mapper for graph concerns. */
public interface GraphMapper {

    List<AuthoritativeGraphSnapshotRow> listAuthoritativeGraphSnapshot(@Param("kb") Long kb);

    List<Map<String, Object>> listAuthoritativeGraphEvidenceBindings(@Param("kb") Long kb);

    long countAuthoritativeGraphEntities(@Param("kb") Long kb);

    long countAuthoritativeGraphRelations(@Param("kb") Long kb);

    Long findEntityId(@Param("kb") Long kb, @Param("code") String code);

    Map<String, Object> findEntityRef(@Param("kb") Long kb, @Param("value") String value);

    List<Map<String, Object>> listEntityNames(@Param("kb") Long kb);

    /**
     * Alias rows already on record, read so resolution can match a surface form it has seen
     * before instead of only the canonical name (design V1.1 §5.4, dictionary layer).
     */
    List<Map<String, Object>> listEntityAliasIndex(@Param("kb") Long kb);

    Map<String, Object> getGraphManifest(@Param("releaseId") String releaseId);

    int insertGraphManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId,
        @Param("manifestHash") String manifestHash, @Param("entityCount") Long entityCount, @Param("relationCount") Long relationCount);

    int upsertExtractedEntity(@Param("kb") Long kb, @Param("sourceId") Long sourceId, @Param("code") String code, @Param("type") String type,
        @Param("name") String name, @Param("description") String description, @Param("confidence") Double confidence, @Param("status") String status,
        @Param("tagsJson") String tagsJson, @Param("aclFrom") String aclFrom);

    int upsertExtractedRelation(@Param("kb") Long kb, @Param("sourceId") Long sourceId, @Param("code") String code, @Param("sourceEntityId") Long
        sourceEntityId, @Param("type") String type, @Param("targetEntityId") Long targetEntityId, @Param("confidence") Double confidence,
        @Param("status") String status, @Param("tagsJson") String tagsJson, @Param("aclFrom") String aclFrom);

    int linkClaimEntityAcl(@Param("claimCode") String claimCode, @Param("entityCode") String entityCode, @Param("role") String role,
        @Param("tagsJson") String tagsJson);

    int linkCardEntityAcl(@Param("knowledgeCode") String knowledgeCode, @Param("entityCode") String entityCode, @Param("role") String role,
        @Param("tagsJson") String tagsJson);

    int insertEntityAclScope(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("sourceId") Long sourceId, @Param("documentId") Long
        documentId, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    int recomputeEntityAclMergePolicy(@Param("kb") Long kb, @Param("entityCode") String entityCode);

    int insertRelationAclScope(@Param("kb") Long kb, @Param("relationCode") String relationCode, @Param("sourceId") Long sourceId,
        @Param("documentId") Long documentId, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    int recomputeRelationAclMergePolicy(@Param("kb") Long kb, @Param("relationCode") String relationCode);

    int upsertEntityFacet(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("facetCode") String facetCode, @Param("sourceId") Long
        sourceId, @Param("documentId") Long documentId, @Param("type") String type, @Param("name") String name, @Param("description") String
        description, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    int upsertRelationFacet(@Param("kb") Long kb, @Param("relationCode") String relationCode, @Param("facetCode") String facetCode, @Param("sourceId")
        Long sourceId, @Param("documentId") Long documentId, @Param("sourceCode") String sourceCode, @Param("targetCode") String targetCode,
        @Param("type") String type, @Param("tagsJson") String tagsJson, @Param("scopeHash") String scopeHash, @Param("origin") String origin);

    int insertGraphFacetManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId,
        @Param("manifestHash") String manifestHash, @Param("entityFacetCount") Long entityFacetCount, @Param("relationFacetCount") Long relationFacetCount);

    int insertEntityFacetEvidence(@Param("facetCode") String facetCode, @Param("evidenceId") Long evidenceId, @Param("evidenceHash") String evidenceHash);

    int insertRelationFacetEvidence(@Param("facetCode") String facetCode, @Param("evidenceId") Long evidenceId, @Param("evidenceHash") String evidenceHash);

    int insertGraphEvidenceManifest(@Param("releaseId") String releaseId, @Param("kb") Long kb, @Param("generationId") String generationId,
        @Param("manifestHash") String manifestHash, @Param("evidenceCount") Long evidenceCount, @Param("bindingCount") Long bindingCount);

    int upsertEntityAlias(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("alias") String alias, @Param("aliasType") String
        aliasType, @Param("confidence") Double confidence);

    List<Long> selectCardEntityLinkIds(@Param("kb") Long kb, @Param("fromCode") String fromCode);

    int updateCardEntityLinkTargets(@Param("ids") List<Long> ids, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

    List<Long> selectClaimEntityLinkIds(@Param("kb") Long kb, @Param("fromCode") String fromCode);

    int updateClaimEntityLinkTargets(@Param("ids") List<Long> ids, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

    int updateEntityStatus(@Param("kb") Long kb, @Param("code") String code, @Param("status") String status);

    List<Map<String, Object>> listEntitiesWithUsage(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    Map<String, Object> getEntity(@Param("kb") Long kb, @Param("code") String code);

    List<Map<String, Object>> listEntityAliases(@Param("kb") Long kb, @Param("entityCode") String entityCode);

    int deleteEntityAlias(@Param("kb") Long kb, @Param("entityCode") String entityCode, @Param("alias") String alias);

    int deleteConflictingAliases(@Param("kb") Long kb, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

    int moveEntityAliases(@Param("kb") Long kb, @Param("fromCode") String fromCode, @Param("toCode") String toCode);

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

}
