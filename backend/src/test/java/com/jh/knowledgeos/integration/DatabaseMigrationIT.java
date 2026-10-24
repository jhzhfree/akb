package com.jh.knowledgeos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import com.jh.knowledgeos.domain.QueryTraceRecord;
import com.jh.knowledgeos.mapper.TraceMapper;
import javax.sql.DataSource;
import com.jh.knowledgeos.mapper.DocumentMapper;
import com.jh.knowledgeos.mapper.ExtractionPostStageMapper;
import com.jh.knowledgeos.domain.ExtractionPostStage;
import com.jh.knowledgeos.mapper.IndexMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public class DatabaseMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new
        MySQLContainer<>("mysql:8.0.36").withDatabaseName("knowledge_os").withUsername("knowledge").withPassword("knowledge123");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.redis.host", () -> "127.0.0.1");
        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("indexing.outbox.enabled", () -> "false");
        registry.add("knowledge.ai-connect-timeout-ms", () -> "100");
        registry.add("knowledge.ai-read-timeout-ms", () -> "1000");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TraceMapper traceMapper;

    @Autowired
    private DocumentMapper documents;

    @Autowired
    private ExtractionPostStageMapper extractionStages;

    @Autowired
    private IndexMapper indexMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional
    void currentEvidenceContractAndIndexStageFollowAllPendingEvents() {
        long id = 910001L;
        jdbc.update("INSERT INTO t_knowledge_base(id,base_code,name) VALUES(?, 'KB_QUALITY_IT','质量验证')", id);
        jdbc.update("INSERT INTO t_source(id,source_code,knowledge_base_id,name) VALUES(?,'SRC_QUALITY_IT',?,'policy')", id, id);
        jdbc.update("INSERT INTO t_source_version(id,source_id,version_no) VALUES(?,?,1)", id, id);
        jdbc.update("INSERT INTO t_document(id,source_version_id,document_code) VALUES(?,?,'DOC_QUALITY_IT')", id, id);
        jdbc.update("INSERT INTO t_content_unit(id,document_id,unit_code,content,status) VALUES(?,?,'UC_ACTIVE','有效正文','ACTIVE')", id, id);
        jdbc.update("INSERT INTO t_content_unit(id,document_id,unit_code,content,status) VALUES(?,?,'UC_OLD','旧正文','SUPERSEDED')", id+1, id);
        jdbc.update("INSERT INTO t_evidence(evidence_code,knowledge_base_id,content_unit_id,status) VALUES('EV_ACTIVE',?,?,'ACTIVE')", id, id);
        jdbc.update("INSERT INTO t_evidence(evidence_code,knowledge_base_id,content_unit_id,status) VALUES('EV_OLD',?,?,'ACTIVE')", id, id+1);
        assertEquals(id, documents.lockDocumentForExtraction(id, id));
        java.util.List<Map<String, Object>> units = documents.findUnitsWithEvidence(id);
        assertEquals(1, units.size());
        assertEquals("UC_ACTIVE", units.get(0).get("unit_id"));
        assertEquals("EV_ACTIVE", units.get(0).get("evidence_code"));
        assertNotNull(units.get(0).get("evidence_id"));
        String key = "KB:"+id+":DOCUMENT:"+id;
        jdbc.update("INSERT INTO t_index_outbox(event_id,idempotency_key,knowledge_base_id,aggregate_type,aggregate_key,event_type,"
            + "object_version,target_path,payload_json,status) VALUES('EV_QIT','IDEM_QIT',?,'DOCUMENT',?,'DOCUMENT_CHUNKS_UPSERT',"
            + "1,'/test','{}','PENDING')", id, key);
        assertTrue(documents.documentsForIndexAggregate(id, key).contains(id));
        documents.refreshDocumentIndexStatus(id);
        assertEquals("PENDING", jdbc.queryForObject("SELECT index_status FROM t_document WHERE id=?", String.class, id));
        jdbc.update("UPDATE t_index_outbox SET status='DEAD' WHERE event_id='EV_QIT'");
        documents.refreshDocumentIndexStatus(id);
        assertEquals("FAILED", jdbc.queryForObject("SELECT index_status FROM t_document WHERE id=?", String.class, id));
        jdbc.update("UPDATE t_index_outbox SET status='SUCCEEDED' WHERE event_id='EV_QIT'");
        documents.refreshDocumentIndexStatus(id);
        assertEquals("INDEXED", jdbc.queryForObject("SELECT index_status FROM t_document WHERE id=?", String.class, id));
        documents.updateExtractionSummary(id, "PARTIAL", "{\"created\":1}");
        assertEquals("PARTIAL", jdbc.queryForObject("SELECT extraction_status FROM t_document WHERE id=?", String.class, id));
    }

    @Test
    void flywayCreatesRequiredTablesAndTraceColumns() throws Exception {
        try (Connection connection = dataSource.getConnection();
            Statement st = connection.createStatement()) {
            assertTrue(tableExists(st, "t_knowledge_card"));
            assertTrue(tableExists(st, "t_claim"));
            assertTrue(tableExists(st, "t_query_trace"));
            assertTrue(tableExists(st, "t_query_feedback"));
            assertTrue(tableExists(st, "t_agent_memory"));
            assertTrue(tableExists(st, "t_agent_memory_scope_access"));
            assertTrue(tableExists(st, "t_agent_memory_governance_event"));
            assertTrue(tableExists(st, "t_api_key_principal_audit"));
            assertTrue(tableExists(st, "t_principal_identity_link"));
            assertTrue(tableExists(st, "t_principal_identity_audit"));
            assertTrue(tableExists(st, "t_extraction_post_stage"));
            assertTrue(columnExists(st, "t_document", "extraction_status"));
            assertTrue(columnExists(st, "t_document", "index_status"));
            assertTrue(columnExists(st, "t_document", "extraction_summary_json"));
            assertTrue(columnExists(st, "t_derivation_run", "execution_summary_json"));
            assertTrue(columnExists(st, "t_knowledge_card", "dedup_key"));
            assertTrue(columnExists(st, "t_extraction_post_stage", "idempotency_key"));
            assertTrue(columnExists(st, "t_claim", "claim_fingerprint"));
            assertTrue(columnExists(st, "t_principal", "principal_type"));
            assertTrue(columnExists(st, "t_api_key", "principal_id"));
            assertTrue(columnExists(st, "t_principal_kb_grant", "permission_tags"));
            assertTrue(columnExists(st, "t_principal_kb_grant_audit", "permission_tags"));
            assertTrue(tableExists(st, "t_index_outbox"));
            assertTrue(tableExists(st, "t_index_aggregate_version"));
            assertTrue(tableExists(st, "t_index_delivery_attempt"));
            assertTrue(tableExists(st, "t_index_outbox_archive"));
            assertTrue(tableExists(st, "t_index_delivery_attempt_archive"));
            assertTrue(tableExists(st, "t_index_schema_release"));
            assertTrue(tableExists(st, "t_index_schema_snapshot_audit"));
            assertTrue(columnExists(st, "t_index_schema_release", "snapshot_checksum"));
            assertTrue(columnExists(st, "t_index_schema_release", "source_high_watermark"));
            assertTrue(columnExists(st, "t_query_trace", "trace_id"));
            assertTrue(columnExists(st, "t_query_trace", "actor_user_id"));
            assertTrue(columnExists(st, "t_api_key", "memory_namespace"));
            assertTrue(columnExists(st, "t_api_key", "memory_agent_code"));
            assertTrue(columnExists(st, "t_api_key", "memory_read_enabled"));
            assertTrue(columnExists(st, "t_api_key", "memory_write_enabled"));
            assertTrue(columnExists(st, "t_query_trace", "rerank_json"));
            assertTrue(columnExists(st, "t_query_trace", "latency_ms"));
            assertTrue(indexExists(st, "t_claim", "idx_claim_lookup"));
            assertTrue(indexExists(st, "t_knowledge_card", "idx_card_updated"));
            assertTrue(indexExists(st, "t_evidence", "idx_evidence_source_status"));
            assertTrue(indexExists(st, "t_index_outbox", "idx_index_outbox_due"));
            assertTrue(indexExists(st, "t_index_outbox", "idx_index_outbox_aggregate"));
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")) {
                assertTrue(rs.next());
                assertTrue(rs.getInt(1) >= 6);
            }
        }
    }

    @Test
    @Transactional
    void extractionPostStagesAreScopedToCurrentRunAndCanBeReplayed() {
        long id = 910021L;
        jdbc.update("INSERT INTO t_knowledge_base(id,base_code,name) VALUES(?, 'KB_STAGE_IT','阶段恢复验证')", id);
        jdbc.update("INSERT INTO t_source(id,source_code,knowledge_base_id,name) VALUES(?,'SRC_STAGE_IT',?,'policy')", id, id);
        jdbc.update("INSERT INTO t_source_version(id,source_id,version_no) VALUES(?,?,1)", id, id);
        jdbc.update("INSERT INTO t_document(id,source_version_id,document_code,extraction_summary_json) VALUES(?,?,?,'{\"runId\":\"run-current\"}')",
            id, id, "DOC_STAGE_IT");

        ExtractionPostStage current = postStage(id, "run-current", "CONFLICT_DETECTION", "EXTRACT_POST:run-current:CONFLICT_DETECTION");
        ExtractionPostStage stale = postStage(id, "run-stale", "ENTITY_RESOLUTION", "EXTRACT_POST:run-stale:ENTITY_RESOLUTION");
        extractionStages.insertStage(current);
        extractionStages.insertStage(stale);

        assertEquals(1, extractionStages.selectDueStages(10).size());
        assertEquals(current.getId(), extractionStages.selectDueStages(10).get(0).getId());
        assertEquals(1, extractionStages.claimStage(current.getId(), "integration-test"));
        assertEquals(1, extractionStages.markStageRetry(current.getId(), "DEAD", "injected", java.time.LocalDateTime.now()));
        assertEquals(1, extractionStages.replayFailedStages(id, "run-current"));
        ExtractionPostStage replayed = extractionStages.findStage(current.getId());
        assertEquals("PENDING", replayed.getStatus());
        assertEquals(0, replayed.getAttemptCount());
        assertEquals(1, replayed.getManualReplayCount());
        assertNull(replayed.getLastError());
    }

    @Test
    @Transactional
    void extractionPostStagesWaitForEarlierStageToComplete() {
        long id = 910022L;
        jdbc.update("INSERT INTO t_knowledge_base(id,base_code,name) VALUES(?, 'KB_STAGE_ORDER_IT','阶段顺序验证')", id);
        jdbc.update("INSERT INTO t_source(id,source_code,knowledge_base_id,name) VALUES(?,'SRC_STAGE_ORDER_IT',?,'policy')", id, id);
        jdbc.update("INSERT INTO t_source_version(id,source_id,version_no) VALUES(?,?,1)", id, id);
        jdbc.update("INSERT INTO t_document(id,source_version_id,document_code,extraction_summary_json) VALUES(?,?,?,'{\"runId\":\"run-ordered\"}')",
            id, id, "DOC_STAGE_ORDER_IT");

        ExtractionPostStage graph = postStage(id, "run-ordered", "GRAPH_INDEX", "EXTRACT_POST:run-ordered:GRAPH_INDEX");
        ExtractionPostStage conflict = postStage(id, "run-ordered", "CONFLICT_DETECTION", "EXTRACT_POST:run-ordered:CONFLICT_DETECTION");
        extractionStages.insertStage(graph);
        extractionStages.insertStage(conflict);

        java.util.List<ExtractionPostStage> due = extractionStages.selectDueStages(10);
        assertEquals(1, due.size());
        assertEquals(graph.getId(), due.get(0).getId());
        assertEquals(1, extractionStages.claimStage(graph.getId(), "ordered-worker"));
        assertEquals(1, extractionStages.markStageSucceeded(graph.getId()));
        due = extractionStages.selectDueStages(10);
        assertEquals(1, due.size());
        assertEquals(conflict.getId(), due.get(0).getId());
    }

    @Test
    @Transactional
    void outboxDoesNotReleaseNewerAggregateVersionBeforeOlderEventCompletes() {
        long kb = 910031L;
        String aggregate = "KB:" + kb + ":CARD:VERSION_IT";
        jdbc.update("INSERT INTO t_index_outbox(event_id,idempotency_key,knowledge_base_id,aggregate_type,aggregate_key,event_type,"
            + "object_version,target_path,payload_json,status) VALUES"
            + "('EV_VERSION_1','IDEM_VERSION_1',?,'CARD',?,'UPSERT',1,'/test','{}','PENDING'),"
            + "('EV_VERSION_2','IDEM_VERSION_2',?,'CARD',?,'UPSERT',2,'/test','{}','PENDING')", kb, aggregate, kb, aggregate);

        java.util.List<com.jh.knowledgeos.domain.IndexOutboxEvent> due = indexMapper.selectDueIndexOutbox(10);
        assertEquals(1, due.size());
        assertEquals("EV_VERSION_1", due.get(0).getEventId());
        assertEquals(1, indexMapper.claimIndexOutbox(due.get(0).getId(), "version-test"));
        indexMapper.markIndexOutboxSucceeded(due.get(0).getId(), "{}");
        due = indexMapper.selectDueIndexOutbox(10);
        assertEquals(1, due.size());
        assertEquals("EV_VERSION_2", due.get(0).getEventId());
    }

    private ExtractionPostStage postStage(long documentId, String runId, String code, String key) {
        ExtractionPostStage stage = new ExtractionPostStage();
        stage.setStageCode(code);
        stage.setDocumentId(documentId);
        stage.setKnowledgeBaseId(documentId);
        stage.setRunId(runId);
        stage.setStatus("PENDING");
        stage.setMaxAttempts(2);
        stage.setIdempotencyKey(key);
        stage.setPayloadJson("{}");
        return stage;
    }

    @Test
    void mapperCanPersistAndReadStructuredQueryTrace() {
        QueryTraceRecord trace = new QueryTraceRecord();
        trace.setQueryId("Q_IT_001");
        trace.setKnowledgeBaseId(1L);
        trace.setActorUserId(9L);
        trace.setTraceId("TRACE_IT_001");
        trace.setOriginalQuery("integration test");
        trace.setRewrittenQueriesJson("[\"integration test\"]");
        trace.setRouterDecisionJson("{\"questionType\":\"FACT\"}");
        trace.setRetrievalJson("{}");
        trace.setFusionJson("{\"algorithm\":\"weighted_rrf\"}");
        trace.setRerankJson("{\"name\":\"test\"}");
        trace.setSelectedContextJson("[]");
        trace.setCitationsJson("[]");
        trace.setModelName("none");
        trace.setLatencyMs(12L);
        trace.setTokenUsageJson("{}");
        trace.setErrorCode(null);
        trace.setTraceJson("{\"totalLatencyMs\":12}");
        trace.setAnswer("ok");
        assertEquals(1, traceMapper.insertTrace(trace));
        Map<String, Object> stored = traceMapper.getTrace("Q_IT_001");
        assertNotNull(stored);
        assertEquals("TRACE_IT_001", String.valueOf(stored.get("traceId")));
        assertEquals(9L, ((Number) stored.get("actorUserId")).longValue());
        assertEquals("none", String.valueOf(stored.get("modelName")));
    }

    private boolean tableExists(Statement st, String table) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + table + "'")) {
            rs.next();
            return rs.getInt(1) == 1;
        }
    }

    private boolean indexExists(Statement st, String table, String index) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics " + "WHERE table_schema=DATABASE() AND table_name='" + table +
            "' AND index_name='" + index + "'";
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private boolean columnExists(Statement st, String table, String column) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.columns " + "WHERE table_schema=DATABASE() AND table_name='" + table +
            "' AND column_name='" + column + "'";
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1) == 1;
        }
    }
}
