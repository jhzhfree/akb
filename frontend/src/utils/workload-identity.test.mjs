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
    { ...setup, installationId: "my agent name" },
    { ...setup, issuer: "http://identity.example" },
    { ...setup, resource: "akb.example.com" },
  ])
    assert.throws(() => parsePlatformSetup(JSON.stringify(bad)));
  assert.equal(
    Object.keys(
      validatePlatformSetup({ ...setup, resource: "http://127.0.0.1:8080" }),
    ).length,
    0,
  );
});

test("platform setup import reduces the platform endpoint to the origin", async () => {
  const { parsePlatformSetup } = await import("./workload-identity.mjs");
  const setup = {
    format: "akb-platform-setup/v1",
    proofProfile: "AGENT_KEY_RS256_V2",
    platformId: "octop",
    installationId: "install",
    workspaceId: "workspace",
    issuer: "https://octop.local/agents",
    audience: "akb-external-api",
  };
  // 平台导出的是它实际调用的端点，AKB 要的是根地址，导入时取 origin
  assert.equal(
    parsePlatformSetup(
      JSON.stringify({
        ...setup,
        resource: "https://akb.example.com/api/v1/external/kb/retrievals",
      }),
    ).resource,
    "https://akb.example.com",
  );
  // 默认端口与末尾斜杠一并归一
  assert.equal(
    parsePlatformSetup(
      JSON.stringify({ ...setup, resource: "https://akb.example.com:443/" }),
    ).resource,
    "https://akb.example.com",
  );
});

/** 内网部署拿不到公网证书，明文 HTTP 必须放行；公网地址仍然强制 HTTPS。 */
test("platform setup accepts plain http on intranet origins only", async () => {
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
  };
  for (const good of [
    "http://192.168.24.201:8088/api/v1/external/kb/retrievals",
    "http://10.20.30.40",
    "http://172.16.5.9:18080",
    "http://akb.internal",
    "http://akb-gateway",
  ])
    assert.equal(
      parsePlatformSetup(JSON.stringify({ ...setup, resource: good })).resource,
      new URL(good).origin,
    );
  for (const bad of ["http://203.0.113.10", "http://akb.example.com"])
    assert.throws(() => parsePlatformSetup(JSON.stringify({ ...setup, resource: bad })));
  // 手动填写仍要求就是根地址，不帮着截
  assert.equal(
    Object.keys(
      validatePlatformSetup({ ...setup, resource: "http://192.168.24.201:8088/api" }),
    ).length,
    1,
  );
});

/** 规则只有 contracts/intranet-http.json 一份：生成物必须与真源逐字节一致。 */
test("intranet rules are generated from the contract, not maintained twice", async () => {
  const { readFileSync } = await import("node:fs");
  const truth = JSON.parse(
    readFileSync(
      new URL("../../../contracts/intranet-http.json", import.meta.url),
      "utf8",
    ),
  );
  const generated = (await import("./intranet-rules.mjs")).default;
  assert.deepEqual(generated, truth);
});

test("plain http follows the shared intranet contract", async () => {
  const { validatePlatformSetup } = await import("./workload-identity.mjs");
  const { readFileSync } = await import("node:fs");
  const contract = JSON.parse(
    readFileSync(
      new URL("../../../contracts/intranet-http.cases.json", import.meta.url),
      "utf8",
    ),
  );
  const setup = {
    platformId: "octop",
    installationId: "install",
    workspaceId: "workspace",
    issuer: "https://octop.local/agents",
    audience: "akb-external-api",
  };
  for (const item of contract.cases) {
    if (!item.host) continue;
    // 裸 IPv6 在 URL 里要带方括号
    const host =
      item.host.includes(":") && !item.host.startsWith("[")
        ? `[${item.host}]`
        : item.host;
    const allowed = item.loopback || item.intranet;
    assert.equal(
      !validatePlatformSetup({ ...setup, resource: `http://${host}` }).resource,
      allowed,
      `${item.host} 的明文 HTTP 放行应为 ${allowed}`,
    );
  }
});
