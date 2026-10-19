-- Agent memory is a separate, scoped data domain. Sharing is explicit through API-key namespace.
ALTER TABLE t_api_key
    ADD COLUMN memory_namespace VARCHAR(64) NULL AFTER allowed_origins,
    ADD COLUMN memory_agent_code VARCHAR(64) NULL AFTER memory_namespace;

CREATE TABLE t_agent_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    memory_code VARCHAR(64) NOT NULL,
    namespace_code VARCHAR(64) NOT NULL,
    agent_code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    scope_code VARCHAR(128) NOT NULL,
    memory_level VARCHAR(8) NOT NULL,
    content TEXT NOT NULL,
    tags_json JSON NULL,
    source_type VARCHAR(32) NULL,
    source_ref VARCHAR(128) NULL,
    idempotency_key VARCHAR(128) NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.7000,
    importance TINYINT NOT NULL DEFAULT 3,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at DATETIME NULL,
    created_by_key_id BIGINT NOT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_memory_code (memory_code),
    UNIQUE KEY uk_agent_memory_idempotency (namespace_code, agent_code, idempotency_key),
    KEY idx_agent_memory_scope (namespace_code, scope_type, scope_code, status, expires_at),
    KEY idx_agent_memory_agent (namespace_code, agent_code, scope_type, status, expires_at),
    KEY idx_agent_memory_expiry (status, expires_at),
    CONSTRAINT fk_agent_memory_key FOREIGN KEY (created_by_key_id) REFERENCES t_api_key(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
