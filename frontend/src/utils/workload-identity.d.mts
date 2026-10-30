import type { AgentPublicJwk } from "../api/workload";
export function parsePublicBundle(text: string): {
  publicJwk: AgentPublicJwk;
  externalAgentId: string;
  runtimeAgentKey: string;
  installationId: string;
  workspaceId: string;
};
export function parseRegistrationProof(text: string): string;
