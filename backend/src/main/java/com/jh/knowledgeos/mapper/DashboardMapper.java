package com.jh.knowledgeos.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * Aggregated reads behind the knowledge workbench (detailed design §3).
 *
 * <p>The workbench needs three shapes of data: the asset counters that make up the KPI strip,
 * the per-stage counters of the knowledge lifecycle, and the open governance workload. They are
 * expressed as three single-row aggregate queries so the dashboard stays one round-trip per card
 * instead of loading full object lists into memory.</p>
 */
public interface DashboardMapper {

    /** Asset counters: source / document / card / assertion / entity / relation. */
    Map<String, Object> countCoreObjects(@Param("kb") Long kb);

    /** Per-stage counters of the lifecycle: ingest → parse → extract → derive → judge → publish. */
    Map<String, Object> countLifecycle(@Param("kb") Long kb);

    /** Governance backlog: pending review / conflict / expired / no-evidence / low-confidence / stale. */
    Map<String, Object> countGovernance(@Param("kb") Long kb, @Param("lowConfidence") Double lowConfidence);

    /** Recently updated knowledge objects, used by the "最近更新" list on the workbench. */
    List<Map<String, Object>> listRecentObjects(@Param("kb") Long kb, @Param("limit") int limit);
}
