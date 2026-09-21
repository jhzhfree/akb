-- Knowledge derivation (design §4.5 / PRD §5.5: 衍生规则 / 候选衍生知识 / 运行记录).
--
-- Derived knowledge must stay distinguishable from knowledge that was written in a
-- source document: every candidate records what it was derived from, which rule and
-- algorithm version produced it, the graph path it walked and the evidence chain that
-- backs it. When a base assertion changes the candidate is not silently wrong, it is
-- marked STALE and re-derived.
CREATE TABLE IF NOT EXISTS t_derivation_rule(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  name VARCHAR(160) NOT NULL,
  derivation_type VARCHAR(24) NOT NULL DEFAULT 'GRAPH_PATH',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  schedule_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  cron_expr VARCHAR(64),
  next_fire_at DATETIME,
  last_fire_at DATETIME,
  source_scope_json JSON,
  definition_json JSON,
  algorithm_version VARCHAR(32) NOT NULL DEFAULT 'derive-v1',
  status VARCHAR(16) NOT NULL DEFAULT 'IDLE',
  last_error TEXT,
  created_by VARCHAR(64),
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_derivation_rule_code(rule_code),
  KEY idx_derivation_rule_kb(knowledge_base_id,enabled),
  KEY idx_derivation_rule_due(enabled,next_fire_at)
);

CREATE TABLE IF NOT EXISTS t_derived_candidate(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  candidate_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  rule_code VARCHAR(64) NOT NULL,
  derivation_type VARCHAR(24) NOT NULL,
  algorithm_version VARCHAR(32),
  title VARCHAR(500) NOT NULL,
  content TEXT,
  confidence DECIMAL(6,4) NOT NULL DEFAULT 0.5,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  base_assertion_codes JSON NOT NULL,
  graph_path JSON,
  evidence_json JSON,
  derived_from_json JSON,
  permission_tags JSON,
  authority_score DECIMAL(6,4),
  content_hash VARCHAR(64),
  stale_reason VARCHAR(200),
  created_by VARCHAR(64),
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_derived_candidate_code(candidate_code),
  KEY idx_derived_candidate_kb_status(knowledge_base_id,status,created_time),
  KEY idx_derived_candidate_rule(rule_code,content_hash)
);

CREATE TABLE IF NOT EXISTS t_derivation_run(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  run_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  rule_code VARCHAR(64) NOT NULL,
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
  scanned_count INT NOT NULL DEFAULT 0,
  candidate_count INT NOT NULL DEFAULT 0,
  stale_count INT NOT NULL DEFAULT 0,
  error_message TEXT,
  started_time DATETIME,
  finished_time DATETIME,
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_derivation_run_code(run_code),
  KEY idx_derivation_run_kb(knowledge_base_id,rule_code,started_time)
);
