CREATE TABLE t_agent_memory_governance_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    memory_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    namespace_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    agent_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    scope_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    scope_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    operation VARCHAR(16) NOT NULL,
    old_status VARCHAR(16) NULL,
    new_status VARCHAR(16) NULL,
    reason VARCHAR(500) NOT NULL,
    operator_id BIGINT NOT NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_memory_governance_namespace_time (namespace_code, created_time),
    KEY idx_memory_governance_code (memory_code, created_time),
    CONSTRAINT fk_memory_governance_operator FOREIGN KEY (operator_id) REFERENCES t_user(id),
    CONSTRAINT chk_memory_governance_operation CHECK (operation IN ('SUSPEND','RESTORE','FORGET'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
