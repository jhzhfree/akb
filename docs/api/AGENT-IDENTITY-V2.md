# 跨平台 Agent 身份 V2 接入

已实现于 AKB V75 与 Octop 仓库 `integrations/agent-identity-sdk` 0.2.0。共享服务 Key 识别 APPLICATION，每 Agent 独立 RSA 私钥签名，AKB 直接登记并绑定公钥，无需远程 JWKS。现有 V1 策略默认保持安装级 JWKS 模式。

## 部署和兼容

1. 先部署 AKB 后端，应用 V74（未应用时）及 V75。V75 新增 profile、Agent 公钥、登记挑战、V2 防重放表和审计列；不要改写已应用的 V74。
2. 在平台安装 SDK/薄插件，保护配置和密钥根目录；模型的 shell/文件工具应隔离。
3. 创建新 V2 策略与严格共享 Key，登记每 Agent 公钥并完成身份探针，再迁移业务调用。
4. 清理不再使用的旧 Key。V2 不自动回退 V1/LEGACY，也不能把同一个严格 Key 改为 LEGACY 放宽认证。

生产必须 HTTPS。只有本地隔离测试才在 AKB 设置 `WORKLOAD_ALLOW_LOOPBACK_HTTP=true`，SDK 配置 `allowInsecureLoopback:true`；远程 HTTP 仍被拒绝。

## 1. 主体与授权

管理员使用控制台登录 Session/Bearer；这些操作只允许系统 ADMIN，外部 Service Key 不具有管理权限。

```http
POST /api/external/admin/principals
{"name":"OpenCode office","principalType":"APPLICATION"}

POST /api/external/admin/principals
{
  "name":"builder",
  "principalType":"AGENT",
  "providerInstanceId":"office-01",
  "externalSubjectId":"e21cce9c-1b16-4ab8-a76a-bb761df919b8"
}
```

Agent ID 为稳定小写 UUID，可由 SDK 管理 CLI 首次生成并持久化，也可由管理员分配。轮换公钥不改变这个 ID。平台只有 Agent 配置名时，配置本身必须受保护。

使用已有 `PUT /api/external/admin/principals/{id}/knowledge-base-grants/config` 配置 APPLICATION 的库上限与各 AGENT 的独立 grants。最终范围取交集；无库授权不回退公开知识库。资料标签为空只允许已授权库内的无标签资料，不等于禁止整库。

## 2. 创建策略、绑定与共享 Key

```http
POST /api/external/admin/workload/policies

{
  "applicationPrincipalId":123,
  "proofProfile":"AGENT_KEY_RS256_V2",
  "platformId":"opencode",
  "issuer":"https://identity.example.com/opencode",
  "audience":"akb-external-api",
  "installationId":"office-01",
  "workspaceId":"engineering-01",
  "resourceOrigin":"https://akb.example.com"
}
```

V2 不传 jwksUri。issuer 是受控命名空间，不需要部署网站。resourceOrigin 为精确规范 origin，无路径、末尾斜杠、query、fragment、凭据或默认 443 端口。它由管理员登记，不从客户端 Host/Forwarded 推断。

```http
POST /api/external/admin/workload/policies/17/agents

{
  "externalAgentId":"e21cce9c-1b16-4ab8-a76a-bb761df919b8",
  "runtimeAgentKey":"builder",
  "agentPrincipalId":456
}
```

AGENT 的 externalSubjectId 与 providerInstanceId 必须匹配登记的稳定 ID 和安装 ID。一个策略中运行键、稳定 ID 分别唯一。记录返回的 binding id。

```http
POST /api/external/admin/workload/policies/17/keys

{
  "name":"Shared agent service key",
  "scopes":["SEARCH","QUERY","LIST","READ_OBJECT"],
  "expiresAt":"2026-12-31T23:59:59",
  "rateLimitPerMinute":60
}
```

日期应替换为部署时未来日期，最长 365 天。返回 secret 仅显示一次，保存到受保护文件；不为每 Agent 签发不同服务 Key。Key 仍属于 APPLICATION，实际 Agent 是请求级不可变身份。

## 3. 生成 Agent 密钥与公钥包

使用 SDK CLI 的 identity create、agent init、key export-public。RSA 默认 3072 位，支持登记 3072–4096 位、指数 65537；kid 为 RFC7638 SHA-256 指纹。公钥仅包含 kty/n/e/kid/alg/use，alg 必须 RS256，use 必须 sig。私钥不发 AKB。

SDK 文件 Provider 当前为 POSIX 0700/0600、当前账户所有且工作区外；Windows 需注入 ACL/Keychain Provider，默认文件 Provider 会明确拒绝。可替换 RsaSigner 端口支持将来 KMS，但模块化本身不隔离同 UID 恶意代码。

## 4. 管理员挑战与持钥登记

```http
POST /api/external/admin/workload/policies/17/agents/21/keys/challenges

{"publicJwk":{"kty":"RSA","n":"<modulus>","e":"AQAB","kid":"<fingerprint>","alg":"RS256","use":"sig"}}
```

响应包含 challengeId、nonce、policyId、bindingId、externalAgentId、jkt、purpose、audience、expiresAt（整数秒）。有效期 5 分钟，同一绑定最多 5 个未过期未消费挑战，最多 2 个未过期活动密钥。

部署端核对 owner/kid，以目标私钥签名专用登记证明；同一管理员提交完成请求：

```http
POST /api/external/admin/workload/policies/17/agents/21/keys/registrations

{"challengeId":"<uuid>","registrationProof":"<专用JWS>"}
```

登记证明 Header typ 为 `akb-key-registration+jwt`，aud 为 `akb-agent-key-registration`，purpose 为 `akb.agent-key.register`。绑定挑战、nonce、公钥指纹和 Agent，不能当成知识库请求证明。服务端以挑战中保存的公钥验证，并在同一事务消费挑战、登记公钥、写管理审计；重放拒绝。普通插件不保存管理员 Token。

## 5. 身份探针与本地选中

```http
GET /api/v1/external/identity/status
X-API-Key: <shared key>
X-AKB-Workload-Assertion: <候选Agent密钥签发的V2请求证明>
```

要求 LIST scope，不读取知识库。响应 data 仅含 agentId、agentPrincipalId、applicationPrincipalId、policyId、kid、proofProfile、keyStatus。SDK key select 命令先核对此探针为预期 Agent、kid、V2 及 ACTIVE，再原子选中本地密钥。

## 6. 业务请求协议

每次请求同时携共享 Key 与逐请求签名。Header：`alg=RS256`、`typ=akb-agent-proof+jwt`、`kid=<fingerprint>`。

声明包括 ver=2、iss、单字符串 aud、resource、policy_id、azp、sub、platform_id、installation_id、workspace_id、runtime_agent、session_id、可选 message_id、invocation_id、iat、exp、jti、credential_sha256 及 request_binding。

- 公钥必须属于当前策略及 Agent 绑定；A 私钥即使签了 B 的 sub 也拒绝。
- TTL 最长 60 秒，iat 最多未来 30 秒；exp 严格晚于当前时间。
- 每 HTTP 请求独立 jti，数据库原子消费；MCP initialize/tools-call 不能复用证明。
- 绑定方法、原始 path/query、精确 body SHA-256、资源 origin 和实际共享 Key SHA-256。
- 禁止其他认证混用、任意外来公钥 URL、重复 JSON 字段、错误类型、未知算法或版本。
- 不接受 Key 首尾空白；客户端读取文件时可去掉末尾换行，再用同一值计算摘要及发送。
- 正文上限 1 MiB、断言 8192 字符，代理不能重写签名覆盖的路径或正文。

SDK 首版不自动重试、只开放 bases/search/query。HTTP 200 的 MCP 仍需检查 JSON-RPC error/isError。用户身份、父子任务权限链、Memory、写入不包含在本版。

## 7. 生命周期

| 接口 | 行为 |
|---|---|
| GET /api/external/admin/workload/policies/{p}/agents/{b}/keys | 公钥记录、状态及有效期 |
| PUT /api/external/admin/workload/policies/{p}/agents/{b}/keys/{kid}/status | ACTIVE→RETIRING→REVOKED 或 ACTIVE→REVOKED |
| PUT /api/external/admin/workload/policies/{p}/agents/{b}/status | 单 Agent ACTIVE/SUSPENDED/REVOKED |
| PUT /api/external/admin/workload/policies/{p}/status | 整安装策略 ACTIVE/SUSPENDED/REVOKED |
| POST /api/external/admin/keys/{keyId}/rotate | 轮换共享 Key，保留严格模式和策略 |

退休 body：`{"status":"RETIRING","validUntil":"<未来一天内UTC ISO时间>"}`。撤销 body：`{"status":"REVOKED","reason":"<可选>"}`。REVOKED 为终态，不允许恢复。

正常轮换：生成 K2→登记并探针→本地选中 K2→K1 退休→等待所有部署及至少覆盖请求/时钟窗口→撤销 K1。RETIRING 仅接收 exp 不超过 validUntil 的证明。紧急泄漏直接撤销或暂停，不等窗口。

SDK 每调用读取活动密钥，每 HTTP 请求读取共享 Key，因此 select/服务 Key 更新可作用于后续调用；绑定配置改变需要重启宿主。撤销不撤回已返回的数据，也不保证取消已认证在途请求。

## 8. 错误、审计与生产限制

认证拒绝为 HTTP 401（V2 证明错误通常 code=AKB_WORKLOAD_PROOF_INVALID，缺 Key/公共入口拒绝沿用 KB-401-001），资源授权拒绝 403/KB-403-001，限流 429/KB-429-001，超大正文 413/REQUEST_TOO_LARGE。工作负载身份存储异常 503/AKB_IDENTITY_UNAVAILABLE，不能降级。

调用审计增加 proofProfile、signingKid、agentKeyRegistrationId、workloadMessageId、workloadPlatformId；同 Key 可追踪不同 Agent 与密钥版本。管理审计不存 nonce、私钥、Service Key 或完整登记证明。普通业务审计仍是尽力写入，不是不可变审计账本。

当前配额仍是按 Key 的进程内限流，多副本需另做分布式限流，入口应配置前置速率控制。公钥绑定缩小密钥泄漏影响，但宿主或同 UID 恶意程序若能读取所有私钥，仍可冒用；需要部署隔离。

## 前端管理入口

`接口服务 → 更多能力 → 凭证 → Agent 身份接入`（仅 ADMIN）提供 V2 策略、应用主体、Agent 绑定、公钥挑战登记 / 退役 / 撤销、只读共享 Key 签发 / 轮换和最近调用审计。应用与 Agent 分别配置知识库授权，最终权限仍取交集。导入仅支持公钥包 / 公共 JWK，私钥由对应平台 SDK 保管，登记证明由平台签署后交回本页。暂停策略仍可查看绑定与公钥，不能继续签发凭证或登记密钥。

资料标签授权区分“继承主体权限”（null）与“无标签资料”（空数组）；保存授权时保留已有语义。服务 Key 仅在生成 / 轮换时显示一次，关闭弹窗即清除页面状态。

更新分支后需重新构建 `frontend`（`npm ci && npm run typecheck && npm test && npm run build`）并部署新的 `dist/`，同时更新后端；仅更新后端不会改变现有静态页面。Octop 对应入口为“外部知识库 → AKB 知识源右侧盾牌按钮”。完整双端步骤、SDK 0.3.1 的宿主安装和安全边界见 Octop 仓库 `integrations/agent-identity-spec/DASHBOARD-SETUP.md`。

### 新手向导与参数交接

“连接新平台”向导引导用户导入平台提供的公开接入信息、命名应用、核对创建；安装实例 / 工作区等标识不应由用户编造。Octop 页面可直接下载 `akb-platform-setup/v1` 信息包；OpenCode 配套 SDK 0.3.1+ 可运行 `akb-agent-identity setup export /absolute/path/identity.json > akb-setup.json`。

创建后页面给出四步指引及未授权提示。AKB 可导出 `akb-platform-connection/v1` 平台连接配置，供 Octop 自动填写应用 / 策略 ID、签发者和受众；导入时核对平台、安装实例、工作区及资源地址，且不自动保存、不更改认证模式。两个信息包都不包含服务 Key 或私钥；服务 Key 仍通过一次性凭证窗口单独配置，Agent 公钥仍需完成挑战登记与远端验证。
