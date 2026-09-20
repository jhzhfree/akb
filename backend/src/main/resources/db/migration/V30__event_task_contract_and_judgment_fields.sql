-- V30: async-task contract on the event bus (design §17.2) and complete judgment records (§10.2).
--
-- Every asynchronous task must be traceable by taskId / traceId / objectId / objectVersion /
-- idempotencyKey / retryCount. The bus already carried traceId and the knowledge base, but not the
-- task or object identity, so a subscriber could not tell which run of which object an event
-- belonged to, and an outbox retry was indistinguishable from a first attempt.
ALTER TABLE t_platform_event ADD COLUMN task_id VARCHAR(64) NULL AFTER trace_id;
ALTER TABLE t_platform_event ADD COLUMN object_id VARCHAR(64) NULL AFTER task_id;
ALTER TABLE t_platform_event ADD COLUMN object_version VARCHAR(32) NULL AFTER object_id;
ALTER TABLE t_platform_event ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER object_version;
ALTER TABLE t_platform_event ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER idempotency_key;

CREATE INDEX idx_platform_event_task ON t_platform_event(task_id);
CREATE INDEX idx_platform_event_object ON t_platform_event(object_id, object_version);

-- A judgment must record what was decided, why, by whom, when, under which policy, and which
-- assertions went in and which one came out; otherwise a later auditor cannot reconstruct why a
-- conflict ended the way it did. Human decisions previously stored only decision/reviewer/comment.
ALTER TABLE t_knowledge_review ADD COLUMN input_object_codes JSON NULL AFTER comment_text;
ALTER TABLE t_knowledge_review ADD COLUMN result_object_code VARCHAR(64) NULL AFTER input_object_codes;
ALTER TABLE t_knowledge_review ADD COLUMN policy_version VARCHAR(32) NULL AFTER result_object_code;
ALTER TABLE t_knowledge_review ADD COLUMN judgment_time DATETIME NULL AFTER policy_version;
