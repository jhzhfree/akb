package com.jh.knowledgeos.service;

/**
 * Canonical AI-service endpoint paths used by backend orchestration and outbox delivery.
 */
public final class AiApiPaths {

    public static final String INDEX_SCHEMA_REBUILD_PREFIX = "/ai/index/schema/rebuild/";

    public static final String INDEX_SCHEMA_REBUILD_BATCH_PREFIX = "/ai/index/schema/rebuild-batch/";

    public static final String INDEX_SCHEMA_ACL_QUALIFY_PREFIX = "/ai/index/schema/acl-qualify/";

    public static final String INDEX_SCHEMA_GRAPH_REBUILD_PREFIX = "/ai/index/schema/graph-rebuild/";

    public static final String INDEX_SCHEMA_ACTIVATE_PREFIX = "/ai/index/schema/activate/";

    public static final String PARSE_DOCUMENT = "/ai/documents/parse";

    public static final String INSPECT_OKF = "/ai/okf/inspect";

    public static final String EXTRACT = "/ai/extract";

    public static final String INDEX_CHUNKS = "/ai/index/chunks";

    public static final String INDEX_STANDARD_DOCUMENT = "/ai/standard/index";

    public static final String INDEX_KNOWLEDGE = "/ai/index/knowledge";

    public static final String QUERY = "/ai/query";

    /**
     * Retrieval-only pipeline: same recall / fusion / rerank, no generation (design §14 grading).
     */
    public static final String RETRIEVE = "/ai/retrieve";

    public static final String INDEX_OBJECT_STATUS = "/ai/index/object/status";

    public static final String INDEX_OBJECT_ACL = "/ai/index/object/acl";

    public static final String INDEX_EXTERNAL_STATS_PREFIX = "/ai/index/external/stats/";

    public static final String INDEX_PURGE_PREFIX = "/ai/index/knowledge-base/";

    public static final String ADJUDICATE = "/ai/adjudicate";

    public static final String DETECT_CONFLICTS = "/ai/conflicts/detect";

    public static final String ENTITY_RESOLVE = "/ai/entity-resolve";

    public static final String CAPABILITIES = "/ai/capabilities";

    public static final String QUERY_POLICY = "/ai/settings/query-policy";

    public static final String QUERY_POLICY_RESET = "/ai/settings/query-policy/reset";

    public static final String QUERY_POLICY_SCHEMA = "/ai/settings/query-policy/schema";

    public static final String QUERY_POLICY_DEFAULTS = "/ai/settings/query-policy/defaults";

    /**
     * Named retrieval strategies: clone, tune, then switch the live one (design §14).
     */
    public static final String QUERY_POLICY_PROFILES = "/ai/settings/query-policy/profiles";

    public static final String QUERY_POLICY_PROFILE_PREFIX = "/ai/settings/query-policy/profiles/";

    /**
     * Chunking vocabulary and defaults exposed by the AI service parser.
     */
    public static final String CHUNK_POLICY = "/ai/settings/chunk-policy";

    /**
     * Model backed knowledge derivation: propose candidates, never write knowledge directly.
     */
    public static final String DERIVE = "/ai/derive";

    /**
     * Publish the knowledge schema vocabulary to the extraction side.
     */
    public static final String SCHEMA_APPLY = "/ai/schema/apply";

    private AiApiPaths() {
    }
}
