ALTER TABLE t_index_schema_release
  ADD COLUMN generation_id VARCHAR(64) NULL,
  ADD COLUMN acl_manifest_hash VARCHAR(64) NULL,
  ADD COLUMN acl_manifest_count BIGINT NULL,
  ADD KEY idx_schema_generation(knowledge_base_id,generation_id);

CREATE TABLE IF NOT EXISTS t_index_acl_manifest (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  generation_id VARCHAR(64) NOT NULL,
  manifest_hash VARCHAR(64) NOT NULL,
  object_count BIGINT NOT NULL,
  source_high_watermark BIGINT NOT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_acl_manifest_release(release_id),
  KEY idx_acl_manifest_generation(knowledge_base_id,generation_id)
);
