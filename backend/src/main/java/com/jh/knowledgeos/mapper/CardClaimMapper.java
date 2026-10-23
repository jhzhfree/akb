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

    /**
     * 按抽取幂等键找已有卡片编码。
     *
     * <p>重跑同一篇文档时用来判定“这条已经写过了”，命中就只补证据、不重复插入。</p>
     */
    String findCardCodeByDedupKey(@Param("kb") Long kb, @Param("dedupKey") String dedupKey);

    /**
     * 按等价指纹找同库的其它卡片（不含自己）。
     *
     * <p>用于记录重复关系。命中不合并：两侧都是独立资产，只是多一条待裁决的重复记录。</p>
     */
    List<Map<String, Object>> findCardCodesByFingerprint(@Param("kb") Long kb, @Param("fingerprint") String fingerprint,
        @Param("selfCode") String selfCode);

    /** 按抽取幂等键找已有断言编码，语义同 {@link #findCardCodeByDedupKey}。 */
    String findClaimCodeByDedupKey(@Param("kb") Long kb, @Param("dedupKey") String dedupKey);

    /** 按等价指纹找同库的其它断言，语义同 {@link #findCardCodesByFingerprint}。 */
    List<Map<String, Object>> findClaimCodesByFingerprint(@Param("kb") Long kb, @Param("fingerprint") String fingerprint,
        @Param("selfCode") String selfCode);

    /**
     * 校验模型给出的证据：确实存在、属于这个知识库、并且出自这篇文档。
     *
     * <p>只按 evidence_code 命中是不够的——模型可能引用别的文档的证据，也可能给出根本
     * 不存在的编码。这两种情况都不该变成“这条知识有据可查”。</p>
     */
    List<Long> findEvidenceIdsByCodes(@Param("kb") Long kb, @Param("codes") List<String> codes,
        @Param("documentId") Long documentId);

    /**
     * 按证据 id 精确关联卡片。
     *
     * <p>支持分给 0.90：证据是模型点名的，比“整篇文档都算这条知识的证据”更有指向性。</p>
     */
    int linkCardEvidenceByIds(@Param("kb") Long kb, @Param("knowledgeCode") String knowledgeCode, @Param("ids") List<Long> ids);

    /** 按证据 id 精确关联断言，语义同 {@link #linkCardEvidenceByIds}。 */
    int linkClaimEvidenceByIds(@Param("kb") Long kb, @Param("claimCode") String claimCode, @Param("ids") List<Long> ids);
}
