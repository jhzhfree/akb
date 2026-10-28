-- 凭证的逻辑删除：吊销后的废凭证应该能从控制台列表里清掉，但不能物理删除。
--
-- t_agent_memory.created_by_key_id 与 t_pat_scope_audit.api_key_id 都是 NOT NULL 外键且没有
-- ON DELETE 级联：物理删一行 t_api_key 会直接撞外键约束，而这两类数据恰恰是要保留的——
-- 记忆是数据资产，scope 变更审计是治理凭据。所以改为标记删除，行留在库里，所有查询过滤掉。

ALTER TABLE t_api_key ADD COLUMN deleted_at TIMESTAMP NULL AFTER status;

-- 列表按「未删除 + 创建时间倒序」取，deleted_at 在前缀里能直接用上索引
ALTER TABLE t_api_key ADD INDEX idx_api_key_deleted(deleted_at, created_time);
