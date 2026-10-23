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
public interface DerivationMapper {

    int insertDerivationRule(@Param("ruleCode") String ruleCode, @Param("kb") Long kb, @Param("presetCode") String presetCode, @Param("name") String
            name, @Param("derivationType") String derivationType, @Param("scheduleType") String scheduleType, @Param("cronExpr") String cronExpr,
            @Param("nextFireAt") String nextFireAt, @Param("sourceScopeJson") String sourceScopeJson, @Param("definitionJson") String definitionJson,
            @Param("algorithmVersion") String algorithmVersion, @Param("createdBy") String createdBy);

    int updateDerivationRule(@Param("ruleCode") String ruleCode, @Param("name") String name, @Param("derivationType") String derivationType,
            @Param("scheduleType") String scheduleType, @Param("cronExpr") String cronExpr, @Param("nextFireAt") String nextFireAt,
            @Param("sourceScopeJson") String sourceScopeJson, @Param("definitionJson") String definitionJson, @Param("algorithmVersion") String
            algorithmVersion, @Param("enabled") boolean enabled);

    int resetDerivationRuleDefinition(@Param("ruleCode") String ruleCode, @Param("name") String name, @Param("derivationType") String derivationType,
            @Param("definitionJson") String definitionJson);

    int updateDerivationRuleState(@Param("ruleCode") String ruleCode, @Param("status") String status, @Param("lastFireAt") String lastFireAt,
            @Param("nextFireAt") String nextFireAt, @Param("lastError") String lastError);

    int deleteDerivationRule(@Param("ruleCode") String ruleCode);

    Map<String, Object> getDerivationRule(@Param("ruleCode") String ruleCode);

    List<Map<String, Object>> listDerivationRules(@Param("kb") Long kb);

    List<String> listDerivationRulePresetCodes(@Param("kb") Long kb);

    List<Map<String, Object>> listDueDerivationRules(@Param("now") String now);

    int insertDerivedCandidate(@Param("candidateCode") String candidateCode, @Param("kb") Long kb, @Param("ruleCode") String ruleCode,
            @Param("derivationType") String derivationType, @Param("algorithmVersion") String algorithmVersion, @Param("title") String title,
            @Param("content") String content, @Param("confidence") double confidence, @Param("baseAssertionCodes") String baseAssertionCodes,
            @Param("graphPath") String graphPath, @Param("evidenceJson") String evidenceJson, @Param("derivedFromJson") String derivedFromJson,
            @Param("permissionTags") String permissionTags, @Param("authorityScore") double authorityScore, @Param("contentHash") String contentHash,
            @Param("createdBy") String createdBy);

    int touchDerivedCandidate(@Param("ruleCode") String ruleCode, @Param("contentHash") String contentHash, @Param("confidence") double confidence,
            @Param("status") String status);

    Map<String, Object> getDerivedCandidate(@Param("candidateCode") String candidateCode);

    Map<String, Object> getDerivedCandidateByHash(@Param("ruleCode") String ruleCode, @Param("contentHash") String contentHash);

    List<Map<String, Object>> listDerivedCandidates(@Param("kb") Long kb, @Param("status") String status, @Param("ruleCode") String ruleCode,
            @Param("limit") int limit);

    int updateDerivedCandidateStatus(@Param("candidateCode") String candidateCode, @Param("status") String status, @Param("staleReason") String staleReason);

    List<Map<String, Object>> listCandidatesByBaseAssertion(@Param("kb") Long kb, @Param("codes") List<String> codes);

    int insertDerivationRun(@Param("runCode") String runCode, @Param("kb") Long kb, @Param("ruleCode") String ruleCode, @Param("triggerType") String
            triggerType);

    int updateDerivationRun(@Param("runCode") String runCode, @Param("status") String status, @Param("scannedCount") int scannedCount,
            @Param("candidateCount") int candidateCount, @Param("staleCount") int staleCount, @Param("errorMessage") String errorMessage,
            @Param("finishedTime") String finishedTime);

    List<Map<String, Object>> listDerivationRuns(@Param("kb") Long kb, @Param("ruleCode") String ruleCode, @Param("limit") int limit);

    /**
     * 衍生零产出时的图现状：有多少关系、置信度最高多少、有没有能首尾相接的两跳。
     *
     * <p>没有这些数，一次“产出 0”无法区分三种完全不同的情况：库里根本没有关系、关系
     * 接不成两跳、关系质量达不到规则阈值。三者的处置方向完全不同，只能靠猜。</p>
     */
    Map<String, Object> diagnoseGraph(@Param("kb") Long kb, @Param("minConfidence") double minConfidence);

    List<Map<String, Object>> listGraphPathPairs(@Param("kb") Long kb, @Param("firstType") String firstType, @Param("secondType") String secondType,
            @Param("minConfidence") double minConfidence, @Param("limit") int limit);

    List<Map<String, Object>> listDerivationSourceClaims(@Param("kb") Long kb, @Param("minConfidence") double minConfidence, @Param("limit") int limit);

    /**
     * Active relations with both endpoint names, read once so a multi-hop walk happens in
     * memory instead of issuing one query per hop per branch.
     */
    List<Map<String, Object>> listActiveRelations(@Param("kb") Long kb, @Param("minConfidence") double minConfidence,
            @Param("limit") int limit);
}
