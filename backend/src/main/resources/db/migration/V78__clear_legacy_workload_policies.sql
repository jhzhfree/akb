-- 复杂版下线后，库里遗留的策略分两类，处理方式不同：
--
-- 1) proof_profile <> AGENT_KEY_RS256_V2（即已下线的 V1）：请求侧只认 V2，这些策略无论如何
--    认证不了，连同它们的 Agent 绑定、公钥与重放记录一起清掉。
-- 2) 仍是 V2 但 simple_mode=FALSE 的：认证还在正常工作，删掉会当场中断正在运行的接入。所以
--    不删，只标记为简化接入，让它们出现在新的控制台里——否则它们既看不到也管不了。
--
-- 共享服务 Key 不删：t_api_key 被记忆与审计表外键引用，且并非全部级联，直接删会撞约束。置空
-- 策略绑定后，请求侧因 workload_policy_id 为空而直接判定未认证，这些 Key 也就不能再用于身份
-- 自证；管理员可在控制台作废它们。管理审计留档，不随策略删除。
UPDATE t_api_key SET workload_policy_id = NULL
WHERE workload_policy_id IN (SELECT id FROM t_workload_policy WHERE proof_profile <> 'AGENT_KEY_RS256_V2');

DELETE FROM t_workload_v2_jti
WHERE policy_id IN (SELECT id FROM t_workload_policy WHERE proof_profile <> 'AGENT_KEY_RS256_V2');

DELETE FROM t_workload_agent_key
WHERE policy_id IN (SELECT id FROM t_workload_policy WHERE proof_profile <> 'AGENT_KEY_RS256_V2');

DELETE FROM t_workload_agent_binding
WHERE policy_id IN (SELECT id FROM t_workload_policy WHERE proof_profile <> 'AGENT_KEY_RS256_V2');

DELETE FROM t_workload_policy WHERE proof_profile <> 'AGENT_KEY_RS256_V2';

UPDATE t_workload_policy SET simple_mode = TRUE WHERE simple_mode = FALSE;
