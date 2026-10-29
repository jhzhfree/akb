# OpenCode 共享 Service Key 的 Agent 身份接入

本文为 V1 安装级 JWKS 兼容模式。推荐新接入使用 [V2 Agent 独立公钥模式](AGENT-IDENTITY-V2.md)：AKB V75 与通用 SDK/插件 0.2.0 已实现，无需插件托管 JWKS。两种模式由策略显式区分，不自动回退。策略和绑定 REVOKED 现在为终态，不能重新启用。

适用：OpenCode 1.14.30 插件代理工具、AKB V74 迁移后的后端。首版为只读知识库认证，不开放 Memory、写入或 USER 委托。

## 凭据模式与兼容性

现有 Key 默认 `LEGACY`，已有 Octop AGENT Key、PAT 与 API 不改变认证方式。通过新路由创建的 Key 使用 `APPLICATION_WORKLOAD`，归 APPLICATION 所有；实际 Agent 存在请求级不可变 WorkloadIdentity 中，不改写 ApiKey.principalId。

新模式要求每个 MCP/REST 请求同时带 `X-API-Key` 和 `X-AKB-Workload-Assertion`。不接受与 Authorization、X-Octop-Identity 混合，Embed Token 不能绕过此模式。缺断言、错误签名、错误 app/实例/工作区、过期、请求体篡改、重复 jti 均拒绝。

## 管理员配置顺序

以下操作通过已有控制台登录 Session/Bearer 认证进行，不使用外部服务 Key。工作负载信任配置只允许系统 ADMIN。

1. 创建 APPLICATION：`POST /api/external/admin/principals`，`principalType: "APPLICATION"`。
2. 创建两个 AGENT：同接口，分别设置稳定 `externalSubjectId`（建议 UUID），`providerInstanceId` 为同一 OpenCode 安装 ID。记录两个 Principal ID。
3. 给 APPLICATION 设置可访问库的上限，给各 AGENT 设置各自的库/资料标签：`PUT /api/external/admin/principals/{id}/knowledge-base-grants/config`。没有授权不回退到公开库；两层取交集。
4. 发布插件安装实例公开 JWKS，创建策略。
5. 管理员登记每个运行 Agent 与 AKB Principal 的映射；不需要给每个 Agent 创建 Key 或 MCP 配置。
6. 通过策略签发一个共享 Key，保存一次性返回的 secret 到插件受保护文件。

创建策略：

```http
POST /api/external/admin/workload/policies
Content-Type: application/json

{
  "applicationPrincipalId": 123,
  "issuer": "https://identity.example.com/opencode",
  "jwksUri": "https://identity.example.com/.well-known/jwks.json",
  "audience": "akb-external-api",
  "installationId": "opencode-office-01",
  "workspaceId": "engineering-01"
}
```

URL 不允许内嵌凭据、query、fragment，默认必须 HTTPS。仅本地开发可在 AKB 显式设置 `WORKLOAD_ALLOW_LOOPBACK_HTTP=true` 接受 loopback HTTP，不用于远程 HTTP。

登记 Agent：

```http
POST /api/external/admin/workload/policies/17/agents

{
  "externalAgentId": "e21cce9c-1b16-4ab8-a76a-bb761df919b8",
  "runtimeAgentKey": "builder",
  "agentPrincipalId": 456
}
```

AGENT 必须 ACTIVE，其 `externalSubjectId` 与 externalAgentId 相同，externalInstanceId 与安装 ID 相同。一个策略下 external ID 和运行配置名分别唯一。管理员直接登记即表示批准，不提供模型自行注册/自动授权接口。

签发严格 Key：

```http
POST /api/external/admin/workload/policies/17/keys

{
  "name": "OpenCode shared MCP",
  "scopes": ["SEARCH", "QUERY", "LIST", "READ_OBJECT"],
  "expiresAt": "2026-12-31T23:59:59",
  "rateLimitPerMinute": 60
}
```

不传 scopes 使用上面四项默认值；空列表、写入/Memory scope 拒绝。有效期必填且在未来，不超过 365 天。Key 签发和策略设置在同一事务完成。旧签发路由不隐式启用新模式，应使用此路由。

生命周期：

| 方法与路径 | 行为 |
|---|---|
| GET /api/external/admin/workload/policies | 查看策略和脱敏配置 |
| GET /api/external/admin/workload/policies/{id}/agents | 查看已登记 Agent |
| PUT /api/external/admin/workload/policies/{id}/status | `{ "status": "ACTIVE/SUSPENDED/REVOKED" }` |
| PUT /api/external/admin/workload/policies/{id}/agents/{bindingId}/status | 按绑定暂停/撤销，影响后续请求 |
| POST /api/external/admin/keys/{id}/rotate | 已有轮换接口；新 Key 保留 identityMode/workloadPolicyId |

状态变更与策略、绑定、凭据创建写入 t_workload_admin_audit，不保存完整 secret。策略配置不可在线随意改 issuer 或主体；需要重新建立明确的新策略和映射。

## 请求契约

RS256 JWT 使用受控策略的 JWKS 验签，不能从 JWT 的 jku 动态选择密钥地址。必须包含：

| Claim | 值 |
|---|---|
| iss / aud | 对应策略 |
| sub | `agent:<externalAgentId>` |
| azp | APPLICATION Principal ID 的十进制字符串 |
| installation_id / workspace_id | 对应策略 |
| runtime_agent | 对应管理员登记的运行 Agent 配置名 |
| session_id / invocation_id | 运行追踪，最多 128 字符 |
| iat / exp | 最多 60 秒的有效期，拒绝过期和过远未来签发 |
| jti | 每个 HTTP 请求独立随机 ID，跨实例数据库原子防重放 |
| request_binding.method | 大写 HTTP 方法 |
| request_binding.target | 后端实际请求 URI + 可选原始 query，与反向代理的路由重写保持一致 |
| request_binding.body_sha256 | 实际发送的 body 字节 SHA256；GET 空 body 用空字节哈希 |

签名证明不放在模型工具参数中。MCP initialize、tools/call 分别签名；禁止复用同一个 token。控制器读取 body 前有 1 MiB 有界缓存，保留精确字节和可再次读取的输入流。上游 API origin 固定；代理不得重写签名覆盖的路径或 body。

这是应用定义的请求绑定协议，不声称实现标准 DPoP。当前绑定方法、URI/query、body；自定义业务 Header 不在摘要范围，授权仍由服务端 grant 独立限制。

## 授权、审计与上线

知识范围为 Key scopes ∩ APPLICATION KB/标签上限 ∩ AGENT KB/标签授权，普通查询 filters 只能进一步收窄。严格模式即使 legacy ACL 为 CLIENT/SHADOW 也执行交集。首版的 Memory/写入 scope 无效，不使用共享应用的 Memory 身份伪装 Agent。

检索和对象读取均传已认证凭据授权上下文。REST 标准检索接口的私有库编码改为按库编码直接定位，再由外部授权检查；不再按控制台 Web 用户列表解析。

`GET /api/external/admin/audits?keyId=...` 返回 applicationPrincipalId、agentPrincipalId、workloadSessionId、workloadInvocationId、workloadIdentityMode，能够证明同 Key 下不同 Agent。传输 HTTP 200 与 JSON-RPC 业务错误分别记录。认证失败不会把未验签的 Agent 声明当作有效身份。

先部署 AKB 迁移和向后兼容后端，再部署插件及公开 JWKS，最后给新安装启用严格 Key。回滚应暂停策略/撤销严格 Key；不要把同一 Key 改为 LEGACY 来放宽权限。

管理入口目前为上述 API，不新增控制台页面。插件自定义入口不等于 OpenCode 原生 MCP 逐请求 hook；任意 shell/curl 不会自动继承身份。宿主同 UID 的 shell/恶意插件能读取本地私钥的风险，需要部署层隔离，不属于此插件可提供的进程安全边界。
