package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.DashboardMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read model of the knowledge workbench (detailed design §3).
 *
 * <p>The workbench is the landing page after login and has to answer three questions in one screen:
 * how much knowledge do we have, where is it stuck in the lifecycle, and what needs human action
 * today. Everything is derived from authoritative tables on read — no duplicated dashboard table
 * that could drift away from the knowledge itself.</p>
 *
 * <p>The health score is intentionally a simple, explainable penalty model: every open governance
 * item lowers the score with a fixed weight, capped so one broken area cannot push it below zero.</p>
 */
@Service
public class DashboardService {

    /**
     * 低于该置信度视作「低置信」，与裁决策略的默认阈值保持一致。
     */
    private static final double DEFAULT_LOW_CONFIDENCE = 0.6d;

    private final DashboardMapper mapper;

    public DashboardService(DashboardMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * KPI strip: asset counters plus the derived health score and the most recent objects.
     */
    public Map<String, Object> summary(Long kb, List<String> permissionTags) {
        Map<String, Object> counts = mapper.countCoreObjects(kb, permissionTags);
        Map<String, Object> governance = governance(kb, DEFAULT_LOW_CONFIDENCE, permissionTags);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("sourceCount", num(counts.get("sourceCount")));
        result.put("documentCount", num(counts.get("documentCount")));
        result.put("chunkCount", num(counts.get("chunkCount")));
        result.put("cardCount", num(counts.get("cardCount")));
        result.put("claimCount", num(counts.get("claimCount")));
        result.put("entityCount", num(counts.get("entityCount")));
        result.put("relationCount", num(counts.get("relationCount")));
        result.put("healthScore", num(governance.get("healthScore")));
        result.put("pendingReviewCount", num(governance.get("pendingReview")));
        result.put("openConflictCount", num(governance.get("conflict")));
        result.put("recent", mapper.listRecentObjects(kb, 8, permissionTags));
        return result;
    }

    /**
     * Lifecycle stages: 接入 → 解析 → 抽取 → 衍生 → 裁决 → 发布，每阶段带异常数与点击目标。
     */
    public Map<String, Object> lifecycle(Long kb, List<String> permissionTags) {
        Map<String, Object> raw = mapper.countLifecycle(kb, permissionTags);
        List<Map<String, Object>> stages = new ArrayList<Map<String, Object>>();
        // 接入阶段的异常只看“没接干净”的对象：失败或脏文档，而不是文档总数。
        stages.add(stage("接入", "INGEST", num(raw.get("ingestSource")) + num(raw.get("ingestDocument")), num(raw.get("publishDirty")), "create.sources"));
        stages.add(stage("解析", "PARSE", num(raw.get("parseSuccess")) + num(raw.get("parseFailed")) + num(raw.get("parseRunning")),
            num(raw.get("parseFailed")), "create.import"));
        stages.add(stage("抽取", "EXTRACT", num(raw.get("extractCandidate")) + num(raw.get("extractUncertain")), num(raw.get("extractUncertain")),
            "create.extraction"));
        stages.add(stage("衍生", "DERIVE", num(raw.get("derivedStale")), num(raw.get("derivedStale")), "create.derivation"));
        stages.add(stage("裁决", "JUDGE", num(raw.get("judgePending")) + num(raw.get("judgeConflict")), num(raw.get("judgeConflict")), "create.judgment"));
        stages.add(stage("发布", "PUBLISH", num(raw.get("publishCardActive")) + num(raw.get("publishClaimActive")), num(raw.get("publishDirty")),
            "workbench.assets"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("stages", stages);
        return result;
    }

    /**
     * Governance backlog. Only actionable counters are returned, each with a severity so the
     * workbench can colour blocking problems red, pending work orange and healthy state green.
     */
    public Map<String, Object> governance(Long kb, Double lowConfidence, List<String> permissionTags) {
        double threshold = lowConfidence == null || lowConfidence <= 0 ? DEFAULT_LOW_CONFIDENCE : lowConfidence;
        Map<String, Object> raw = mapper.countGovernance(kb, threshold, permissionTags);
        long pendingReview = num(raw.get("pendingReviewClaim")) + num(raw.get("pendingReviewCard"));
        long conflict = num(raw.get("conflict"));
        long expired = num(raw.get("expired"));
        long noEvidence = num(raw.get("noEvidenceClaim")) + num(raw.get("noEvidenceCard"));
        long lowConfidenceCount = num(raw.get("lowConfidenceClaim")) + num(raw.get("lowConfidenceCard"));
        long stale = num(raw.get("staleDerived"));
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        items.add(item("pendingReview", "待审核", pendingReview, pendingReview > 0 ? "warn" : "ok", "create.judgment"));
        items.add(item("conflict", "冲突", conflict, conflict > 0 ? "block" : "ok", "create.conflict"));
        items.add(item("expired", "过期", expired, expired > 0 ? "warn" : "ok", "create.judgment"));
        items.add(item("noEvidence", "无证据", noEvidence, noEvidence > 0 ? "warn" : "ok", "workbench.assets"));
        items.add(item("lowConfidence", "低置信", lowConfidenceCount, lowConfidenceCount > 0 ? "warn" : "ok", "create.judgment"));
        items.add(item("staleDerived", "衍生失效", stale, stale > 0 ? "block" : "ok", "create.derivation"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", kb);
        result.put("healthScore", healthScore(pendingReview, conflict, expired, noEvidence, lowConfidenceCount, stale));
        result.put("pendingReview", pendingReview);
        result.put("conflict", conflict);
        result.put("expired", expired);
        result.put("noEvidence", noEvidence);
        result.put("lowConfidence", lowConfidenceCount);
        result.put("staleDerived", stale);
        result.put("items", items);
        return result;
    }

    /**
     * Penalty model: conflicts and stale derivations block, pending/expired work only warns.
     */
    private long healthScore(long pendingReview, long conflict, long expired, long noEvidence, long lowConfidence, long stale) {
        double penalty = conflict * 2.0d + stale * 1.5d + pendingReview * 0.6d + expired * 0.5d + noEvidence * 0.4d + lowConfidence * 0.3d;
        long score = Math.round(100.0d - Math.min(penalty, 60.0d));
        return Math.max(score, 0L);
    }

    private Map<String, Object> stage(String label, String code, long count, long abnormal, String target) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("label", label);
        row.put("code", code);
        row.put("count", count);
        row.put("abnormal", abnormal);
        row.put("target", target);
        return row;
    }

    private Map<String, Object> item(String key, String label, long count, String level, String target) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("key", key);
        row.put("label", label);
        row.put("count", count);
        row.put("level", level);
        row.put("target", target);
        return row;
    }

    private long num(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
