import type { AgentPublicJwk } from "../api/workload";
export function parsePublicBundle(text: string): {
  publicJwk: AgentPublicJwk;
  externalAgentId: string;
  runtimeAgentKey: string;
  installationId: string;
  workspaceId: string;
};
export function parseRegistrationProof(text: string): string;
export interface PlatformSetup {
  platformId: string;
  installationId: string;
  workspaceId: string;
  issuer: string;
  audience: string;
  resource: string;
}
export function validatePlatformSetup(
  value: PlatformSetup,
): Record<string, string>;
export function isLoopbackHost(host: string): boolean;
export function parsePlatformSetup(text: string): PlatformSetup;
