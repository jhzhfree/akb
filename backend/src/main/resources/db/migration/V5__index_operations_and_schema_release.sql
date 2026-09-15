-- Operational retention and blue/green index schema release metadata.
CREATE TABLE IF NOT EXISTS t_index_outbox_archive (
  id BIGINT PRIMARY KEY, event_id VARCHAR(64) NOT NULL, idempotency_key VARCHAR(255) NOT NULL,
  knowledge_base_id BIGINT NOT NULL, aggregate_type VARCHAR(32) NOT NULL, aggregate_key VARCHAR(255) NOT NULL,
  event_type VARCHAR(64) NOT NULL, object_version BIGINT NOT NULL, target_path VARCHAR(255) NOT NULL,
  payload_json JSON NULL, status VARCHAR(32) NOT NULL, attempt_count INT NOT NULL, max_attempts INT NOT NULL,
  result_json JSON NULL, last_error TEXT NULL, created_time DATETIME NOT NULL, processed_time DATETIME NULL,
  archived_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_outbox_archive_event(event_id), KEY idx_outbox_archive_kb_time(knowledge_base_id,archived_time),
  KEY idx_outbox_archive_aggregate(aggregate_key,object_version)
);

CREATE TABLE IF NOT EXISTS t_index_delivery_attempt_archive (
  id BIGINT PRIMARY KEY, event_id VARCHAR(64) NOT NULL, attempt_no INT NOT NULL, status VARCHAR(32) NOT NULL,
  duration_ms BIGINT NULL, response_json JSON NULL, error_text TEXT NULL, created_time DATETIME NOT NULL,
  archived_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_delivery_attempt_archive(event_id,attempt_no), KEY idx_delivery_attempt_archive_event(event_id,created_time)
);

CREATE TABLE IF NOT EXISTS t_index_schema_release (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, release_id VARCHAR(64) NOT NULL, knowledge_base_id BIGINT NOT NULL,
  schema_version VARCHAR(64) NOT NULL, state VARCHAR(32) NOT NULL,
  opensearch_target VARCHAR(128) NULL, qdrant_target VARCHAR(128) NULL,
  expected_count BIGINT NULL, opensearch_count BIGINT NULL, qdrant_count BIGINT NULL,
  validation_json JSON NULL, error_text TEXT NULL, created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  activated_time DATETIME NULL, retired_time DATETIME NULL,
  UNIQUE KEY uk_schema_release(release_id),
  KEY idx_schema_release_kb_state(knowledge_base_id,state,created_time),
  KEY idx_schema_release_version(knowledge_base_id,schema_version)
);
