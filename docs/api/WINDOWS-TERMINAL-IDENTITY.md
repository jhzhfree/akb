# Windows 终端身份与 MCP 工具上下文

本次变更兼容现有 `AGENT_KEY_RS256_V2`。终端 SDK/插件制作 CLI 位于 Octop 仓库的 `integrations/agent-identity-sdk` 与 `integrations/opencode-identity`；Windows 使用步骤见适配器的 `WINDOWS.md`。

终端为每个 Agent 保存稳定互信 UUID 与独立 RSA 私钥；AKB 登记同一 UUID 的公钥。每个请求发送 `X-API-Key` 和 `X-AKB-Workload-Assertion`。签名不仅包含 Agent UUID/运行绑定，还绑定方法、路径、正文、Service Key 摘要、60 秒时间窗和唯一 jti，沿用现有防重放机制。

## 身份从哪里来

```text
OpenCode 可信 ToolContext.agent
  → 本地受保护的 Agent / UUID / 私钥映射
  → SDK 构造并签署 V2 JWS
  → WorkloadRequestFilter 缓存精确请求正文
  → ExternalAccessService / AgentKeyProofVerifier 验证公钥归属、请求和防重放
  → 当前请求的 ApiKey.workloadIdentity
  → McpServerService.toolsCall(key, params)
  → 业务 facade / memoryService 收到同一个已认证 key
```

现有 MCP 已将 `ApiKey` 显式传入工具分发与业务方法，因此无需在模型填写的 `arguments` 内注入身份。新工具也必须通过 `key.getWorkloadIdentity()` 获取已验证身份，不能采用调用参数里的 `agentId`、Agent 名称或未经验证的 `_meta` 作为授权依据。身份是每次请求的不可变对象，不使用跨请求全局变量或线程上下文。

## 身份诊断接口

已有 `GET /api/v1/external/identity/status` 与新增 MCP 工具 `get_agent_identity` 共用 `VerifiedAgentIdentity.describe(key)`。二者都要求签名身份以及 `LIST` scope；MCP 身份工具只对已验签的 V2 请求展示。

```json
{
  "jsonrpc": "2.0",
  "id": "identity-check-1",
  "method": "tools/call",
  "params": { "name": "get_agent_identity", "arguments": {} }
}
```

调用必须带对应请求的有效 V2 签名，不能复用 REST 身份探针或 `initialize` 的签名。结果位于 `result.structuredContent`：

| 字段 | 含义 |
| --- | --- |
| `agentId` | 已登记互信 UUID |
| `agentPrincipalId` / `applicationPrincipalId` | 当前已验证主体 |
| `policyId` | 服务策略 |
| `runtimeAgentKey` | 服务端登记绑定中的运行 Agent 键 |
| `terminalAgentName` | 可选签名声明 `terminal_agent`，由可信宿主读取真实执行名称 |
| `kid` / `proofProfile` / `keyStatus` | 验签使用的公钥及状态 |
| `platformId` / `sessionId` / `invocationId` | 本次已验证调用上下文 |

`arguments` 必须为空，传入身份参数会拒绝。无 V2 身份时工具拒绝调用，不回显客户端声称的身份。

`terminal_agent` 是新增的可选签名字段，旧客户端可省略，服务端返回 null。它长度最多 128 字符，只允许 `[A-Za-z0-9_.:-]`。简化接入时服务端 `runtimeAgentKey` 可能为 UUID，本地执行名称仍保留在 `terminalAgentName`。名称是持钥宿主签署的诊断断言，不会改变数据库绑定、主体或授权范围。SERVICE_SCOPE 的身份复制也保留这些字段。

## 验收

运行后端 `mvn test`；新增测试覆盖签名名称保留、改名后签名失效、非法名称拒绝、身份工具参数伪造拒绝及无身份请求拒绝。SDK 测试独立检查 JWS 签名与请求绑定；插件测试检查并发 Agent、真实审批 Effect、拒绝时无请求，以及审计不可用时无业务请求。

现场先登记公钥与 UUID，再运行 `akb-plugin-builder verify <identity.json> <workspace> <agent-name>`。该命令分别验证 REST 和 MCP，任何一步不匹配都不能返回 verified。最后必须从目标 Windows 的真实 OpenCode Agent 发起业务工具调用，并结合 AKB 审计核对 UUID、kid 与授权结果。没有真实 Windows/AKB 环境时不能声称完成现场验收。
