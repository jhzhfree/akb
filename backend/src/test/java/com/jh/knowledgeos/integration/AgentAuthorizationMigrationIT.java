package com.jh.knowledgeos.integration;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercise an upgrade through V78, whose old authorization flag cannot be recovered. */
@Testcontainers
class AgentAuthorizationMigrationIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
        .withDatabaseName("identity_upgrade").withUsername("knowledge").withPassword("knowledge123");

    @Test void historicalConnectionsCannotGainAuthorityDuringUpgrade() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).target("77").load().migrate();
        try (Connection c=MYSQL.createConnection(""); Statement s=c.createStatement()) {
            s.executeUpdate("INSERT INTO t_principal(id,principal_code,name,principal_type) VALUES(9001,'upgrade-app','Upgrade app','APPLICATION')");
            s.executeUpdate("INSERT INTO t_workload_policy(id,application_principal_id,issuer,audience,installation_id,workspace_id,proof_profile,platform_id,resource_origin,created_by,simple_mode) VALUES(9001,9001,'https://issuer.example','akb','install','workspace','AGENT_KEY_RS256_V2','octop','https://akb.example',1,FALSE)");
            s.executeUpdate("INSERT INTO t_workload_policy(id,application_principal_id,issuer,audience,installation_id,workspace_id,proof_profile,platform_id,resource_origin,created_by,status,simple_mode) VALUES(9002,9001,'https://issuer.example','akb','install2','workspace2','AGENT_KEY_RS256_V2','octop','https://akb.example',1,'REVOKED',TRUE)");
        }
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();
        try (Connection c=MYSQL.createConnection(""); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT id,status,authorization_mode FROM t_workload_policy WHERE id IN (9001,9002) ORDER BY id")) {
            assertTrue(r.next()); assertEquals("SUSPENDED",r.getString("status")); assertEquals("REVIEW_REQUIRED",r.getString("authorization_mode"));
            assertTrue(r.next()); assertEquals("REVOKED",r.getString("status")); assertEquals("REVIEW_REQUIRED",r.getString("authorization_mode"));
        }
        // Re-running migrations must not change an administrator's reviewed authority.
        try (Connection c=MYSQL.createConnection(""); Statement s=c.createStatement()) {
            s.executeUpdate("UPDATE t_workload_policy SET authorization_mode='AGENT_INTERSECTION' WHERE id=9001");
        }
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()).load().migrate();
        try (Connection c=MYSQL.createConnection(""); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT authorization_mode FROM t_workload_policy WHERE id=9001")) {
            assertTrue(r.next());assertEquals("AGENT_INTERSECTION",r.getString(1));
        }
    }
}
