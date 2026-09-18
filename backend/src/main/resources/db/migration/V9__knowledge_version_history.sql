-- Immutable knowledge-object snapshots for audit, diff and supersession lineage.
CREATE TABLE IF NOT EXISTS t_knowledge_version(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT NOT NULL,
  object_type VARCHAR(16) NOT NULL,
  object_code VARCHAR(64) NOT NULL,
  version_no INT NOT NULL,
  title VARCHAR(500),
  summary TEXT,
  content LONGTEXT,
  status VARCHAR(32),
  confidence DECIMAL(6,4),
  change_type VARCHAR(32) NOT NULL,
  changed_by VARCHAR(128),
  supersedes_version_no INT,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_knowledge_version(object_type,object_code,version_no),
  KEY idx_knowledge_version_kb(knowledge_base_id,object_type,object_code,version_no)
);

-- Backfill one baseline snapshot so existing V1.1 knowledge immediately becomes traceable.
INSERT IGNORE INTO t_knowledge_version(
  knowledge_base_id,object_type,object_code,version_no,title,summary,content,status,confidence,change_type,changed_by
)
SELECT knowledge_base_id,'CARD',knowledge_code,1,title,summary,content,status,confidence,'BASELINE','V9_MIGRATION'
FROM t_knowledge_card;

INSERT IGNORE INTO t_knowledge_version(
  knowledge_base_id,object_type,object_code,version_no,title,summary,content,status,confidence,change_type,changed_by
)
SELECT knowledge_base_id,'CLAIM',claim_code,1,subject,predicate,object_value,status,confidence,'BASELINE','V9_MIGRATION'
FROM t_claim;
