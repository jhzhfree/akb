-- Shared scopes are opt-in per credential; scope codes are identifiers, not authorization.
-- Binary comparisons prevent case variants from aliasing tenant, agent, or scope identities.
ALTER TABLE t_agent_memory
    MODIFY namespace_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY agent_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    MODIFY scope_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

CREATE TABLE t_agent_memory_scope_access (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    api_key_id BIGINT NOT NULL,
    namespace_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    scope_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    scope_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    can_read TINYINT(1) NOT NULL DEFAULT 0,
    can_write TINYINT(1) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by_operator BIGINT NOT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_memory_scope_grant (api_key_id, namespace_code, scope_type, scope_code),
    KEY idx_memory_scope_lookup (namespace_code, scope_type, scope_code, status, api_key_id),
    CONSTRAINT fk_memory_scope_access_key FOREIGN KEY (api_key_id) REFERENCES t_api_key(id) ON DELETE CASCADE,
    CONSTRAINT fk_memory_scope_access_operator FOREIGN KEY (created_by_operator) REFERENCES t_user(id),
    CONSTRAINT chk_memory_scope_access_type CHECK (scope_type IN ('TEAM','PROJECT','TASK','WORKFLOW','SESSION')),
    CONSTRAINT chk_memory_scope_access_permission CHECK (can_read=1 OR can_write=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
