package com.jh.knowledgeos.mapper;

import com.jh.knowledgeos.domain.QueryTraceRecord;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** Query trace and answer-qualification persistence boundary. */
public interface TraceMapper {

    int insertTrace(QueryTraceRecord trace);

    int insertAnswerEvidenceAssessment(@Param("queryId") String queryId, @Param("kb") Long kb, @Param("gateStatus") String gateStatus,
        @Param("completeness") Double completeness, @Param("faithfulness") Double faithfulness, @Param("claimCount") Integer claimCount,
        @Param("supportedCount") Integer supportedCount, @Param("citationCount") Integer citationCount, @Param("unsupportedJson") String
        unsupportedJson, @Param("verificationJson") String verificationJson);

    int insertAnswerQualification(@Param("queryId") String queryId, @Param("kb") Long kb, @Param("policyVersion") String policyVersion,
        @Param("gateStatus") String gateStatus, @Param("evidenceGateStatus") String evidenceGateStatus, @Param("structuredGateStatus") String
        structuredGateStatus, @Param("reasonsJson") String reasonsJson, @Param("originalHash") String originalHash, @Param("deliveredHash") String
        deliveredHash, @Param("claimCount") Integer claimCount);

    int insertAnswerClaimAssessment(@Param("queryId") String queryId, @Param("claimId") String claimId, @Param("policyVersion") String policyVersion,
        @Param("statement") String statement, @Param("decision") String decision, @Param("confidence") Double confidence, @Param("entailmentScore")
        Double entailmentScore, @Param("citationIndexesJson") String citationIndexesJson, @Param("evidenceCodesJson") String evidenceCodesJson,
        @Param("evidenceStatusesJson") String evidenceStatusesJson, @Param("included") Boolean included);

    Map<String, Object> getAnswerQualification(@Param("queryId") String queryId);

    /** The stored evidence-gate document, whose {@code blockReasons} explains what blocked the answer. */
    Map<String, Object> getAnswerVerificationJson(@Param("queryId") String queryId);

    List<Map<String, Object>> listAnswerClaimAssessments(@Param("queryId") String queryId);

    Map<String, Object> summarizeAnswerQualifications(@Param("kb") Long kb, @Param("hours") Integer hours);

    List<Map<String, Object>> listAnswerQualificationPolicyStats(@Param("kb") Long kb, @Param("hours") Integer hours);

    Map<String, Object> getTrace(@Param("qid") String queryId);

    List<Map<String, Object>> listQueryTraces(@Param("kb") Long kb, @Param("limit") Integer limit);
}
