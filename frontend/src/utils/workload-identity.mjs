/** Public registration files only. Never accept a private key or a service credential. */
export function parsePublicBundle(text) {
  if (typeof text !== "string" || text.length > 16384)
    throw new Error("公钥接入包不能超过 16 KiB");
  if (
    /PRIVATE KEY|"(?:privateKey|private_key|secret|api_key|serviceKey)"\s*:/.test(
      text,
    )
  )
    throw new Error("请只导入公钥接入包，不能包含私钥或服务 Key");
  const value = JSON.parse(text);
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("接入包必须为 JSON 对象");
  const jwk = value.publicJwk || value;
  const fields = ["kty", "n", "e", "kid", "alg", "use"];
  if (
    !jwk ||
    typeof jwk !== "object" ||
    Object.keys(jwk).length !== 6 ||
    !Object.keys(jwk).every((k) => fields.includes(k)) ||
    jwk.kty !== "RSA" ||
    jwk.alg !== "RS256" ||
    jwk.use !== "sig" ||
    jwk.e !== "AQAB" ||
    typeof jwk.n !== "string" ||
    !/^[-_A-Za-z0-9]{512,683}$/.test(jwk.n) ||
    typeof jwk.kid !== "string" ||
    !/^[-_A-Za-z0-9]{43}$/.test(jwk.kid)
  )
    throw new Error("需要 RSA 3072–4096 位 RS256 公钥，不接受私钥字段");
  const owner = value.owner || value;
  const externalAgentId = owner.externalAgentId || "";
  if (
    externalAgentId &&
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      externalAgentId,
    )
  )
    throw new Error("Agent 稳定 ID 必须为小写 UUID");
  return {
    publicJwk: jwk,
    externalAgentId,
    runtimeAgentKey: value.runtimeAgentKey || "",
    installationId: owner.installationId || "",
    workspaceId: owner.workspaceId || "",
  };
}

export function parseRegistrationProof(text) {
  let value = text.trim();
  if (value.startsWith("{")) value = JSON.parse(value).registrationProof;
  if (
    typeof value !== "string" ||
    value.length > 8192 ||
    !/^[-_A-Za-z0-9]+\.[-_A-Za-z0-9]+\.[-_A-Za-z0-9]+$/.test(value)
  )
    throw new Error("请输入或导入有效的登记证明 JWT");
  return value;
}

const setupFields = [
  "format",
  "proofProfile",
  "platformId",
  "installationId",
  "workspaceId",
  "issuer",
  "audience",
  "resource",
];
export function validatePlatformSetup(value) {
  const errors = {};
  for (const name of ["platformId", "installationId", "workspaceId"]) {
    if (
      typeof value[name] !== "string" ||
      !/^[A-Za-z0-9_.:-]{1,128}$/.test(value[name])
    )
      errors[name] =
        name === "platformId"
          ? "请选择调用平台，其他平台标识只能使用字母、数字、点、冒号、下划线和短横线"
          : "请从调用平台复制此值，不能填写 Agent 名称、网址或任意空格";
  }
  try {
    const u = new URL(value.issuer);
    if (
      u.protocol !== "https:" ||
      u.username ||
      u.password ||
      u.search ||
      u.hash ||
      value.issuer.length > 255 ||
      value.issuer.trim() !== value.issuer
    )
      throw new Error();
  } catch {
    errors.issuer =
      "请复制平台提供的签发者标识，必须为不含账号、查询参数和片段的 HTTPS 地址";
  }
  if (
    typeof value.audience !== "string" ||
    !value.audience.trim() ||
    value.audience.trim() !== value.audience ||
    value.audience.length > 128 ||
    /[\x00-\x1f\x7f]/.test(value.audience)
  )
    errors.audience = "请填写平台提供的受众标识（1–128 个字符）";
  try {
    const u = new URL(value.resource);
    if (
      (u.protocol !== "https:" &&
        !(
          u.protocol === "http:" &&
          ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname)
        )) ||
      u.username ||
      u.password ||
      u.pathname !== "/" ||
      u.search ||
      u.hash ||
      value.resource.replace(/\/$/, "") !== u.origin
    )
      throw new Error();
  } catch {
    errors.resource =
      "填写 AKB 对外访问的根地址，例如 https://akb.example.com，不要包含 /api 路径、默认端口或末尾参数；HTTP 仅支持本机调试";
  }
  return errors;
}
export function parsePlatformSetup(text) {
  if (typeof text !== "string" || text.length > 16384)
    throw new Error("接入信息包不能超过 16 KiB");
  const value = JSON.parse(text);
  if (
    !value ||
    typeof value !== "object" ||
    Array.isArray(value) ||
    Object.keys(value).some((k) => !setupFields.includes(k)) ||
    value.format !== "akb-platform-setup/v1" ||
    value.proofProfile !== "AGENT_KEY_RS256_V2"
  )
    throw new Error(
      "请导入平台导出的 AKB 接入信息包，不要上传完整配置、私钥、公钥包或服务 Key",
    );
  const errors = validatePlatformSetup(value);
  if (Object.keys(errors).length)
    throw new Error(Object.values(errors).join("；"));
  return {
    platformId: value.platformId,
    installationId: value.installationId,
    workspaceId: value.workspaceId,
    issuer: value.issuer,
    audience: value.audience,
    resource: value.resource.replace(/\/$/, ""),
  };
}
