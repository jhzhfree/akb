-- V1.1 production indexing: transactional outbox, monotonic aggregate versions and delivery audit.
CREATE TABLE IF NOT EXISTS t_index_aggregate_version(
  aggregate_key VARCHAR(220) PRIMARY KEY,
  current_version BIGINT NOT NULL DEFAULT 0,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_index_outbox(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  aggregate_type VARCHAR(48) NOT NULL,
  aggregate_key VARCHAR(220) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  object_version BIGINT NOT NULL,
  target_path VARCHAR(255) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 8,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  locked_by VARCHAR(128) NULL,
  locked_at DATETIME NULL,
  last_error TEXT NULL,
  result_json JSON NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  processed_time DATETIME NULL,
  UNIQUE KEY uk_index_outbox_event(event_id),
  UNIQUE KEY uk_index_outbox_idempotency(idempotency_key),
  KEY idx_index_outbox_due(status,next_attempt_at,id),
  KEY idx_index_outbox_aggregate(aggregate_key,object_version,status),
  KEY idx_index_outbox_kb(knowledge_base_id,created_time)
);

CREATE TABLE IF NOT EXISTS t_index_delivery_attempt(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  attempt_no INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  duration_ms BIGINT NULL,
  response_json JSON NULL,
  error_text TEXT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_index_delivery_attempt(event_id,attempt_no),
  KEY idx_index_delivery_event(event_id,created_time)
);
