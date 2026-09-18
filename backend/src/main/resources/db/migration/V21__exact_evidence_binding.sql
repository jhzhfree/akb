CREATE TABLE IF NOT EXISTS t_exact_evidence_binding (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, knowledge_base_id BIGINT NOT NULL, object_type VARCHAR(32) NOT NULL,
  object_code VARCHAR(128) NOT NULL, facet_code VARCHAR(256) NULL, evidence_id BIGINT NOT NULL, evidence_code VARCHAR(128) NOT NULL,
  evidence_hash VARCHAR(64) NOT NULL, extraction_hash VARCHAR(64) NOT NULL, binding_mode VARCHAR(16) NOT NULL DEFAULT 'EXACT',
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_exact_binding(object_type,object_code,facet_code,evidence_id), KEY idx_exact_binding_kb(knowledge_base_id,object_type,object_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
