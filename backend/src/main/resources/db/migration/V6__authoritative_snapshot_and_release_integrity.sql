ALTER TABLE t_index_schema_release
  ADD COLUMN snapshot_checksum VARCHAR(64) NULL,
  ADD COLUMN source_high_watermark BIGINT NULL,
  ADD COLUMN source_end_watermark BIGINT NULL,
  ADD COLUMN snapshot_count BIGINT NULL,
  ADD COLUMN integrity_state VARCHAR(32) NULL;

CREATE TABLE IF NOT EXISTS t_index_schema_snapshot_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_id VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  page_no INT NOT NULL,
  row_count INT NOT NULL,
  page_checksum VARCHAR(64) NOT NULL,
  first_object_key VARCHAR(255) NULL,
  last_object_key VARCHAR(255) NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_snapshot_release_page(release_id,page_no),
  KEY idx_snapshot_audit_kb_release(knowledge_base_id,release_id)
);
