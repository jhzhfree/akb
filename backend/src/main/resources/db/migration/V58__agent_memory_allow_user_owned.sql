-- 记忆不再只由 API Key 产生：控制台用户对自己答案的纠正会存成他自己的记忆，
-- 那条记录没有 Key 可填。外键保留（有 Key 时仍然校验），只是允许为空。
ALTER TABLE t_agent_memory MODIFY created_by_key_id BIGINT NULL;
