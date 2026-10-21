-- 检索链路记录补上调用方 IP：调用日志（t_api_call_audit）已经记了 IP，
-- 但"检索链路追踪"页看的是问答 trace 自己的表，两边不一致时同一个问题会出现
-- "调用日志有来源、链路记录查不出是谁问的"。
ALTER TABLE t_query_trace ADD COLUMN caller_ip VARCHAR(64) NULL;
