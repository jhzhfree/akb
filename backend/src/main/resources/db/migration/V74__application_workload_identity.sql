CREATE TABLE t_workload_policy (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  application_principal_id BIGINT NOT NULL,
  issuer VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  jwks_uri VARCHAR(1000) NOT NULL,
  audience VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  installation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  workspace_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NOT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (application_principal_id) REFERENCES t_principal(id)
);
CREATE TABLE t_workload_agent_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  policy_id BIGINT NOT NULL,
  external_agent_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  runtime_agent_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  agent_principal_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE KEY uk_workload_external(policy_id, external_agent_id),
  UNIQUE KEY uk_workload_runtime(policy_id, runtime_agent_key),
  FOREIGN KEY (policy_id) REFERENCES t_workload_policy(id),
  FOREIGN KEY (agent_principal_id) REFERENCES t_principal(id)
);
CREATE TABLE t_workload_assertion_jti (
  issuer_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  jti VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME NOT NULL,
  PRIMARY KEY(issuer_hash,jti),
  KEY idx_workload_jti_expiry(expires_at)
);
CREATE TABLE t_workload_admin_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  policy_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  operator_id BIGINT NOT NULL,
  detail JSON NOT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE t_api_key
  ADD COLUMN identity_mode VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN workload_policy_id BIGINT NULL,
  ADD CONSTRAINT fk_key_workload_policy FOREIGN KEY(workload_policy_id) REFERENCES t_workload_policy(id);
ALTER TABLE t_api_call_audit
  ADD COLUMN application_principal_id BIGINT NULL,
  ADD COLUMN agent_principal_id BIGINT NULL,
  ADD COLUMN workload_session_id VARCHAR(128) NULL,
  ADD COLUMN workload_invocation_id VARCHAR(128) NULL,
  ADD COLUMN workload_identity_mode VARCHAR(32) NULL,
  ADD KEY idx_audit_agent(agent_principal_id,created_time);
