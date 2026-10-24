-- Durable checkpoints for extraction work that happens after cards and claims commit.
CREATE TABLE t_extraction_post_stage (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    stage_code VARCHAR(64) NOT NULL,
    document_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    manual_replay_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 8,
    idempotency_key VARCHAR(160) NOT NULL,
    payload_json JSON NOT NULL,
    last_error VARCHAR(1000),
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(128),
    locked_at DATETIME,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_extraction_stage_key (idempotency_key),
    UNIQUE KEY uk_extraction_stage_run (document_id, run_id, stage_code),
    KEY idx_extraction_stage_due (status, next_attempt_at, id),
    KEY idx_extraction_stage_document (document_id, run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable retries for post-extraction stages';
