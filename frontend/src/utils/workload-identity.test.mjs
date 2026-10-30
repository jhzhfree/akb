import { test } from "node:test";
import assert from "node:assert/strict";
import {
  parsePublicBundle,
  parseRegistrationProof,
} from "./workload-identity.mjs";
const publicJwk = {
  kty: "RSA",
  n: "a".repeat(512),
  e: "AQAB",
  kid: "b".repeat(43),
  alg: "RS256",
  use: "sig",
};
test("registration import keeps public identity and rejects private material", () => {
  const bundle = {
    externalAgentId: "e21cce9c-1b16-4ab8-a76a-bb761df919b8",
    runtimeAgentKey: "builder",
    publicJwk,
  };
  assert.equal(
    parsePublicBundle(JSON.stringify(bundle)).runtimeAgentKey,
    "builder",
  );
  assert.throws(() =>
    parsePublicBundle(
      JSON.stringify({ ...bundle, publicJwk: { ...publicJwk, d: "private" } }),
    ),
  );
  assert.throws(() =>
    parsePublicBundle(JSON.stringify({ ...bundle, secret: "credential" })),
  );
  assert.throws(() => parsePublicBundle("x".repeat(16385)));
});
test("registration proof accepts the dedicated response envelope and rejects malformed input", () => {
  assert.equal(
    parseRegistrationProof('{"registrationProof":"a.b.c"}'),
    "a.b.c",
  );
  assert.throws(() => parseRegistrationProof("private key"));
});

test("platform setup package accepts public metadata and rejects credentials or invalid origins", async () => {
  const { parsePlatformSetup, validatePlatformSetup } =
    await import("./workload-identity.mjs");
  const setup = {
    format: "akb-platform-setup/v1",
    proofProfile: "AGENT_KEY_RS256_V2",
    platformId: "octop",
    installationId: "install",
    workspaceId: "workspace",
    issuer: "https://octop.local/agents",
    audience: "akb-external-api",
    resource: "https://akb.example.com",
  };
  assert.equal(
    parsePlatformSetup(JSON.stringify(setup)).installationId,
    "install",
  );
  for (const bad of [
    { ...setup, serviceKey: "secret" },
    { ...setup, resource: "https://akb.example.com/api/mcp" },
    { ...setup, installationId: "my agent name" },
    { ...setup, issuer: "http://identity.example" },
  ])
    assert.throws(() => parsePlatformSetup(JSON.stringify(bad)));
  assert.equal(
    Object.keys(
      validatePlatformSetup({ ...setup, resource: "http://127.0.0.1:8080" }),
    ).length,
    0,
  );
});
