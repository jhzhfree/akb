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
     * 同库已有卡片的标题，供抽取去重使用。
     *
     * <p>只取标题一列：抽取时每行都要比对，取整行会把正文也拉进内存。
     * 去重按归一化后的标题做，所以这里返回原始值，归一化在调用侧统一处理。</p>
     */
    List<String> listCardTitles(@Param("kb") Long kb);

    /**
     * 同库已有断言的三元组（subject|predicate|object），供抽取去重使用。
     *
     * <p>同一条规则被多篇文档命中时（模板化文档尤其常见），模型会为每篇都产出一条
     * 措辞相同的主张；按三元组比对能把它们收敛成一条，而不是让同一句话在库里躺 N 份。</p>
     */
    List<String> listClaimTriples(@Param("kb") Long kb);
}
