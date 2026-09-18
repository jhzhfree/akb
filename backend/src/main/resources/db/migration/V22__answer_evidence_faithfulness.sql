CREATE TABLE IF NOT EXISTS t_answer_evidence_assessment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, query_id VARCHAR(128) NOT NULL, knowledge_base_id BIGINT NOT NULL,
  gate_status VARCHAR(24) NOT NULL, completeness_score DECIMAL(6,5) NOT NULL DEFAULT 0, faithfulness_score DECIMAL(6,5) NOT NULL DEFAULT 0,
  factual_claim_count INT NOT NULL DEFAULT 0, supported_claim_count INT NOT NULL DEFAULT 0, citation_count INT NOT NULL DEFAULT 0,
  unsupported_claims_json JSON NULL, verification_json JSON NULL, created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_answer_evidence_query(query_id), KEY idx_answer_evidence_kb_time(knowledge_base_id,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
