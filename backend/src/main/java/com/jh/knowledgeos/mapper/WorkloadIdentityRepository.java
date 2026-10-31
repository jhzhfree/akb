package com.jh.knowledgeos.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class WorkloadIdentityRepository {
    private final JdbcTemplate jdbc;
    public WorkloadIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> policy(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM t_workload_policy WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public List<Map<String, Object>> policies() {
        return jdbc.queryForList("SELECT * FROM t_workload_policy ORDER BY id");
    }
    public long createPolicy(long application, String issuer, String jwks, String audience,
        String installation, String workspace, long operator) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO t_workload_policy(application_principal_id,issuer,jwks_uri,audience,installation_id,workspace_id,created_by) VALUES(?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, application); statement.setString(2, issuer); statement.setString(3, jwks);
            statement.setString(4, audience); statement.setString(5, installation); statement.setString(6, workspace);
            statement.setLong(7, operator); return statement;
        }, keys);
        return keys.getKey().longValue();
    }
    public List<Map<String, Object>> bindings(long policy) {
        return jdbc.queryForList("SELECT * FROM t_workload_agent_binding WHERE policy_id=? ORDER BY id", policy);
    }
    public Map<String, Object> binding(long policy, String external, String runtime) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT b.* FROM t_workload_agent_binding b JOIN t_principal p ON p.id=b.agent_principal_id WHERE b.policy_id=? AND b.external_agent_id=? AND b.runtime_agent_key=? AND b.status='ACTIVE' AND p.status='ACTIVE' AND p.principal_type='AGENT'",
            policy, external, runtime);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public void createBinding(long policy, String external, String runtime, long agent) {
        jdbc.update("INSERT INTO t_workload_agent_binding(policy_id,external_agent_id,runtime_agent_key,agent_principal_id) VALUES(?,?,?,?)", policy, external, runtime, agent);
    }
    public int bindingStatus(long policy, long binding, String status) {
        return jdbc.update("UPDATE t_workload_agent_binding SET status=? WHERE policy_id=? AND id=? AND (status<>'REVOKED' OR ?='REVOKED')", status, policy, binding, status);
    }
    public int policyStatus(long policy, String status) {
        return jdbc.update("UPDATE t_workload_policy SET status=? WHERE id=? AND (status<>'REVOKED' OR ?='REVOKED')", status, policy, status);
    }
    public void configureKey(long key, long policy) {
        jdbc.update("UPDATE t_api_key SET identity_mode='APPLICATION_WORKLOAD',workload_policy_id=? WHERE id=?", policy, key);
    }
    public void audit(long policy, String action, long operator, String detail) {
        jdbc.update("INSERT INTO t_workload_admin_audit(policy_id,action,operator_id,detail) VALUES(?,?,?,CAST(? AS JSON))", policy, action, operator, detail);
    }

    public void enableSimple(long policy) {
        jdbc.update("UPDATE t_workload_policy SET simple_mode=TRUE,authorization_mode='SERVICE_SCOPE' WHERE id=?", policy);
    }
    public int reviewAuthorization(long policy, String mode, String platform) {
        return jdbc.update("UPDATE t_workload_policy SET authorization_mode=?,platform_type=? WHERE id=? AND status<>'REVOKED' AND authorization_mode='REVIEW_REQUIRED'", mode, platform, policy);
    }
    public void platformType(long policy, String platform) {
        jdbc.update("UPDATE t_workload_policy SET platform_type=? WHERE id=?", platform, policy);
    }
    public void configureV2(long policy, String platform, String resource) {
        jdbc.update("UPDATE t_workload_policy SET proof_profile='AGENT_KEY_RS256_V2',authorization_mode='AGENT_INTERSECTION',platform_id=?,resource_origin=? WHERE id=?", platform, resource, policy);
    }
    public Map<String, Object> bindingById(long policy, long binding, boolean lock) {
        return first(jdbc.queryForList("SELECT * FROM t_workload_agent_binding WHERE policy_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), policy, binding));
    }
    public Map<String, Object> agentKey(long policy, long binding, String kid) {
        return first(jdbc.queryForList("SELECT * FROM t_workload_agent_key WHERE policy_id=? AND binding_id=? AND kid=?", policy, binding, kid));
    }
    public List<Map<String, Object>> agentKeys(long policy, long binding) {
        return jdbc.queryForList("SELECT * FROM t_workload_agent_key WHERE policy_id=? AND binding_id=? ORDER BY id", policy, binding);
    }
    public int activeKeyCount(long policy, long binding) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_workload_agent_key WHERE policy_id=? AND binding_id=? AND status<>'REVOKED' AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)", Integer.class, policy, binding);
    }
    public Map<String, Object> registerKey(long policy, long binding, String kid, String jwk, long operator) {
        jdbc.update("INSERT INTO t_workload_agent_key(policy_id,binding_id,kid,public_jwk,created_by) VALUES(?,?,?,CAST(? AS JSON),?)", policy, binding, kid, jwk, operator);
        return agentKey(policy, binding, kid);
    }
    public int keyStatus(long policy, long binding, String kid, String status, Instant until, long operator, String reason) {
        return jdbc.update("UPDATE t_workload_agent_key SET status=?,valid_until=?,revoked_by=?,revoked_time=?,reason=? WHERE policy_id=? AND binding_id=? AND kid=? AND status<>'REVOKED' AND (status='ACTIVE' OR ?='REVOKED')",
            status, until == null ? null : Timestamp.from(until), "REVOKED".equals(status) ? operator : null,
            "REVOKED".equals(status) ? Timestamp.from(Instant.now()) : null, reason, policy, binding, kid, status);
    }
    public void consumeV2(long policy, String kid, String jti, Instant expires) {
        jdbc.update("INSERT INTO t_workload_v2_jti(policy_id,kid,jti,expires_at) VALUES(?,?,?,?)", policy, kid, jti, Timestamp.from(expires.plusSeconds(120)));
    }
    public void cleanV2() {
        jdbc.update("DELETE FROM t_workload_v2_jti WHERE expires_at<CURRENT_TIMESTAMP LIMIT 1000");
    }
    private static Map<String, Object> first(List<Map<String, Object>> rows) { return rows.isEmpty() ? null : rows.get(0); }
}
