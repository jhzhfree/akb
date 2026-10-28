-- PATs are user-owned credentials; service keys remain principal-owned API credentials.
ALTER TABLE t_api_key
  ADD COLUMN credential_type VARCHAR(16) NOT NULL DEFAULT 'SERVICE_KEY' AFTER key_prefix,
  ADD COLUMN owner_user_id BIGINT NULL AFTER principal_id,
  ADD KEY idx_api_key_owner_type(owner_user_id,credential_type,status),
  ADD CONSTRAINT fk_api_key_owner_user FOREIGN KEY (owner_user_id) REFERENCES t_user(id);

ALTER TABLE t_principal
  ADD COLUMN owner_user_id BIGINT NULL AFTER principal_type,
  ADD COLUMN external_instance_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER owner_user_id,
  ADD COLUMN external_subject_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER external_instance_id,
  ADD UNIQUE KEY uk_principal_owner_user(owner_user_id),
  ADD UNIQUE KEY uk_principal_external_identity(external_instance_id,external_subject_id),
  ADD CONSTRAINT fk_principal_owner_user FOREIGN KEY (owner_user_id) REFERENCES t_user(id);

CREATE TABLE t_personal_access_token_scope_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_key_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  old_scopes VARCHAR(255) NOT NULL,
  new_scopes VARCHAR(255) NOT NULL,
  operator_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pat_scope_audit_key(api_key_id,created_time),
  CONSTRAINT fk_pat_scope_audit_key FOREIGN KEY (api_key_id) REFERENCES t_api_key(id),
  CONSTRAINT fk_pat_scope_audit_owner FOREIGN KEY (owner_user_id) REFERENCES t_user(id),
  CONSTRAINT fk_pat_scope_audit_operator FOREIGN KEY (operator_id) REFERENCES t_user(id)
);

-- Explicit Octop-user to AKB USER-principal binding. Binding is administered and audited in AKB.
CREATE TABLE t_external_identity_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  provider_instance_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  principal_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by BIGINT NULL,
  updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  revoked_by BIGINT NULL,
  revoked_time TIMESTAMP NULL,
  reason VARCHAR(500) NULL,
  UNIQUE KEY uk_external_identity_binding(provider,provider_instance_id,external_user_id),
  KEY idx_external_identity_principal(principal_id,status),
  CONSTRAINT fk_external_identity_principal FOREIGN KEY (principal_id) REFERENCES t_principal(id)
);

CREATE TABLE t_external_identity_binding_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  binding_id BIGINT NOT NULL,
  provider VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  provider_instance_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  external_user_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  principal_id BIGINT NOT NULL,
  action VARCHAR(16) NOT NULL,
  operator_id BIGINT NULL,
  reason VARCHAR(500) NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_external_identity_audit_binding(binding_id,created_time)
);

-- Stable Memory identity and grants belong to the principal, not to a rotatable key.
CREATE TABLE t_principal_memory_profile (
  principal_id BIGINT PRIMARY KEY,
  namespace_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  agent_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_principal_memory_profile_principal FOREIGN KEY (principal_id) REFERENCES t_principal(id) ON DELETE CASCADE
);

CREATE TABLE t_principal_memory_scope_grant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  principal_id BIGINT NOT NULL,
  namespace_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  scope_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  scope_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  can_read TINYINT(1) NOT NULL DEFAULT 0,
  can_write TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_by_operator BIGINT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_principal_memory_scope(principal_id,namespace_code,scope_type,scope_code),
  KEY idx_principal_memory_scope_lookup(namespace_code,scope_type,scope_code,status,principal_id),
  CONSTRAINT fk_principal_memory_scope_principal FOREIGN KEY (principal_id) REFERENCES t_principal(id) ON DELETE CASCADE,
  CONSTRAINT fk_principal_memory_scope_operator FOREIGN KEY (created_by_operator) REFERENCES t_user(id),
  CONSTRAINT chk_principal_memory_scope_type CHECK (scope_type IN ('AGENT','TEAM','PROJECT','TASK','WORKFLOW','SESSION')),
  CONSTRAINT chk_principal_memory_scope_permission CHECK (can_read=1 OR can_write=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Backfill only unambiguous identities. Conflicting per-key namespaces remain on the legacy
-- columns for administrator review and must not be merged automatically.
INSERT INTO t_principal_memory_profile(principal_id,namespace_code,agent_code,created_by)
SELECT k.principal_id,
       COALESCE(MAX(k.memory_namespace),CONCAT('key:',MIN(k.id))),
       COALESCE(MAX(k.memory_agent_code),MAX(k.key_code)),
       MAX(k.created_by)
FROM t_api_key k
WHERE k.principal_id IS NOT NULL
GROUP BY k.principal_id
HAVING COUNT(DISTINCT COALESCE(k.memory_namespace,CONCAT('key:',k.id)))=1
   AND COUNT(DISTINCT COALESCE(k.memory_agent_code,k.key_code))=1;

-- Do not broaden old read-only/write-only grants while moving them. The operation-level flags
-- are retained; the new combined MEMORY_MANAGE scope gates access to this grant set.
INSERT INTO t_principal_memory_scope_grant(principal_id,namespace_code,scope_type,scope_code,can_read,can_write,
    status,created_by_operator,created_time,updated_time)
SELECT k.principal_id,s.namespace_code,s.scope_type,s.scope_code,MIN(s.can_read),MIN(s.can_write),'ACTIVE',
       MAX(s.created_by_operator),MIN(s.created_time),MAX(s.updated_time)
FROM t_agent_memory_scope_access s JOIN t_api_key k ON k.id=s.api_key_id
WHERE k.principal_id IS NOT NULL
GROUP BY k.principal_id,s.namespace_code,s.scope_type,s.scope_code
HAVING COUNT(DISTINCT s.api_key_id)=(SELECT COUNT(1) FROM t_api_key k2 WHERE k2.principal_id=k.principal_id)
   AND MIN(s.can_read)=MAX(s.can_read) AND MIN(s.can_write)=MAX(s.can_write);

INSERT IGNORE INTO t_principal_memory_scope_grant(principal_id,namespace_code,scope_type,scope_code,can_read,can_write,status,created_by_operator)
SELECT p.principal_id,p.namespace_code,'AGENT',p.agent_code,1,1,'ACTIVE',p.created_by
FROM t_principal_memory_profile p;

-- Short lived signed Octop assertions use a one-time jti. Expired rows are pruned at validation.
CREATE TABLE t_octop_assertion_jti (
  jti VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
  expires_at TIMESTAMP NOT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_octop_assertion_expiry(expires_at)
);
