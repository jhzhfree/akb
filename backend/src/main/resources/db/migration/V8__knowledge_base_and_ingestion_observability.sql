ALTER TABLE t_knowledge_base
  ADD COLUMN description VARCHAR(1000) NULL AFTER name;

CREATE TABLE IF NOT EXISTS t_ingestion_run(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_code VARCHAR(64) NOT NULL UNIQUE,
  knowledge_base_id BIGINT NOT NULL,
  source_name VARCHAR(500) NOT NULL,
  status VARCHAR(32) NOT NULL,
  current_stage VARCHAR(64) NOT NULL,
  chunk_count INT NOT NULL DEFAULT 0,
  card_count INT NOT NULL DEFAULT 0,
  claim_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(2000) NULL,
  started_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_time DATETIME NULL,
  INDEX idx_ingestion_run_kb_time(knowledge_base_id,started_time),
  INDEX idx_ingestion_run_status(status)
);

CREATE TABLE IF NOT EXISTS t_ingestion_event(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  stage VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  message VARCHAR(1000) NULL,
  detail_json TEXT NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ingestion_event_run_time(run_id,created_time)
);
