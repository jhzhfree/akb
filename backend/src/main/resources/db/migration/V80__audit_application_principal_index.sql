-- 接入详情要按「这条接入」筛调用记录。一个接入对应一个应用主体，审计表里已记
-- application_principal_id，但表中只有 key_id 与 agent_principal_id 的索引——按接入查会全表扫。
-- 索引与查询条件同序，便于直接走 (application_principal_id, created_time) 倒序取最近 N 条。
CREATE INDEX idx_audit_app_principal ON t_api_call_audit(application_principal_id, created_time);
