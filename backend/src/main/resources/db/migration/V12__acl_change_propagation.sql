CREATE TABLE IF NOT EXISTS t_acl_change (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  change_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT,
  resource_type VARCHAR(64) NOT NULL,
  resource_code VARCHAR(255) NOT NULL,
  old_permission_tags JSON,
  new_permission_tags JSON,
  changed_by VARCHAR(128),
  propagation_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  impact_json JSON,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_time TIMESTAMP NULL,
  UNIQUE KEY uk_acl_change_code(change_code),
  KEY idx_acl_change_kb_state(knowledge_base_id,propagation_state,created_time)
);
ALTER TABLE t_acl_access_audit
  ADD COLUMN trace_id VARCHAR(128) NULL,
  ADD COLUMN request_uri VARCHAR(500) NULL,
  ADD KEY idx_acl_audit_trace(trace_id);
