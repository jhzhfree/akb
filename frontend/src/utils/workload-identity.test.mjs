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
