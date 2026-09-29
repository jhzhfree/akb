package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.Claim;
import com.jh.knowledgeos.domain.KnowledgeCard;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Authoritative persistence boundary for cards, claims and their evidence bindings. */
public interface CardClaimMapper {

    int insertCard(KnowledgeCard card);

    int insertClaim(Claim claim);

    int linkCardEvidenceFromDocument(@Param("knowledgeCode") String knowledgeCode, @Param("documentId") Long documentId);

    int linkClaimEvidenceFromDocument(@Param("claimCode") String claimCode, @Param("documentId") Long documentId);

    List<KnowledgeCard> listCards(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    List<Claim> listClaims(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    List<Claim> listClaimsBySubjects(@Param("kb") Long kb, @Param("subjects") List<String> subjects);

    long countCardEvidence(@Param("code") String code);

    long countClaimEvidence(@Param("code") String code);

    List<Map<String, Object>> listClaimsWithValidity(@Param("kb") Long kb);

    List<Map<String, Object>> countClaimsByStatus(@Param("kb") Long kb);

    List<Map<String, Object>> countCardsByStatus(@Param("kb") Long kb);

    List<Map<String, Object>> listExpiringClaims(@Param("kb") Long kb, @Param("horizon") Date horizon);
}
