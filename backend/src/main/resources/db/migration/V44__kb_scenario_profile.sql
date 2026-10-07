-- Scenario profile per knowledge base.
--
-- Retrieval used to be one deployment-wide policy file, so an operations runbook and
-- a policy handbook in the same deployment were retrieved with the same strategy, and
-- chunking was the only part a knowledge base could tune. The scenario profile carries
-- the three decisions that actually differ per library — how documents are cut and
-- enriched (generation), what is derived from them (derivation), and how questions are
-- answered (query) — and is stored next to the knowledge base it describes.
--
-- profile_json is NULL until an operator edits it: an untouched library keeps following
-- its template, so a later improvement to the template reaches every library that never
-- asked for something special.
CREATE TABLE IF NOT EXISTS t_kb_scenario(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT NOT NULL,
  scenario VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
  profile_json MEDIUMTEXT,
  updated_by VARCHAR(64),
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_kb_scenario_kb(knowledge_base_id)
);
