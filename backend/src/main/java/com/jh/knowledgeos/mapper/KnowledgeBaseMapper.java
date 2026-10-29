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
public interface KnowledgeBaseMapper {
    @org.apache.ibatis.annotations.Select("SELECT * FROM t_knowledge_base WHERE base_code=#{code} AND status='ACTIVE' LIMIT 1")
    com.jh.knowledgeos.domain.KnowledgeBase getExternalKnowledgeBaseByCode(@org.apache.ibatis.annotations.Param("code") String code);


    List<KnowledgeBase> listKnowledgeBases(@Param("userId") Long userId, @Param("all") boolean all);

    KnowledgeBase getKnowledgeBase(@Param("id") Long id);

    int insertKnowledgeBase(KnowledgeBase base);

    int updateKnowledgeBaseStatus(@Param("id") Long id, @Param("status") String status);

    int updateKnowledgeBaseAccess(@Param("id") Long id, @Param("visibility") String visibility, @Param("ownerGroupId") Long ownerGroupId);

    int updateKnowledgeBase(@Param("id") Long id, @Param("name") String name, @Param("description") String description,
        @Param("visibility") String visibility, @Param("ownerGroupId") Long ownerGroupId, @Param("defaultQuery") String defaultQuery);

    int updateKnowledgeBaseOwner(@Param("id") Long id, @Param("createdBy") Long createdBy);

    /**
     * 删除工作区本身。只应在知识已清空之后调用——反过来的顺序会留下一个指向已删知识库的
     * 检索索引，而那个索引再也没人能按知识库 id 清掉。
     */
    int deleteKnowledgeBase(@Param("id") Long id);

    Map<String, Object> getKbScenario(@Param("kb") Long kb);

    int saveKbScenario(@Param("kb") Long kb, @Param("scenario") String scenario, @Param("profileJson") String profileJson,
        @Param("updatedBy") String updatedBy);

    int resetKbScenario(@Param("kb") Long kb);
}
