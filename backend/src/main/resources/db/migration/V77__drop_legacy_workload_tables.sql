-- 只保留简化版 Agent 接入后，两张表失去用途：
--   t_workload_assertion_jti —— V1 安装级证明的断言重放防护（V2 用 t_workload_v2_jti）
--   t_workload_key_challenge —— nonce 挑战式公钥登记（简化版直接登记公钥，无需挑战）
-- 注意：只删表，不动 t_workload_policy 上的 jwks_uri 等列——历史策略行仍在，只是不再写入新值。
DROP TABLE IF EXISTS t_workload_assertion_jti;
DROP TABLE IF EXISTS t_workload_key_challenge;
