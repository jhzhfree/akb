-- Delegated employee identity: explicit IdP subject links and separate USER principals.
ALTER TABLE t_principal
  ADD COLUMN principal_type VARCHAR(16) NOT NULL DEFAULT 'APPLICATION' AFTER principal_code;

CREATE TABLE t_principal_identity_link (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  principal_id BIGINT NOT NULL,
  issuer VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_by BIGINT NULL,
  revoked_time TIMESTAMP NULL,
  UNIQUE KEY uk_principal_identity_issuer_subject(issuer,subject),
  KEY idx_principal_identity_principal(principal_id,status),
  CONSTRAINT fk_principal_identity_principal FOREIGN KEY (principal_id) REFERENCES t_principal(id)
);

CREATE TABLE t_principal_identity_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  identity_link_id BIGINT NOT NULL,
  principal_id BIGINT NOT NULL,
  action VARCHAR(16) NOT NULL,
  operator_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_principal_identity_audit_link(identity_link_id,created_time),
  KEY idx_principal_identity_audit_principal(principal_id,created_time)
);
