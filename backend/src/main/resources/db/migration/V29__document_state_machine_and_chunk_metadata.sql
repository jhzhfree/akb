-- V29: document state machine (design §6.2 / §16.1) and chunk metadata (design §7.2).
--
-- The document previously only carried `parse_status ∈ {PARSING, PARSED}`, so a failed ingestion
-- kept claiming it was being parsed, nothing could tell a parse failure from a dirty index, and
-- there was no place to record which stage died or how many times it was retried. The design
-- requires the full chain UPLOADED → PROCESSING → READY / FAILED → RETRYING → PROCESSING and
-- READY → DIRTY → REINDEXING → READY, plus failure context (stage, error, retryCount).
ALTER TABLE t_document ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED' AFTER parse_status;
ALTER TABLE t_document ADD COLUMN current_stage VARCHAR(64) NULL AFTER status;
ALTER TABLE t_document ADD COLUMN error_message VARCHAR(1000) NULL AFTER current_stage;
ALTER TABLE t_document ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER error_message;
ALTER TABLE t_document ADD COLUMN updated_time DATETIME NULL AFTER retry_count;

-- Existing rows keep working: a parsed document is READY, one still being parsed is PROCESSING.
UPDATE t_document
SET status = CASE
        WHEN parse_status = 'PARSED' THEN 'READY'
        WHEN parse_status = 'PARSING' THEN 'PROCESSING'
        ELSE 'UPLOADED'
    END,
    current_stage = CASE WHEN parse_status = 'PARSED' THEN 'INDEXING' ELSE 'PARSE' END,
    updated_time = created_time;

CREATE INDEX idx_document_status ON t_document(status, updated_time);

-- ContentUnit is the physical chunk store, but it only ever recorded heading path, page and text.
-- The design's required chunk metadata adds the chunking strategy, the character/page span, token
-- count, lifecycle status, its own ACL tags, the source version it came from and a revision, so a
-- chunk edit can produce a new revision instead of silently overwriting content that Evidence
-- already points at, and so the index can be marked DIRTY after an edit.
ALTER TABLE t_content_unit ADD COLUMN chunk_type VARCHAR(32) NOT NULL DEFAULT 'PARAGRAPH' AFTER heading_path;
ALTER TABLE t_content_unit ADD COLUMN page_end INT NULL AFTER page_no;
ALTER TABLE t_content_unit ADD COLUMN char_start INT NULL AFTER page_end;
ALTER TABLE t_content_unit ADD COLUMN char_end INT NULL AFTER char_start;
ALTER TABLE t_content_unit ADD COLUMN token_count INT NULL AFTER char_end;
ALTER TABLE t_content_unit ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER token_count;
ALTER TABLE t_content_unit ADD COLUMN acl_tags JSON NULL AFTER status;
ALTER TABLE t_content_unit ADD COLUMN source_version INT NOT NULL DEFAULT 1 AFTER acl_tags;
ALTER TABLE t_content_unit ADD COLUMN revision INT NOT NULL DEFAULT 1 AFTER source_version;

CREATE INDEX idx_content_unit_type ON t_content_unit(document_id, chunk_type);
