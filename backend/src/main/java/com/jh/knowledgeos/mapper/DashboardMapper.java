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

    /**
     * Asset counters: source / document / card / assertion / entity / relation.
     *
     * <p>permissionTags 与列表查询共用同一套 ACL 语义（null = 不受约束）：
     * 计数必须与「用户能看到多少条」一致，否则工作台会显示列表里根本不存在的数量。</p>
     */
    Map<String, Object> countCoreObjects(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    /**
     * Per-stage counters of the lifecycle: ingest → parse → extract → derive → judge → publish.
     */
    Map<String, Object> countLifecycle(@Param("kb") Long kb, @Param("permissionTags") List<String> permissionTags);

    /**
     * Governance backlog: pending review / conflict / expired / no-evidence / low-confidence / stale.
     */
    Map<String, Object> countGovernance(@Param("kb") Long kb, @Param("lowConfidence") Double lowConfidence,
        @Param("permissionTags") List<String> permissionTags);

    /**
     * Recently updated knowledge objects, used by the "最近更新" list on the workbench.
     */
    List<Map<String, Object>> listRecentObjects(@Param("kb") Long kb, @Param("limit") int limit,
        @Param("permissionTags") List<String> permissionTags);
}
