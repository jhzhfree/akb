CREATE TABLE IF NOT EXISTS t_acl_rebuild_job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_code VARCHAR(64) NOT NULL,
  change_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  schema_version VARCHAR(40) NOT NULL,
  release_id VARCHAR(64),
  state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  qualification_state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  qualification_json JSON,
  error_text VARCHAR(1000),
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_time TIMESTAMP NULL,
  UNIQUE KEY uk_acl_rebuild_job_code(job_code),
  UNIQUE KEY uk_acl_rebuild_change(change_code),
  KEY idx_acl_rebuild_kb_state(knowledge_base_id,state,created_time)
);
