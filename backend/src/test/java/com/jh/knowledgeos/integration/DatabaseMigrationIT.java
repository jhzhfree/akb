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
        registry.add("knowledge.ai-connect-timeout-ms", () -> "100");
        registry.add("knowledge.ai-read-timeout-ms", () -> "1000");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TraceMapper traceMapper;

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
            assertTrue(columnExists(st, "t_api_key", "principal_id"));
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
