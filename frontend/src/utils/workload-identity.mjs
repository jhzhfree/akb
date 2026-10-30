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
