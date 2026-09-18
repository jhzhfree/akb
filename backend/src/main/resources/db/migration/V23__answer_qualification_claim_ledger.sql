-- Phase 17: versioned final-answer qualification and claim-level audit ledger.
CREATE TABLE IF NOT EXISTS t_answer_qualification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  query_id VARCHAR(128) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  policy_version VARCHAR(64) NOT NULL,
  gate_status VARCHAR(24) NOT NULL,
  evidence_gate_status VARCHAR(24) NOT NULL,
  structured_gate_status VARCHAR(24) NOT NULL,
  decision_reasons_json JSON NULL,
  original_answer_hash VARCHAR(64) NOT NULL,
  delivered_answer_hash VARCHAR(64) NOT NULL,
  claim_count INT NOT NULL DEFAULT 0,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_answer_qualification_query(query_id),
  KEY idx_answer_qualification_kb_time(knowledge_base_id,created_time),
  KEY idx_answer_qualification_status(gate_status,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS t_answer_claim_assessment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  query_id VARCHAR(128) NOT NULL,
  claim_id VARCHAR(64) NOT NULL,
  policy_version VARCHAR(64) NOT NULL,
  statement_text TEXT NOT NULL,
  decision VARCHAR(32) NOT NULL,
  confidence DECIMAL(6,5) NOT NULL DEFAULT 0,
  entailment_score DECIMAL(6,5) NOT NULL DEFAULT 0,
  citation_indexes_json JSON NULL,
  evidence_codes_json JSON NULL,
  evidence_statuses_json JSON NULL,
  included_in_final_answer TINYINT(1) NOT NULL DEFAULT 0,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_answer_claim_query(query_id,claim_id),
  KEY idx_answer_claim_decision(decision,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
