-- Claim model completion (design V1.1 §6 / §9.2) and entity-resolution storage (§5.4).
-- The claim columns carry polarity, qualifiers, certainty, validity window and version so that
-- conflict detection can distinguish real contradictions from temporally separated statements
-- (design §17 risk table).
ALTER TABLE t_claim ADD COLUMN polarity VARCHAR(16) NOT NULL DEFAULT 'POSITIVE';
ALTER TABLE t_claim ADD COLUMN certainty DECIMAL(6,4) NULL;
ALTER TABLE t_claim ADD COLUMN qualifier_json JSON NULL;
ALTER TABLE t_claim ADD COLUMN valid_from DATETIME NULL;
ALTER TABLE t_claim ADD COLUMN valid_to DATETIME NULL;
ALTER TABLE t_claim ADD COLUMN version INT NOT NULL DEFAULT 1;
CREATE INDEX idx_claim_validity ON t_claim(knowledge_base_id,valid_from,valid_to);
CREATE INDEX idx_claim_subject ON t_claim(knowledge_base_id,subject(191),status);

-- Surface forms linked to their canonical entity. Produced by Entity Resolution
-- (alias dictionary + normalized matching + embedding similarity, design §5.4).
CREATE TABLE IF NOT EXISTS t_entity_alias(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT NOT NULL,
  entity_code VARCHAR(64) NOT NULL,
  alias VARCHAR(255) NOT NULL,
  alias_type VARCHAR(32) NOT NULL DEFAULT 'SURFACE_FORM',
  confidence DECIMAL(6,4) DEFAULT 0,
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_entity_alias(entity_code,alias),
  KEY idx_entity_alias_kb_entity(knowledge_base_id,entity_code)
);
