package com.jh.knowledgeos.service;

import com.jh.knowledgeos.mapper.GovernanceMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Governance insight: what changed in the corpus (version history) and how good the corpus is
 * (quality overview).
 *
 * <p>Design §5.1 asks the workbench to answer "how much of this knowledge can be trusted" with four
 * numbers: trusted coverage, citation accuracy, retrieval hit rate and freshness. Every one of them
 * is derived from data the platform already owns — a metric invented for a dashboard would look
 * credible only until someone asks where it came from.</p>
 */
@Service
public class GovernanceInsightService {

    private final GovernanceMapper mapper;

    public GovernanceInsightService(GovernanceMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Version snapshots with group counts, so the page can filter without a second round trip.
     */
    public Map<String, Object> versions(Long kb, String objectType, String changeType, String keyword, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", mapper.countVersionSnapshots(kb, blank(objectType), blank(changeType), blank(keyword)));
        result.put("rows", mapper.listVersionSnapshots(kb, blank(objectType), blank(changeType), blank(keyword), safeLimit, safeOffset));
        result.put("groups", mapper.countVersionsByGroup(kb));
        return result;
    }

    /**
     * Evidence chain behind a versioned object: source → document → page → heading → chunk.
     * A version diff that cannot be traced back to the original text is an opinion, not an audit log.
     */
    public Map<String, Object> evidence(Long kb, String objectType, String objectCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectType", objectType);
        result.put("objectCode", objectCode);
        result.put("items", mapper.listEvidenceChain(kb, objectType, objectCode));
        return result;
    }

    /**
     * Quality overview: coverage, citation support, retrieval hit rate, freshness and worklist counters.
     */
    public Map<String, Object> quality(Long kb, int withinDays) {
        int days = withinDays <= 0 ? 30 : withinDays;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trustedCoverage", trustedCoverage(kb));
        result.put("citation", citation(kb));
        result.put("retrieval", mapper.latestEvaluationRun(kb));
        result.put("freshness", freshness(kb, days));
        result.put("health", mapper.aggregateHealth(kb));
        result.put("statusBreakdown", mapper.countKnowledgeByStatus(kb));
        result.put("withinDays", days);
        return result;
    }

    /**
     * Trusted coverage: approved knowledge over all knowledge, counted over cards and claims alike.
     */
    private Map<String, Object> trustedCoverage(Long kb) {
        List<Map<String, Object>> rows = mapper.countKnowledgeByStatus(kb);
        long approved = 0;
        long total = 0;
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long count = toLong(row.get("total"));
            String status = String.valueOf(row.getOrDefault("status", "UNKNOWN"));
            total += count;
            if ("APPROVED".equals(status)) {
                approved += count;
            }
            byStatus.merge(status, count, Long::sum);
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("approved", approved);
        coverage.put("total", total);
        coverage.put("ratio", ratio(approved, total));
        coverage.put("byStatus", byStatus);
        return coverage;
    }

    /**
     * Citation accuracy: share of factual claims that carried supporting evidence in recent answers.
     */
    private Map<String, Object> citation(Long kb) {
        Map<String, Object> raw = mapper.aggregateAnswerSupport(kb, 200);
        long claimCount = toLong(raw.get("claimCount"));
        long supported = toLong(raw.get("supportedClaimCount"));
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("answerCount", toLong(raw.get("answerCount")));
        citation.put("claimCount", claimCount);
        citation.put("supportedClaimCount", supported);
        citation.put("citationCount", toLong(raw.get("citationCount")));
        citation.put("accuracy", ratio(supported, claimCount));
        citation.put("faithfulness", round(raw.get("faithfulness")));
        citation.put("completeness", round(raw.get("completeness")));
        citation.put("latestTime", raw.get("latestTime"));
        return citation;
    }

    /**
     * Freshness: share of knowledge touched recently, plus the part that already expired unnoticed.
     */
    private Map<String, Object> freshness(Long kb, int withinDays) {
        Map<String, Object> raw = mapper.aggregateFreshness(kb, withinDays);
        long total = toLong(raw.get("total"));
        long fresh = toLong(raw.get("freshCount"));
        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("total", total);
        freshness.put("freshCount", fresh);
        freshness.put("staleCount", total - fresh);
        freshness.put("expiredCount", toLong(raw.get("expiredCount")));
        freshness.put("ratio", ratio(fresh, total));
        freshness.put("latestUpdate", raw.get("latestUpdate"));
        return freshness;
    }

    private static String blank(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * Ratio rounded to four decimals: readable as a percentage, still stable enough to compare.
     */
    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return BigDecimal.valueOf((double) numerator / denominator).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round(Object value) {
        if (value == null) {
            return 0d;
        }
        double parsed = value instanceof Number ? ((Number) value).doubleValue() : 0d;
        return BigDecimal.valueOf(parsed).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
