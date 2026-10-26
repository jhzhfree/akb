-- Detect reuse of an idempotency key with a changed memory payload, including
-- metadata that affects trust, sharing, ranking, or retention.
ALTER TABLE t_agent_memory
    ADD COLUMN payload_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER idempotency_key;
