/**
 * 只剩类型：简化版 Agent 接入直接走 /api/external/admin/agent-connections（见
 * SimpleAgentConnections.vue），复杂版那套 /workload/policies/** 的调用与 nonce 挑战式登记
 * 都已删除，这里不再提供请求封装。
 */
export interface WorkloadPolicy {
  simple_mode?: boolean;
  id: number;
  application_principal_id: number;
  issuer: string;
  audience: string;
  installation_id: string;
  workspace_id: string;
  platform_id: string | null;
  resource_origin: string | null;
  /** 简化接入只写 V2；V1（远程 JWKS + 安装级证明）已下线。 */
  proof_profile: "AGENT_KEY_RS256_V2";
  status: string;
}
export interface WorkloadAgent {
  id: number;
  policy_id: number;
  external_agent_id: string;
  runtime_agent_key: string;
  agent_principal_id: number;
  status: string;
}
export interface WorkloadAgentKey {
  id: number;
  kid: string;
  status: string;
  public_jwk: string;
  valid_until: string | null;
  created_time: string;
}
