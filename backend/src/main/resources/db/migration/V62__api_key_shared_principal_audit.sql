-- Record credential reassignment without deleting the principals that retain shared grants.
CREATE TABLE IF NOT EXISTS t_api_key_principal_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_key_id BIGINT NOT NULL,
  key_code VARCHAR(64) NOT NULL,
  old_principal_id BIGINT NULL,
  new_principal_id BIGINT NOT NULL,
  operator_id BIGINT NULL,
  reason VARCHAR(500) NOT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_api_key_principal_audit_key(api_key_id,created_time),
  KEY idx_api_key_principal_audit_principal(new_principal_id,created_time)
);
