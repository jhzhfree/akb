ALTER TABLE t_workload_policy
  MODIFY COLUMN jwks_uri VARCHAR(1000) NULL,
  ADD COLUMN proof_profile VARCHAR(32) NOT NULL DEFAULT 'INSTALLATION_RS256_V1',
  ADD COLUMN platform_id VARCHAR(128) NULL,
  ADD COLUMN resource_origin VARCHAR(255) NULL;

CREATE TABLE t_workload_agent_key (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  policy_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  kid VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  public_jwk JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  valid_from TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  valid_until TIMESTAMP NULL,
  created_by BIGINT NOT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_by BIGINT NULL,
  revoked_time TIMESTAMP NULL,
  reason VARCHAR(500) NULL,
  UNIQUE KEY uk_agent_key_policy_kid(policy_id,kid),
  FOREIGN KEY(policy_id) REFERENCES t_workload_policy(id),
  FOREIGN KEY(binding_id) REFERENCES t_workload_agent_binding(id)
);
CREATE TABLE t_workload_key_challenge (
  challenge_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  policy_id BIGINT NOT NULL,
  binding_id BIGINT NOT NULL,
  public_jwk JSON NOT NULL,
  jkt VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  nonce_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  created_by BIGINT NOT NULL,
  consumed_at TIMESTAMP NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_key_challenge_binding(binding_id,expires_at),
  FOREIGN KEY(policy_id) REFERENCES t_workload_policy(id),
  FOREIGN KEY(binding_id) REFERENCES t_workload_agent_binding(id)
);
CREATE TABLE t_workload_v2_jti (
  policy_id BIGINT NOT NULL,
  kid VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  jti CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  PRIMARY KEY(policy_id,kid,jti),
  KEY idx_workload_v2_expiry(expires_at)
);
ALTER TABLE t_api_call_audit
  ADD COLUMN proof_profile VARCHAR(32) NULL,
  ADD COLUMN signing_kid VARCHAR(43) NULL,
  ADD COLUMN agent_key_registration_id BIGINT NULL,
  ADD COLUMN workload_message_id VARCHAR(128) NULL,
  ADD COLUMN workload_platform_id VARCHAR(128) NULL;
