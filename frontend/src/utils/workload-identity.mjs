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
/**
 * 内网判定读的是 contracts/intranet-http.json —— 与后端
 * com.jh.knowledgeos.common.IntranetAddressRules 同一个真源（这里用的是
 * scripts/sync-intranet-rules.mjs 生成的模块）。规则只有一处定义，不会各改各的。
 * 公网一律要求 HTTPS，明文只放开本机与内网：内网部署拿不到公网证书，也不该为一个内部
 * 地址去申请。不解析 DNS：解析会阻塞、可能失败，且结果随时可变。
 */
import intranetRules from "./intranet-rules.mjs";

function normalizeHost(host) {
  let value = (host || "").trim().toLowerCase();
  if (value.startsWith("[") && value.endsWith("]")) value = value.slice(1, -1);
  return value;
}
/**
 * 本机地址。导出给界面用：后端是否放行本机明文由 WORKLOAD_ALLOW_LOOPBACK_HTTP 决定（默认
 * 不开），前端无从知道那个开关的值，但至少能提前把「这里需要后端开开关」说清楚，而不是让
 * 用户在控制台填完、提交后才撞上一句后端的拒绝。
 */
export function isLoopbackHost(host) {
  const value = normalizeHost(host);
  if (!value) return false;
  return (
    (intranetRules.loopback?.hosts || []).some(
      (name) => value === name.toLowerCase(),
    ) ||
    (intranetRules.loopback?.ipv4Prefixes || []).some((prefix) =>
      value.startsWith(prefix),
    )
  );
}
function isIntranetHost(host) {
  const value = normalizeHost(host);
  if (!value) return false;
  // 本机不算内网：它是另一类场景。"无点主机名"这条规则本身会把 localhost 算进来，
  // 显式排除才能和后端同口径。
  if (isLoopbackHost(value)) return false;
  if ((intranetRules.ipv6Prefixes || []).some((p) => value.startsWith(p)))
    return true;
  if ((intranetRules.hostSuffixes || []).some((s) => value.endsWith(s)))
    return true;
  if (
    intranetRules.bareHostnameIsIntranet &&
    !value.includes(".") &&
    !value.includes(":")
  )
    return true;
  const parts = value.split(".");
  if (parts.length !== 4 || parts.some((part) => !/^\d+$/.test(part)))
    return false;
  const a = Number(parts[0]);
  const b = Number(parts[1]);
  return (intranetRules.ipv4Ranges || []).some((range) => {
    if (a !== range.first) return false;
    if (range.second !== undefined) return b === range.second;
    // 只给了 first 就是整段（如 10/8），第二段没有上下限——与后端读取时的默认值一致
    const from = range.secondFrom ?? Number.MIN_SAFE_INTEGER;
    const to = range.secondTo ?? Number.MAX_SAFE_INTEGER;
    return b >= from && b <= to;
  });
}

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
    // 本机在控制台侧无条件放行（不要求开关），内网按真源规则判定
    const plainIntranet =
      u.protocol === "http:" &&
      (isIntranetHost(u.hostname) || isLoopbackHost(u.hostname));
    if (
      (u.protocol !== "https:" && !plainIntranet) ||
      u.username ||
      u.password ||
      u.pathname !== "/" ||
      u.search ||
      u.hash ||
      // URL 会把主机名小写化，比较时跟着归一：填 HTTPS://AKB.Example.com 不该被判为带多余东西
      value.resource.replace(/\/$/, "").toLowerCase() !== u.origin
    )
      throw new Error();
  } catch {
    errors.resource =
      "填写 AKB 对外访问的根地址，例如 https://akb.example.com，不要包含 /api 路径、默认端口或末尾参数；HTTP 仅支持本机与内网地址";
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
  // 平台导出的 resource 是它实际调用的端点（如 .../api/v1/external/kb/retrievals），AKB
  // 要的是根地址：取 origin，顺带去掉默认端口与末尾斜杠。手动填写仍然要求就是根地址。
  let resource;
  try {
    resource = new URL(value.resource).origin;
  } catch {
    throw new Error("接入信息包里的 AKB 地址不是一个完整地址，请从平台重新导出");
  }
  const errors = validatePlatformSetup({ ...value, resource });
  if (Object.keys(errors).length)
    throw new Error(Object.values(errors).join("；"));
  return {
    platformId: value.platformId,
    installationId: value.installationId,
    workspaceId: value.workspaceId,
    issuer: value.issuer,
    audience: value.audience,
    resource,
  };
}
