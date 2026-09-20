package com.jh.knowledgeos.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * Read model for governance insight: knowledge version history and quality overview.
 *
 * Kept separate from {@link KnowledgeMapper} because it only answers read questions
 * about governance state and never mutates authoritative knowledge.
 */
public interface GovernanceMapper {

    /** Version snapshots of a knowledge base, newest first, optionally filtered. */
    List<Map<String, Object>> listVersionSnapshots(
            @Param("kb") Long kb,
            @Param("objectType") String objectType,
            @Param("changeType") String changeType,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countVersionSnapshots(
            @Param("kb") Long kb,
            @Param("objectType") String objectType,
            @Param("changeType") String changeType,
            @Param("keyword") String keyword);

    /** Snapshot counts grouped by object type and change type: answers "what changed, and how". */
    List<Map<String, Object>> countVersionsByGroup(@Param("kb") Long kb);

    /** Status distribution of authoritative knowledge objects, used for trusted coverage. */
    List<Map<String, Object>> countKnowledgeByStatus(@Param("kb") Long kb);

    /** Evidence chain of a versioned object: source → document → page → heading → chunk. */
    List<Map<String, Object>> listEvidenceChain(
            @Param("kb") Long kb,
            @Param("objectType") String objectType,
            @Param("objectCode") String objectCode);

    /** Answer-side citation support aggregated over recent answers. */
    Map<String, Object> aggregateAnswerSupport(@Param("kb") Long kb, @Param("limit") int limit);

    /** Freshness distribution: how much of the knowledge moved recently, and how much already expired. */
    Map<String, Object> aggregateFreshness(@Param("kb") Long kb, @Param("withinDays") int withinDays);

    /** Health counters that turn into worklist entries: review, conflict, ingestion, expiry. */
    Map<String, Object> aggregateHealth(@Param("kb") Long kb);

    /** Latest evaluation run of a knowledge base, the source of Hit@K style metrics. */
    Map<String, Object> latestEvaluationRun(@Param("kb") Long kb);
}
