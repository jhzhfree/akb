package com.jh.knowledgeos.service;

import com.jh.knowledgeos.common.KnowledgeStatus;
import com.jh.knowledgeos.common.PlatformEventTypes;
import com.jh.knowledgeos.mapper.KnowledgeMapper;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.mapper.CardClaimMapper;
import com.jh.knowledgeos.mapper.GovernanceMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle transitions of authoritative knowledge: validity expiry, supersession of older
 * versions and impact analysis for source revocation (design V1.1 sections 5.5, 12 and 17).
 *
 * <p>Every transition writes a knowledge-version snapshot so that "why is this card expired"
 * stays answerable, and publishes an outbox event so indexes converge asynchronously.</p>
 */
@Service
public class KnowledgeLifecycleService {

    private final KnowledgeMapper mapper;

    private final DocumentMapper documentMapper;

    private final CardClaimMapper cardClaimMapper;

    private final GovernanceMapper governanceMapper;


    private final IndexOutboxService indexOutboxService;

    private final TransactionRunner transactionRunner;

    private final PlatformEventPublisher publisher;

    public KnowledgeLifecycleService(KnowledgeMapper mapper, IndexOutboxService indexOutboxService, TransactionRunner transactionRunner,
        PlatformEventPublisher publisher, GovernanceMapper governanceMapper, CardClaimMapper cardClaimMapper,
        DocumentMapper documentMapper) {
        this.cardClaimMapper = cardClaimMapper;
        this.documentMapper = documentMapper;
        this.governanceMapper = governanceMapper;
        this.mapper = mapper;
        this.indexOutboxService = indexOutboxService;
        this.transactionRunner = transactionRunner;
        this.publisher = publisher;
    }

    /**
     * Move every assertion whose validity window has closed to EXPIRED.
     *
     * @return counts plus the affected claim codes, so an operator can audit the sweep
     */
    public Map<String, Object> expireDueClaims(Long knowledgeBaseId) {
        return expireDueClaims(knowledgeBaseId, false);
    }

    /**
     * Expire-validity sweep with an optional preview.
     *
     * <p>{@code dryRun} exists because expiry is irreversible from the operator's point of view:
     * it must be possible to see exactly which assertions a sweep would retire before running it.</p>
     */
    public Map<String, Object> expireDueClaims(Long knowledgeBaseId, boolean dryRun) {
        Date now = new Date();
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> rows = cardClaimMapper.listClaimsWithValidity(knowledgeBaseId);
        List<String> expired = new ArrayList<String>();
        List<String> due = dueClaimCodes(rows, now);
        if (!dryRun) {
            transactionRunner.required(() -> {
                for (String claimCode : due) {
                    governanceMapper.updateClaimStatus(claimCode, KnowledgeStatus.EXPIRED.name());
                    governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CLAIM", claimCode, "EXPIRED", "LIFECYCLE_ENGINE");
                    indexOutboxService.enqueueObjectStatus(knowledgeBaseId, "CLAIM", claimCode, KnowledgeStatus.EXPIRED.name(), "lifecycle_expire_" +
                        claimCode + "_" + UUID.randomUUID());
                    expired.add(claimCode);
                }
                return null;
            });
        }
        summary.put("knowledgeBaseId", knowledgeBaseId);
        summary.put("dryRun", dryRun);
        summary.put("scanned", rows.size());
        summary.put("expired", expired.size());
        summary.put("expiredClaimCodes", expired);
        // In preview mode the candidate list is what the operator reviews; after a real sweep it is
        // the audit trail, so both shapes stay available under the same key.
        summary.put("candidateClaimCodes", due);
        if (!dryRun && !expired.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("scanned", rows.size());
            detail.put("expiredCount", expired.size());
            detail.put("expiredClaimCodes", expired.size() > 20 ? expired.subList(0, 20) : expired);
            publisher.emitUser(PlatformEventTypes.KNOWLEDGE_OBJECT_EXPIRED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, "CLAIM",
                expired.get(0), "有效期到期：失效 " + expired.size() + " 条主张", detail);
        }
        return summary;
    }

    /**
     * Lifecycle dashboard counters (V1.0): how much knowledge is live, how much decayed, and how
     * much is about to decay.
     */
    public Map<String, Object> summary(Long knowledgeBaseId, Integer withinDays) {
        int days = withinDays == null ? 30 : Math.max(1, Math.min(withinDays, 365));
        Map<String, Long> claimStatus = statusCounts(cardClaimMapper.countClaimsByStatus(knowledgeBaseId));
        Map<String, Long> cardStatus = statusCounts(cardClaimMapper.countCardsByStatus(knowledgeBaseId));
        long claimTotal = total(claimStatus);
        long cardTotal = total(cardStatus);
        long claimExpired = value(claimStatus, KnowledgeStatus.EXPIRED.name());
        long claimSuperseded = value(claimStatus, KnowledgeStatus.SUPERSEDED.name());
        long cardSuperseded = value(cardStatus, KnowledgeStatus.SUPERSEDED.name());
        long claimRejected = value(claimStatus, KnowledgeStatus.REJECTED.name());
        long effective = claimTotal - claimExpired - claimSuperseded - claimRejected + cardTotal - cardSuperseded;
        long total = claimTotal + cardTotal;
        long decayed = claimExpired + claimSuperseded + cardSuperseded;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("knowledgeBaseId", knowledgeBaseId);
        result.put("withinDays", days);
        result.put("claimTotal", claimTotal);
        result.put("claimEffective", claimTotal - claimExpired - claimSuperseded - claimRejected);
        result.put("claimExpired", claimExpired);
        result.put("claimSuperseded", claimSuperseded);
        result.put("claimRejected", claimRejected);
        result.put("cardTotal", cardTotal);
        result.put("cardSuperseded", cardSuperseded);
        result.put("effectiveTotal", Math.max(effective, 0L));
        result.put("decayedTotal", decayed);
        result.put("claimStatusCounts", claimStatus);
        result.put("cardStatusCounts", cardStatus);
        result.put("dueNowCount", dueClaimCodes(cardClaimMapper.listClaimsWithValidity(knowledgeBaseId), new Date()).size());
        result.put("expiringSoonCount", expiringClaims(knowledgeBaseId, days).size());
        result.put("expiredRate", total == 0 ? 0.0D : round((double) decayed / (double) total));
        return result;
    }

    /**
     * Assertions whose validity window closes within {@code withinDays}; the renewal queue.
     */
    public List<Map<String, Object>> expiringClaims(Long knowledgeBaseId, Integer withinDays) {
        int days = withinDays == null ? 30 : Math.max(1, Math.min(withinDays, 365));
        Date horizon = new Date(System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L);
        return cardClaimMapper.listExpiringClaims(knowledgeBaseId, horizon);
    }

    /**
     * Claim codes whose validity window has closed but that are still in a live state.
     *
     * <p>Terminal states are skipped so a sweep is idempotent: re-running it must never rewrite a
     * snapshot for an assertion that was already retired.</p>
     */
    private List<String> dueClaimCodes(List<Map<String, Object>> rows, Date now) {
        List<String> due = new ArrayList<String>();
        for (Map<String, Object> row : rows) {
            Date validTo = toDate(row.get("validTo"));
            if (validTo == null || !validTo.before(now)) {
                continue;
            }
            String status = String.valueOf(row.get("status"));
            if (KnowledgeStatus.EXPIRED.name().equals(status) || KnowledgeStatus.SUPERSEDED.name().equals(status)) {
                continue;
            }
            Object claimCode = row.get("claimCode");
            if (claimCode != null) {
                due.add(String.valueOf(claimCode));
            }
        }
        return due;
    }

    private Map<String, Long> statusCounts(List<Map<String, Object>> rows) {
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        for (Map<String, Object> row : rows) {
            Object status = row.get("status");
            Object total = row.get("total");
            String key = status == null ? "UNKNOWN" : String.valueOf(status);
            long value = total instanceof Number ? ((Number) total).longValue() : 0L;
            Long existing = counts.get(key);
            counts.put(key, existing == null ? value : existing + value);
        }
        return counts;
    }

    private long total(Map<String, Long> counts) {
        long total = 0L;
        for (Long value : counts.values()) {
            total += value;
        }
        return total;
    }

    private long value(Map<String, Long> counts, String key) {
        Long value = counts.get(key);
        return value == null ? 0L : value;
    }

    private double round(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
    }

    /**
     * Supersede an older object version with its replacement. Used when a new card version
     * replaces a previous one: history is preserved, only the live status changes.
     */
    public Map<String, Object> supersede(Long knowledgeBaseId, String objectType, String oldObjectCode, String newObjectCode) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        if (oldObjectCode == null || newObjectCode == null || oldObjectCode.equals(newObjectCode)) {
            throw new IllegalArgumentException("替代关系需要两个不同的知识对象编码");
        }
        transactionRunner.required(() -> {
            if ("CARD".equalsIgnoreCase(objectType)) {
                governanceMapper.updateCardStatus(oldObjectCode, KnowledgeStatus.SUPERSEDED.name());
                governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CARD", oldObjectCode, "SUPERSEDED", "LIFECYCLE_ENGINE");
            } else {
                governanceMapper.updateClaimStatus(oldObjectCode, KnowledgeStatus.SUPERSEDED.name());
                governanceMapper.snapshotKnowledgeObject(knowledgeBaseId, "CLAIM", oldObjectCode, "SUPERSEDED", "LIFECYCLE_ENGINE");
            }
            return null;
        });
        summary.put("knowledgeBaseId", knowledgeBaseId);
        summary.put("objectType", objectType);
        summary.put("superseded", oldObjectCode);
        summary.put("supersededBy", newObjectCode);
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("objectType", objectType == null ? null : objectType.toUpperCase());
        detail.put("superseded", oldObjectCode);
        detail.put("supersededBy", newObjectCode);
        publisher.emitUser(PlatformEventTypes.KNOWLEDGE_OBJECT_SUPERSEDED, knowledgeBaseId, PlatformEventPublisher.SOURCE_UI, objectType == null ?
            null : objectType.toUpperCase(), oldObjectCode, "知识对象被替代：" + oldObjectCode + " → " + newObjectCode, detail);
        return summary;
    }

    /**
     * Impact analysis for a revoked source (design V1.1 section 12): which knowledge objects
     * lose their supporting evidence once a source is deleted or withdrawn.
     */
    public Map<String, Object> sourceImpact(Long sourceId) {
        List<Map<String, Object>> rows = mapper.listSourceImpactRows(sourceId);
        Map<String, List<String>> byType = new LinkedHashMap<String, List<String>>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("objectType"));
            List<String> codes = byType.get(key);
            if (codes == null) {
                codes = new ArrayList<String>();
                byType.put(key, codes);
            }
            String code = String.valueOf(row.get("objectCode"));
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("sourceId", sourceId);
        summary.put("affectedCount", deduplicatedCount(rows));
        summary.put("affectedByType", byType);
        summary.put("degraded", rows.isEmpty());
        return summary;
    }

    /**
     * Normalize a validity column value into {@link Date}.
     *
     * <p>MyBatis exposes MySQL DATETIME as {@link Date} or {@link java.time.LocalDateTime}
     * depending on the driver configuration, so both shapes have to be accepted.</p>
     */
    private static Date toDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof java.time.LocalDateTime) {
            java.time.LocalDateTime moment = (java.time.LocalDateTime) value;
            return Date.from(moment.atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
        if (value instanceof Number) {
            return new Date(((Number) value).longValue());
        }
        return null;
    }

    /**
     * Knowledge ledger maintenance: hand a batch of sources their identity card — owner, validity
     * window, scope — or retire them in one go.
     *
     * <p>Only the keys present in the request are written. A stock-take fills the ledger one
     * column at a time, so nominating an owner must not wipe the expiry date entered last week.</p>
     *
     * @return how many rows changed plus the fields actually applied, so the console can tell the
     *         operator what landed
     */
    public Map<String, Object> updateSourceGovernance(Long knowledgeBaseId, Map<String, Object> request) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("缺少知识库 ID");
        }
        List<Long> ids = toIdList(request == null ? null : request.get("ids"));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("请先在台账里选择要维护的来源");
        }
        Long ownerUserId = request.containsKey("ownerUserId") ? toLongOrNull(request.get("ownerUserId")) : null;
        Date effectiveAt = request.containsKey("effectiveAt") ? parseMoment(request.get("effectiveAt"), "生效日期") : null;
        Date expireAt = request.containsKey("expireAt") ? parseMoment(request.get("expireAt"), "失效日期") : null;
        String scope = request.containsKey("scope") ? trimToNull(request.get("scope")) : null;
        String status = request.containsKey("status") ? normalizeSourceStatus(request.get("status")) : null;
        int updated = 0;
        for (Long id : ids) {
            updated += documentMapper.updateSourceGovernance(knowledgeBaseId, id, ownerUserId, effectiveAt, expireAt, scope, status);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requested", ids.size());
        result.put("updated", updated);
        result.put("ownerUserId", ownerUserId);
        result.put("effectiveAt", request.containsKey("effectiveAt") ? request.get("effectiveAt") : null);
        result.put("expireAt", request.containsKey("expireAt") ? request.get("expireAt") : null);
        result.put("scope", scope);
        result.put("status", status);
        return result;
    }

    /** Ids arrive as JSON numbers from the console and as numeric strings from curl; both work. */
    private List<Long> toIdList(Object raw) {
        List<Long> ids = new ArrayList<Long>();
        if (!(raw instanceof List<?>)) {
            return ids;
        }
        for (Object item : (List<?>) raw) {
            Long parsed = toLongOrNull(item);
            if (parsed != null && !ids.contains(parsed)) {
                ids.add(parsed);
            }
        }
        return ids;
    }

    private Long toLongOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * A date picker sends "2026-10-19", an API caller sends "2026-10-19T00:00"; both are accepted
     * and anything else is refused, because a silently ignored expiry date is worse than an error.
     */
    private Date parseMoment(Object raw, String label) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) {
            return null;
        }
        String text = String.valueOf(raw).trim().replace('T', ' ');
        String[] patterns = { "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd" };
        for (String pattern : patterns) {
            try {
                return new java.text.SimpleDateFormat(pattern).parse(text);
            } catch (java.text.ParseException ignored) {
                // try the next shape
            }
        }
        throw new IllegalArgumentException(label + "格式不正确，示例：2026-10-19");
    }

    /** A source is either live or archived: retired knowledge is kept for traceability, never dropped. */
    private String normalizeSourceStatus(Object raw) {
        if (raw == null || String.valueOf(raw).trim().isEmpty()) {
            return null;
        }
        String text = String.valueOf(raw).trim().toUpperCase();
        if (!"ACTIVE".equals(text) && !"ARCHIVED".equals(text)) {
            throw new IllegalArgumentException("来源状态只能是 ACTIVE 或 ARCHIVED");
        }
        return text;
    }

    private String trimToNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? null : text;
    }

    private int deduplicatedCount(List<Map<String, Object>> rows) {
        List<String> seen = new ArrayList<String>();
        for (Map<String, Object> row : rows) {
            String key = row.get("objectType") + ":" + row.get("objectCode");
            if (!seen.contains(key)) {
                seen.add(key);
            }
        }
        return seen.size();
    }
}
