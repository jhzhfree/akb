ALTER TABLE t_api_key
    ADD COLUMN memory_read_enabled TINYINT(1) NULL AFTER memory_agent_code,
    ADD COLUMN memory_write_enabled TINYINT(1) NULL AFTER memory_read_enabled;

-- NULL preserves the existing behavior: a key's MEMORY_* scope remains authoritative until
-- an operator explicitly changes its per-key policy in the integration console.
