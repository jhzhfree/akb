-- 调用日志补上调用方 IP：排查"谁在打这个接口"时，凭证只说到哪个 Key，
-- IP 才说到哪台机器/哪个出口——同一枚 Key 被复制到多处、或被非预期环境使用时，
-- 这是唯一能把两者区分开的字段。
-- 64 位足够放下 IPv6 的完整写法。
ALTER TABLE t_api_call_audit ADD COLUMN caller_ip VARCHAR(64) NULL;
