package com.jh.knowledgeos.service;

/**
 * Canonical AI-service endpoint paths used by backend orchestration and outbox delivery.
 */
public final class AiApiPaths {
    public static final String INDEX_SCHEMA_REBUILD_PREFIX = "/ai/index/schema/rebuild/";
    public static final String INDEX_SCHEMA_REBUILD_BATCH_PREFIX = "/ai/index/schema/rebuild-batch/";
    public static final String INDEX_SCHEMA_ACTIVATE_PREFIX = "/ai/index/schema/activate/";
    public static final String PARSE_DOCUMENT = "/ai/documents/parse";
    public static final String EXTRACT = "/ai/extract";
    public static final String INDEX_CHUNKS = "/ai/index/chunks";
    public static final String INDEX_KNOWLEDGE = "/ai/index/knowledge";
    public static final String QUERY = "/ai/query";
    public static final String INDEX_OBJECT_STATUS = "/ai/index/object/status";
    public static final String INDEX_EXTERNAL_STATS_PREFIX = "/ai/index/external/stats/";
    public static final String ADJUDICATE = "/ai/adjudicate";
    public static final String DETECT_CONFLICTS = "/ai/conflicts/detect";
    public static final String CAPABILITIES = "/ai/capabilities";
    private AiApiPaths() {}
}
