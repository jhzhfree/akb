package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** Domain persistence mapper for evidence concerns. */
public interface EvidenceMapper {

    Map<String, Object> getContentUnitDetail(@Param("kb") Long kb, @Param("contentUnitId") Long contentUnitId, @Param("permissionTags") List<String>
        permissionTags);

    Map<String, Object> getEvidenceDetail(@Param("kb") Long kb, @Param("evidenceCode") String evidenceCode, @Param("permissionTags") List<String>
        permissionTags);

    List<Map<String, Object>> listEvidenceForObject(@Param("objectType") String objectType, @Param("objectCode") String objectCode);

    List<Map<String, Object>> listLineageForObject(@Param("kb") Long kb, @Param("objectType") String objectType, @Param("objectCode") String objectCode);

    Map<String, Object> findPrimaryEvidenceAnchor(@Param("objectType") String objectType, @Param("objectCode") String objectCode);

    int batchInsertEvidence(@Param("rows") List<Map<String, Object>> rows);

    int insertEvidence(@Param("evidenceCode") String evidenceCode, @Param("kb") Long kb, @Param("contentUnitId") Long contentUnitId,
        @Param("sourceId") Long sourceId, @Param("quoteText") String quoteText, @Param("locationJson") String locationJson, @Param("authorityScore")
        Double authorityScore);
}
