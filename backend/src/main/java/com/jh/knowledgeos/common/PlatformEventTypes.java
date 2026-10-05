package com.jh.knowledgeos.common;

/**
 * Canonical platform event names shared by every producer and every SSE subscriber.
 *
 * <p>The names are part of the external contract: a client filters with {@code ?types=} using
 * exactly these tokens, so they are stable even when the producing service is refactored.</p>
 */
public final class PlatformEventTypes {

    // 知识库生命周期
    public static final String KNOWLEDGE_BASE_CREATED = "KNOWLEDGE_BASE_CREATED";

    public static final String KNOWLEDGE_BASE_UPDATED = "KNOWLEDGE_BASE_UPDATED";

    public static final String KNOWLEDGE_BASE_ACCESS_UPDATED = "KNOWLEDGE_BASE_ACCESS_UPDATED";

    public static final String KNOWLEDGE_BASE_ARCHIVED = "KNOWLEDGE_BASE_ARCHIVED";

    public static final String KNOWLEDGE_BASE_PURGED = "KNOWLEDGE_BASE_PURGED";

    // 文档摄入
    public static final String INGESTION_DOCUMENT_RECEIVED = "INGESTION_DOCUMENT_RECEIVED";

    public static final String INGESTION_STAGE_CHANGED = "INGESTION_STAGE_CHANGED";

    public static final String INGESTION_COMPLETED = "INGESTION_COMPLETED";

    public static final String INGESTION_FAILED = "INGESTION_FAILED";

    // 抽取与知识对象
    public static final String EXTRACTION_COMPLETED = "EXTRACTION_COMPLETED";

    public static final String KNOWLEDGE_OBJECT_CREATED = "KNOWLEDGE_OBJECT_CREATED";

    public static final String KNOWLEDGE_OBJECT_UPDATED = "KNOWLEDGE_OBJECT_UPDATED";

    public static final String KNOWLEDGE_OBJECT_STATUS_CHANGED = "KNOWLEDGE_OBJECT_STATUS_CHANGED";

    public static final String KNOWLEDGE_OBJECT_SUPERSEDED = "KNOWLEDGE_OBJECT_SUPERSEDED";

    public static final String KNOWLEDGE_OBJECT_EXPIRED = "KNOWLEDGE_OBJECT_EXPIRED";

    // 治理：人工/智能裁断、冲突消解
    public static final String GOVERNANCE_DECISION = "GOVERNANCE_DECISION";

    public static final String CONFLICT_RESOLVED = "CONFLICT_RESOLVED";

    // 检索与问答
    public static final String RETRIEVAL_EXECUTED = "RETRIEVAL_EXECUTED";

    // 权限
    public static final String ACL_CHANGED = "ACL_CHANGED";

    // 设计说明书 §17.2 事件清单：以业务动作命名，供外部订阅方按领域语义过滤
    public static final String SOURCE_SYNCED = "SOURCE_SYNCED";

    public static final String CHUNK_CHANGED = "CHUNK_CHANGED";

    public static final String KNOWLEDGE_CANDIDATE_CREATED = "KNOWLEDGE_CANDIDATE_CREATED";

    public static final String ASSERTION_APPROVED = "ASSERTION_APPROVED";

    public static final String ASSERTION_INVALIDATED = "ASSERTION_INVALIDATED";

    public static final String KNOWLEDGE_CONFLICT_DETECTED = "KNOWLEDGE_CONFLICT_DETECTED";

    public static final String KNOWLEDGE_JUDGMENT_COMPLETED = "KNOWLEDGE_JUDGMENT_COMPLETED";

    public static final String KNOWLEDGE_DERIVED_STALE = "KNOWLEDGE_DERIVED_STALE";

    public static final String INDEX_REBUILD_REQUESTED = "INDEX_REBUILD_REQUESTED";

    public static final String EVALUATION_COMPLETED = "EVALUATION_COMPLETED";

    public static final String KNOWLEDGE_GAP_DETECTED = "KNOWLEDGE_GAP_DETECTED";

    // 外部知识的生命周期：条目入库、被新版替换、源端删除后归档
    public static final String CONNECTOR_ITEM_INGESTED = "CONNECTOR_ITEM_INGESTED";

    public static final String CONNECTOR_ITEM_SUPERSEDED = "CONNECTOR_ITEM_SUPERSEDED";

    public static final String CONNECTOR_ITEM_ARCHIVED = "CONNECTOR_ITEM_ARCHIVED";

    private PlatformEventTypes() {
    }

    /**
     * Every declared type, so the console can render the filter list without hard-coding it.
     */
    public static String[] all() {
        return new String[] { KNOWLEDGE_BASE_CREATED, KNOWLEDGE_BASE_UPDATED, KNOWLEDGE_BASE_ACCESS_UPDATED, KNOWLEDGE_BASE_ARCHIVED,
            KNOWLEDGE_BASE_PURGED,
            INGESTION_DOCUMENT_RECEIVED, INGESTION_STAGE_CHANGED, INGESTION_COMPLETED, INGESTION_FAILED, EXTRACTION_COMPLETED,
            KNOWLEDGE_OBJECT_CREATED, KNOWLEDGE_OBJECT_UPDATED, KNOWLEDGE_OBJECT_STATUS_CHANGED, KNOWLEDGE_OBJECT_SUPERSEDED,
            KNOWLEDGE_OBJECT_EXPIRED, GOVERNANCE_DECISION, CONFLICT_RESOLVED, RETRIEVAL_EXECUTED, ACL_CHANGED, SOURCE_SYNCED, CHUNK_CHANGED,
            KNOWLEDGE_CANDIDATE_CREATED, ASSERTION_APPROVED, ASSERTION_INVALIDATED, KNOWLEDGE_CONFLICT_DETECTED, KNOWLEDGE_JUDGMENT_COMPLETED,
            KNOWLEDGE_DERIVED_STALE, INDEX_REBUILD_REQUESTED, EVALUATION_COMPLETED, KNOWLEDGE_GAP_DETECTED };
    }
}
