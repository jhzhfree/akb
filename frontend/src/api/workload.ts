import axios from "axios";
import type { ApiEnvelope } from "../types";

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
  proof_profile: "INSTALLATION_RS256_V1" | "AGENT_KEY_RS256_V2";
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
export interface AgentPublicJwk {
  kty: "RSA";
  n: string;
  e: string;
  kid: string;
  alg: "RS256";
  use: "sig";
}
export interface WorkloadAgentKey {
  id: number;
  kid: string;
  status: string;
  public_jwk: string | AgentPublicJwk;
  valid_until: string | null;
  created_time: string;
}
export interface RegistrationChallenge {
  challengeId: string;
  nonce: string;
  policyId: string;
  bindingId: string;
  externalAgentId: string;
  jkt: string;
  purpose: string;
  audience: string;
  expiresAt: number;
}
const root = "/api/external/admin/workload";
async function unwrap<T>(
  request: Promise<{ data: ApiEnvelope<T> }>,
): Promise<T> {
  const { data } = await request;
  if (!data.success || data.data === undefined)
    throw new Error(data.message || "操作失败");
  return data.data;
}
export const workloadApi = {
  policies: () => unwrap<WorkloadPolicy[]>(axios.get(`${root}/policies`)),
  createPolicy: (body: Record<string, unknown>) =>
    unwrap<WorkloadPolicy>(axios.post(`${root}/policies`, body)),
  policyStatus: (id: number, status: string) =>
    unwrap(axios.put(`${root}/policies/${id}/status`, { status })),
  agents: (id: number) =>
    unwrap<WorkloadAgent[]>(axios.get(`${root}/policies/${id}/agents`)),
  bind: (id: number, body: Record<string, unknown>) =>
    unwrap<WorkloadAgent[]>(axios.post(`${root}/policies/${id}/agents`, body)),
  agentStatus: (p: number, b: number, status: string) =>
    unwrap(axios.put(`${root}/policies/${p}/agents/${b}/status`, { status })),
  keys: (p: number, b: number) =>
    unwrap<WorkloadAgentKey[]>(
      axios.get(`${root}/policies/${p}/agents/${b}/keys`),
    ),
  challenge: (p: number, b: number, publicJwk: AgentPublicJwk) =>
    unwrap<RegistrationChallenge>(
      axios.post(`${root}/policies/${p}/agents/${b}/keys/challenges`, {
        publicJwk,
      }),
    ),
  register: (
    p: number,
    b: number,
    challengeId: string,
    registrationProof: string,
  ) =>
    unwrap(
      axios.post(`${root}/policies/${p}/agents/${b}/keys/registrations`, {
        challengeId,
        registrationProof,
      }),
    ),
  keyStatus: (
    p: number,
    b: number,
    kid: string,
    status: string,
    validUntil?: string,
  ) =>
    unwrap(
      axios.put(
        `${root}/policies/${p}/agents/${b}/keys/${encodeURIComponent(kid)}/status`,
        { status, validUntil },
      ),
    ),
  issueKey: (id: number, body: Record<string, unknown>) =>
    unwrap<{ secret: string }>(axios.post(`${root}/policies/${id}/keys`, body)),
};
