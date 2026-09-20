-- Chunking policy per knowledge base.
--
-- Chunking used to be two environment constants inside the AI service, so a manual
-- and a design report in the same deployment were cut with the same window. The
-- policy now lives next to the data it describes: the backend reads it on upload and
-- forwards it to the parser, and every chunk records the strategy that produced it.
CREATE TABLE IF NOT EXISTS t_chunk_policy(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT NOT NULL,
  strategy VARCHAR(32) NOT NULL DEFAULT 'SEMANTIC',
  chunk_size INT NOT NULL DEFAULT 900,
  chunk_overlap INT NOT NULL DEFAULT 120,
  min_chunk_size INT NOT NULL DEFAULT 120,
  measure VARCHAR(16) NOT NULL DEFAULT 'CHAR',
  keep_structure TINYINT(1) NOT NULL DEFAULT 1,
  respect_sentence TINYINT(1) NOT NULL DEFAULT 1,
  parent_child TINYINT(1) NOT NULL DEFAULT 0,
  updated_by VARCHAR(64),
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_chunk_policy_kb(knowledge_base_id)
);
