Warning: truncated output (original token count: 24537)
Total output lines: 1777

# 知识集成 · 对外接口文档（外部系统调用）

> 适用版本：knowledge-os 1.1.0（分支 v1.4）
> 面向读者：需要把知识库能力接进自建业务系统 / 智能体 / IDE 的外部调用方
> 文中所有返回样例均取自真实环境的实测响应（敏感 ID 已做替换）

知识集成对外提供**三条通道**，共用同一个 API Key、同一套权限与同一份调用审计：

| 通道 | 入口 | 适用场景 |
|---|---|---|
| RESTful | `/api/v1/external/**` | 业务系统后端对接，最通用 |
| MCP Server | `POST /api/mcp` | 智能体 / IDE / Copilot 以 MCP 客户端方式接入 |
| SSE 推送 | `/api/v1/external/stream/**` | 流式问答、知识库事件订阅 |

REST、MCP、SSE 共用知识服务的鉴权、限流与审计；Agent Memory 通过 REST 与 MCP 提供，使用单独的 Memory Scope，不会因拥有知识库 READ/WRITE 权限而自动开启。

---

## 1. 快速开始（5 分钟跑通）

### 1.1 签发 API Key

由**系统管理员**或**知识库管理员**在控制台「知识集成 → API Key」签发，或调用管理接口：

```bash
curl -X POST http://<host>:8080/api/external/admin/keys \
  -H 'Content-Type: application/json' \
  -H 'Cookie: <控制台登录会话>' \
  -d '{
        "name": "客服工单系统",
        "knowledgeBaseId": 1,
        "scopes": ["READ", "INGEST"],
        "rateLimitPerMinute": 120,
        "expiresAt": "2026-12-31T23:59:59"
      }'
```

响应（**明文 `secret` 只出现这一次**，请立即保存）：

```json
{
  "success": true,
  "code": "OK",
  "message": "成功",
  "data": {
    "id": 7,
    "keyCode": "AK_20260921xxxx",
    "name": "客服工单系统",
    "keyPrefix": "kos_ak_S9MP25",
    "knowledgeBaseId": 1,
    "knowledgeBaseName": "默认知识库",
    "scopes": "READ,INGEST",
    "rateLimitPerMinute": 120,
    "status": "ACTIVE",
    "expiresAt": "2026-12-31T23:59:59",
    "lastUsedAt": null,
    "createdByName": "系统管理员",
    "createdTime": "2026-09-21T12:00:00",
    "secret": "kos_ak_S9MP25n3HNv8hgr6VHPrTXkgK2iSBwwC"
  }
}
```

数据库只存 SHA-256 摘要与展示用前缀，明文不可再次找回；`keyPrefix` 用于在控制台辨认是哪一把 Key。

### 1.2 第一次调用

```bash
curl -H 'X-API-Key: kos_ak_S9MP25n3HNv8hgr6VHPrTXkgK2iSBwwC' \
     http://<host>:8080/api/v1/external/bases
```

```json
{
  "success": true,
  "code": "OK",
  "message": "成功",
  "traceId": "a58c0813546648e981d5e17ef422fc8f",
  "data": [
    {
      "id": 1,
      "baseCode": "KB_DEFAULT",
      "name": "默认知识库",
      "description": null,
      "status": "ACTIVE",
      "visibility": "PUBLIC",
      "createdByName": "系统管理员"
    }
  ]
}
```

### 1.3 问一个问题

```bash
curl -X POST http://<host>:8080/api/v1/external/query \
  -H 'X-API-Key: kos_ak_xxx' \
  -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId": 1, "question": "知识库如何做权限控制", "topK": 3, "answerFormat": "markdown"}'
```

返回的 `data.answer` 是答案，`data.citations` 是逐条引用，`data.queryId` 可用于链路回放（见 §4.10）。

`answerFormat` 可以整个省掉——不传即默认格式（模型原文）。四种取值见 §4.2；响应里的 `answerFormat` 是**实际生效**的那个，传了认不出来的取值会退回 `default` 而不是报错。

---

## 2. 鉴权

### 2.1 凭据传递方式（三选一，优先级从高到低）

| 方式 | 示例 |
|---|---|
| 请求头 `X-API-Key` | `X-API-Key: kos_ak_xxx`（推荐） |
| `Authorization: Bearer` | `Authorization: Bearer kos_ak_xxx` |
| URL 参数 `apiKey` | `?apiKey=kos_ak_xxx`（仅便于浏览器/curl 调试，不建议生产使用） |

### 2.2 Scope（权限范围）

| Scope | 含义 | 涉及的接口 |
|---|---|---|
| `LIST` | 列出知识库与订阅可见事件 | `GET /bases`、MCP `list_knowledge_bases` / `resources/list`、SSE `/stream/events` |
| `QUERY` | 生成答案的问答 | `POST /query`、MCP `knowledge_query`、SSE `/stream/query`、嵌入令牌签发；第三方检索协议在 `includeAnswer=true` 时也需要 |
| `SEARCH` | 只检索、不生成答案 | `POST /search`、MCP `knowledge_search`、`POST /kb/retrievals` |
| `READ_OBJECT` | 读取卡片、断言、证据、图谱、概览、问答 Trace 与嵌入配置 | `/cards`、`/claims`、`/evidence`、`/graph`、`/overview`、`/queries/{queryId}`、embed `/config`、MCP 对应对象工具与 `resources/read` |
| `READ` | 兼容旧凭证的读取权限组，等价于 `LIST + QUERY + SEARCH + READ_OBJECT` | 旧 Key 行为保持不变 |
| `INGEST` | 文档/文本接入 | `POST /documents/text`、MCP 工具 `ingest_text` |
| `WRITE` | 知识对象治理 | 预留，具备 `WRITE` 即隐含 READ 与 INGEST |
| `MEMORY_READ` | Agent 记忆召回 | `POST /memories/recall`、MCP 工具 `memory_recall` |
| `MEMORY_WRITE` | Agent 记忆 Capture / Forget | `POST /memories/capture`、`DELETE /memories/{memoryCode}`、MCP `memory_capture` / `memory_forget` |

新签发的 Key 可只授予所需细分动作，例如仅问答可用 `QUERY`，只供 Agent 自行组装上下文可用 `SEARCH`，读取来源/证据需要 `READ_OBJECT`。MCP `tools/list` 只公布当前 Key 有权使用的知识工具，服务端在每次工具调用时仍会再次校验。历史 `READ` 继续等价于 `LIST + QUERY + SEARCH + READ_OBJECT`；历史 `WRITE` 继续兼容为读取权限组加 `INGEST`。`WRITE` 不会隐含授予记忆权限。不传 `scopes` 时默认为 `READ`。无对应 scope 调用会返回 403。

启用记忆权限时可同时配置 API Key 的 `memoryNamespace` 与 `memoryAgentCode`：默认命名空间按 API Key 隔离；多个 Agent Key 配置相同 namespace 后仍需管理员逐 Key 授予 TEAM / PROJECT / TASK / WORKFLOW / SESSION 的读取或写入权限。只有精确授权的 `scopeType + scopeCode` 可访问，且 scope code 区分大小写。`memoryAgentCode` 是服务端信任的 Agent 身份，调用参数不能更改。不同租户/应用不得复用 namespace。

记忆能力另有三项开关：平台总开关 `EXTERNAL_AGENT_MEMORY_ENABLED`（默认 `true`，关闭后 REST 记忆端点拒绝调用、MCP 不公开记忆工具）；API Key 的 `MEMORY_READ` / `MEMORY_WRITE` 签发 scope；以及凭证控制台中的逐 Key 记忆策略开关。逐 Key 策略只能关闭或恢复签发时已经授予的 scope，不能借此增加权限。普通 `POST /query` 和 MCP `knowledge_query` 不自动调用记忆；需要记忆的 Agent 应显式调用 `memory_recall`，任务完成后按策略调用 `memory_capture`。

### 2.3 知识库归属与主体授权

API Key 通过主体获得知识库授权。管理员可读取或原子替换一个 Key 所属主体的授权：

```http
GET /api/external/admin/keys/{id}/knowledge-base-grants
PUT /api/external/admin/keys/{id}/knowledge-base-grants
GET /api/external/admin/keys/{id}/knowledge-base-grants/config
PUT /api/external/admin/keys/{id}/knowledge-base-grants/config
Content-Type: application/json

[12, 18]
```

`PUT` 请求体是知识库 ID 数组；空数组 `[]` 表示撤销全部。授权归共享 Principal 所有，因此变更会立即作用于该主体绑定的所有 API Key；授权变更写入授予/撤销审计。API 请求中的知识库 ID 必须匹配有效 grant；V61 会把既有单库绑定迁移为等价 grant，撤销后不会通过遗留 `knowledge_base_id` 回退访问。

多个 API Key 可通过共享 Principal 共用同一份授权。管理员可查询主体清单并将 Key 加入现有主体，或为 Key 创建独立主体：

```http
GET /api/external/admin/principals
POST /api/external/admin/keys/{id}/principal
Content-Type: application/json

{ "principalId": 42, "reason": "同一 Octop Agent 服务接入" }
```

`principalId` 与 `name` 二选一。指定 `principalId` 后，该 Key 会立即继承目标主体当前有效的知识库授权；指定 `name` 时会创建主体、复制当前 Key 的有效授权，再绑定 Key。主体变更原因必填，写入 V62 的主体关联审计。主体不会物理删除，以保留审计引用。

知识库授权配置还可通过 `permissionTags` 为每个库设置独立资料标签范围：`null` 表示继承 API Key 标签配置，`[]` 表示仅允许无标签的公开资料，非空数组表示该库额外允许的标签。知识库标签范围与 Key 全局标签范围取交集，请求中的 `permissionTags` 只能进一步收窄。变更审计会保存新旧标签范围快照。

需要管理每个授权的期限时，使用 `/config` 路由，逐条提交 `knowledgeBaseId`、`expiresAt` 和可选的 `reason`。`expiresAt` 使用本地 ISO 时间（如 `2026-12-31T23:59:59`），留空表示长期有效；已到期时间会被拒绝，重复知识库 ID 也会被拒绝。该路由同样采用整体替换语义：提交的清单之外的授权将被撤销。

```json
[
  { "knowledgeBaseId": 12, "expiresAt": "2026-12-31T23:59:59", "reason": "项目验收期临时授权" },
  { "knowledgeBaseId": 18, "expiresAt": null, "reason": "长期业务接入" }
]
```

### OIDC 员工委托身份（可选）

默认关闭。启用 `knowledge.external.delegated-identity.enabled=true` 后，每次 REST、MCP 或 SSE 请求都必须同时携带 Agent 的 `X-API-Key` 和员工 IdP 的 `Authorization: Bearer <JWT>`。此模式不接受把 JWT 当作 API Key，也不接受 `X-User-Id` 等调用方自报身份；独立 Embed Token 会被拒绝。

服务端需要配置以下 IdP 参数，并通过 JWKS 验签：

```properties
knowledge.external.delegated-identity.enabled=true
knowledge.external.delegated-identity.issuer=https://idp.example.com/
knowledge.external.delegated-identity.jwk-set-uri=https://idp.example.com/.well-known/jwks.json
knowledge.external.delegated-identity.audience=akb-external-api
```

JWT 必须包含并通过验证：`iss`、配置的 `aud`、`exp`、`nbf`、非空 `sub`。管理端需先创建 USER 主体，再将 IdP `issuer + subject` 显式映射到该主体；不会按 token 自动创建用户。示例：

```http
POST /api/external/admin/principals/users
Content-Type: application/json

{ "principalCode": "EMP_1008", "name": "员工 1008" }
```

用返回的 `id` 绑定身份并设置该员工自己的知识库权限：

```http
POST /api/external/admin/principals/{id}/identities
Content-Type: application/json

{ "issuer": "https://idp.example.com/", "subject": "00u123456", "reason": "企业账号映射" }
```

```http
PUT /api/external/admin/principals/{id}/knowledge-base-grants/config
Content-Type: application/json

[{ "knowledgeBaseId": 12, "permissionTags": ["team-a"], "reason": "员工业务范围" }]
```

实际知识库范围取 API Key 所属 Agent 主体与 JWT 映射员工主体的有效 grant 交集；任一方无授权都拒绝访问，PUBLIC 库也不能绕过交集。检索标签继续与 Key 全局范围、Agent grant 标签和员工 grant 标签取交集。管理员可用 `GET /api/external/admin/principals/{id}/identities` 查看绑定，并用 `POST /api/external/admin/principal-identities/{identityId}/revoke` 即时吊销。此版本的业务操作 Scope 仍由 Agent API Key 控制，员工动作级 Scope 与资料级 ACL 仍在后续 P2。

| Key 形态 | 行为 |
|---|---|
| V61 前的单库 Key | 迁移为主体 grant，行为保持原知识库范围；管理员可替换授权 |
| 主体有 grant | 只能访问授权清单中的知识库；请求未带 `knowledgeBaseId` 时默认选择首个授权库 |
| 主体无 grant 且原来未绑定知识库 | 仍只可访问公开知识库；问答、检索等单库操作需要显式传 `knowledgeBaseId` |
| 撤销全部 grant 的已绑定 Key | 立即失去原绑定库访问权，不回退到旧 `knowledge_base_id` 字段 |

### 2.4 配额

- 每把 Key 独立按**分钟滑动窗口**限流，默认 60 次/分钟，签发时可通过 `rateLimitPerMinute` 调整。
- 超限返回 **429** `KB-429-001`。计数在单实例内存内，重启只清空当前窗口。
- 建议调用方自己做退避重试（`Retry-After` 语义按 60 秒窗口估算）。

---

## 3. 统一响应与错误码

### 3.1 响应信封

所有 RESTful 接口都返回同一结构：

```json
{
  "success": true,
  "code": "OK",
  "message": "成功",
  "traceId": "fcaa8330e26e461a94a285b31343133e",
  "data": { }
}
```

| 字段 | 说明 |
|---|---|
| `success` | 业务是否成功 |
| `code` | 业务码，成功恒为 `OK` |
| `message` | 中文提示，失败时为可直接展示的原因 |
| `traceId` | 链路追踪 ID（本次 HTTP 调用），排障时提供给平台方。**不要**与 `/query` 返回里的 `data.traceId` 混淆：后者是问答 ID，与 `data.queryId` 同值 |
| `data` | 业务数据，失败时为 `null` |

### 3.2 错误码对照

| HTTP | code | 触发场景 | 处理建议 |
|---|---|---|---|
| 400 | `KB-400-002` | 参数校验失败，如 `question` 为空 | 修正入参，错误信息含字段名 |
| 400 | `KB-400-001` | 参数非法，如缺少 `knowledgeBaseId` | 补齐参数 |
| 401 | `KB-401-001` | 未携带 Key / Key 无效 / 已吊销 / 已过期 | 检查请求头与 Key 状态 |
| 403 | `KB-403-001` | scope 不足、越权访问其它知识库、通道已关闭 | 换 Key 或联系管理员开通 |
| 429 | `KB-429-001` | 超出每分钟配额 | 退避重试或申请提额 |
| 500 | `KB-500-001` / `KB-500-002` | 系统内部错误 / 数据库错误 | 携带 `traceId` 反馈 |
| 502 | `KB-502-001` | 下游 AI 服务不可用或返回异常 | 重试；持续出现需检查 ai-service |

真实错误样例：

```json
{
  "success": false,
  "code": "KB-401-001",
  "message": "缺少 API Key：请通过 X-API-Key 或 Authorization: Bearer 提供",
  "traceId": "e0a125cea7f3449ea4ee12556ca91d30",
  "data": null
}
```

```json
{
  "success": false,
  "code": "KB-403-001",
  "message": "该 API Key 未获准访问此知识库",
  "traceId": "ff8961c0ed90482ab6a853ff7d2dab73",
  "data": null
}
```

```json
{
  "success": false,
  "code": "KB-400-002",
  "message": "question: must not be blank",
  "traceId": "d25cedb3819a452dac52a38cd4d94dfc",
  "data": null
}
```

> 注意：查找不存在对象编码（如 `GET /cards/K_NOT_EXIST`）属于「查到 0 条」，返回 **200 + `data: {}`**，不是 404。

---

## 4. RESTful 接口

基础路径：`/api/v1/external`，全部支持跨域（CORS）。

**接口总览**（下表即控制器暴露的全部 REST 端点；`权限` 为所需 Scope，见 [2.2](#22-scope权限范围)）

| 方法 | 完整路径 | 权限 | 说明 | 章节 |
|---|---|---|---|---|
| GET | `/api/v1/external/bases` | LIST | 可见知识库列表 | [4.1](#41-get-bases--可见知识库列表) |
| POST | `/api/v1/external/query` | QUERY | 可追溯知识问答（答案 + 引用 + 链路），**核心接口** | [4.2](#42-post-query--可追溯知识问答核心接口) |
| POST | `/api/v1/external/search` | SEARCH | 只检索不生成答案（候选 + 重排 + 引用） | [4.3](#43-post-search--只检索不生成答案) |
| GET | `/api/v1/external/cards` | READ_OBJECT | 知识卡片清单 | [4.4](#44-get-cards--知识卡片清单) |
| GET | `/api/v1/external/cards/{code}` | READ_OBJECT | 知识卡片详情（含证据链） | [4.5](#45-get-cardscode--知识卡片详情含证据链) |
| GET | `/api/v1/external/claims` | READ_OBJECT | 断言清单 | [4.6](#46-get-claims--断言清单) |
| GET | `/api/v1/external/claims/{code}` | READ_OBJECT | 断言详情 | [4.7](#47-get-claimscode--断言详情) |
| GET | `/api/v1/external/evidence/{code}` | READ_OBJECT | 证据原文与出处（答案溯源） | [4.8](#48-get-evidencecode--证据原文与出处) |
| GET | `/api/v1/external/graph/{code}` | READ_OBJECT | 图谱邻域 | [4.9](#49-get-graphcode--图谱邻域) |
| GET | `/api/v1/external/queries/{queryId}` | READ_OBJECT | 问答链路回放 | [4.10](#410-get-queriesqueryid--问答链路回放) |
| GET | `/api/v1/external/overview` | READ_OBJECT | 知识库概览计数 | [4.11](#411-get-overview--知识库概览计数) |
| POST | `/api/v1/external/documents/text` | INGEST | 文本接入 | [4.12](#412-post-documentstext--文本接入需要-ingest) |
| POST | `/api/v1/external/memories/capture` | MEMORY_WRITE | Agent 记忆写入 | [4.13](#413-agent-记忆-api需要独立-memory-scope) |
| POST | `/api/v1/external/memories/recall` | MEMORY_READ | Agent 记忆召回 | [4.13](#413-agent-记忆-api需要独立-memory-scope) |
| DELETE | `/api/v1/external/memories/{memoryCode}` | MEMORY_WRITE | Agent 记忆删除 | [4.13](#413-agent-记忆-api需要独立-memory-scope) |
| POST | `/api/v1/external/kb/retrievals` | SEARCH；`includeAnswer=true` 还需 QUERY | 第三方检索协议；仅返回片段或按需附带答案 | [第三方检索接口](#第三方检索协议) |

> 另有两条非 REST 通道，路径不在这个基础路径下：MCP Server 是 `POST /api/mcp`（见 [第 5 节](#5-mcp-server智能体接入)），SSE 推送是 `GET /api/v1/external/stream/query` 与 `/api/v1/external/stream/events`（见 [第 6 节](#6-sse-推送)）。

SSE 问答流需要 `QUERY`，事件订阅需要 `LIST`。嵌入组件令牌由 `QUERY` Key 签发，`/embed/config` 需要 `READ_OBJECT`；短期嵌入令牌只继承其 API Key 的能力，不扩大权限。MCP 的 `tools/list` 与 `resources/list` / `resources/read` 分别按操作 scope 过滤和校验。

### 4.1 GET /bases — 可见知识库列表

**参数**：无。

**返回**：数组，元素字段 `id / baseCode / name / description / status / visibility / createdByName / defaultQuery`。

> `defaultQuery` 是知识库预置的示例问句（在「知识库管理」里随库一起设置），只作输入框提示与一键发起，不参与召回；留空时调用方退回自己的通用提示语。

```bash
curl -H 'X-API-Key: kos_ak_xxx' http://<host>:8080/api/v1/external/bases
```

### 4.2 POST /query — 可追溯知识问答（核心接口）

**请求体**

| 字段 | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `knowledgeBaseId` | Long | 否（未绑定 Key 必填） | — | 目标知识库 |
| `question` | String | **是** | 非空，≤ 4000 字符 | 问题原文 |
| `topK` | Integer | 否 | 1–50，默认 10 | 召回候选数量 |
| `permissionTags` | String[] | 否 | — | 调用方侧权限标签，平台仍会叠加自身 ACL |
| `answerFormat` | String | 否 | `default` / `markdown` / `text` / `html`，默认 `default` | 答案交付格式，取值见下 |
| `minScore` | Double | 否 | `0.0`–`1.0`，含端点 | 最低相关度分数：低于该分数的候选不参与答案生成（也用于 `/search` 过滤返回）。不传即不过滤——与历史完全一致 |
| `responseMode` | String | 否 | `minimal` / `basic` / `full`，默认 `full` | 返回详略，见下方「返回详略」 |

**`answerFormat` 取值**

| 取值 | 交付形状 |
|---|---|
| `default`（默认） | 模型生成的原文，引用由调用方按 `citations` 自行展示 |
| `markdown` | 答案正文 + Markdown 格式的引用原文章节 |
| `text` | 纯文本：去掉 Markdown 标记与证据章节，只有正文 |
| `html` | 渲染后的 HTML 片段，答案里直接带上引用原文 |

不传即 `default`；传了认不出来的取值同样退回 `default` 而不报错——一个老调用方或一次拼写错误不该让本来能问出答案的问题变成 400。想知道自己的取值有没有被接受，看响应里的 `answerFormat`（实际生效的那个）。

**返回 `data` 主要字段**

一次调用会把「这次问答是怎么来的」整条链路带回来，字段按用途分组（下表即当前实现的完整清单）。

| 字段 | 说明 |
|---|---|
| **标识与问题** | |
| `queryId` | 本次问答 ID，用于 `/queries/{queryId}` 回放 |
| `traceId` | 与 `queryId` **同值**；信封层的 `traceId` 是后端这次 HTTP 调用的 ID，两者别…14537 tokens truncated…ery`、`/search` 和 SSE `/stream/query`
同名同义；不传即不过滤（与历史一致）。

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{ "type": "text", "text": "{ …JSON 字符串… }" }],
    "structuredContent": { "queryId": "Q_xxx", "answer": "…", "answerFormat": "markdown", "citations": [ … ] },
    "isError": false
  }
}
```

### 5.4 资源

- `resources/list` 返回 `knowledge://kb/{id}`（`application/json`）。
- `resources/read` 支持：
  - `knowledge://kb/{id}` → 知识库概览
  - `knowledge://kb/{id}/cards/{code}` → 卡片详情
  - `knowledge://kb/{id}/claims/{code}` → 断言详情

### 5.5 MCP 错误码

| code | 含义 |
|---|---|
| `-32600` | 请求缺少 `method` |
| `-32601` | 不支持的方法 |
| `-32602` | 参数错误（含未知工具名、缺少必填参数） |
| `-32603` | 内部错误 |
| `-32001` | 权限/范围不足（scope 不足、越权知识库） |

协议层错误统一包装为 JSON-RPC `error`；凭据与限流问题仍走 HTTP 401/403/429。

### 5.6 MCP 客户端配置示例

```json
{
  "mcpServers": {
    "knowledge-os": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://<host>:8080/api/mcp"],
      "env": { "MCP_REMOTE_HEADERS": "{\"X-API-Key\":\"kos_ak_xxx\"}" }
    }
  }
}
```

---

## 6. SSE 推送

两个端点均需 API Key，且要求 `READ` scope；每把 Key **最多 5 路并发订阅**。

| 参数/配置 | 默认值 | 说明 |
|---|---|---|
| `sse-heartbeat-seconds` | 15 | 心跳间隔（`retry:` 重连建议时长） |
| `sse-max-minutes` | 30 | 单条连接最长存活时间，到点由服务端正常结束 |
| `answer-chunk-chars` | 40 | 流式答案切片字符数 |

### 6.1 GET /stream/query — 流式问答

**参数**：`question`（**必填**）、`knowledgeBaseId`（可选）、`topK`（可选）、`answerFormat`（可选，同 `/query`，默认 `default`）、`content`（可选，`stream` | `final`，默认 `stream`）、`minScore`（可选，`0.0`–`1.0`，与 `/query` 同义，越界返 400）。

**事件序列**

| 事件 | 载荷 | 说明 |
|---|---|---|
| `open` | `{knowledgeBaseId, question, topK, effectiveTopK, answerFormat, content, stages:[{key,label}], time}` | 握手帧，先告知将要执行哪些阶段；`content` 是服务端实际生效的交付方式（`final` = 答案整体在 `done` 里，`stream` = 逐字推 `delta`），`topK` 与 `effectiveTopK` 的口径同 `/query` |
| `stage` | `{"knowledgeBaseId":1,"stage":"REWRITE","time":1789970566684}` | 依次 REWRITE → RECALL → FUSION → RERANK → ANSWER |
| `delta` | 字符串（答案切片） | 逐片输出答案正文，按 `answerFormat` 交付（不传即默认格式）。切片内含换行时，按 SSE 规范拆成**多条 `data:` 字段**，客户端需用 `\n` 拼回 —— `EventSource` 会自动拼好，手写解析器也必须照做，否则换行会被当成帧结束、后半截被丢弃（表现为整段答案挤成一行）。当 `content=final` 时不推送 `delta` 事件，答案在 `done.answer` 里整体交付 —— 与阻塞式 `/api/v1/external/query` 拿到的内容同源 |
| `citation` | `{"citations":[ … ]}` | 引用一次性下发，字段同 `/query` 的 `citations` |
| `done` | `{queryId, knowledgeBaseId, question, answer, answerFormat, model, trace, confidence, durationMs, channels, strategies, reranked, questionTypeLabel, rewrittenQuery, conclusionSource, answerQualification, contextChars}` | 完成帧，`answerFormat` 为实际生效的交付格式；`conclusionSource` / `answerQualification` 与 `/query` 同构，客户端可以复用同一套渲染 |
| `error` | 错误信息字符串 | 异常结束 |

> 流式连接建立失败（SSE 不可用）时服务端会退化成「阻塞查询 + 事件回放」：事件序列不变，`done` 帧额外带上 `stages` / `elapsedMs` / `streamed:false`。客户端按 `done` 里的字段渲染即可，无需区分这两条路径。

真实帧（chunked 解码后）：

```
event:stage
data:{"knowledgeBaseId":1,"stage":"REWRITE","time":1789970566684}

event:stage
data:{"knowledgeBaseId":1,"stage":"RECALL","time":1789970566685}
```

```bash
curl -N -H 'X-API-Key: kos_ak_xxx' -H 'Accept: text/event-stream' \
  'http://<host>:8080/api/v1/external/stream/query?knowledgeBaseId=1&question=%E7%9F%A5%E8%AF%86%E5%BA%93%E6%9D%83%E9%99%90&topK=3&answerFormat=markdown'
```

浏览器端：

```javascript
const es = new EventSource(
  'http://<host>:8080/api/v1/external/stream/query?knowledgeBaseId=1&question=' +
    encodeURIComponent('知识库如何做权限控制') + '&answerFormat=markdown'
);
// 注意：浏览器 EventSource 不能自定义请求头，请使用 ?apiKey= 或经网关注入鉴权头
es.addEventListener('stage', e => console.log('阶段', JSON.parse(e.data).stage));
es.addEventListener('delta', e => process.stdout.write(e.data));
es.addEventListener('citation', e => console.log('引用', JSON.parse(e.data)));
es.addEventListener('done', e => { console.log('完成', JSON.parse(e.data)); es.close(); });
es.addEventListener('error', e => { console.error('失败', e.data); es.close(); });
```

### 6.2 GET /stream/events — 知识库事件订阅

| 参数 | 说明 |
|---|---|
| `knowledgeBaseId` | 目标知识库（可选） |
| `types` | 逗号分隔的事件类型白名单，留空表示全部 |
| `sinceEventId` | 从该事件 ID 之后重放（curl 类客户端使用） |
| `Last-Event-ID` | 同上，浏览器 `EventSource` 自动携带，用于断线续传 |
| `legacy=true` | 旧版计数轮询模式，推送 `ingestion` / `knowledge` / 心跳 |

建立连接后先收到 `open` 帧并附带 `retry`：

```
event:open
data:{"subscriptionId":"SUB-1789970566710-0","knowledgeBaseId":1,"types":"ALL","replayFrom":56,"maxMinutes":30}
retry:15000
```

随后按事件类型名推送（如 `event:KNOWLEDGE_OBJECT_CREATED`），每条带 `id:<事件ID>`，客户端重连时以 `Last-Event-ID` 续传；空闲期推送注释帧 `:heartbeat`。

**常用事件类型**

| 分类 | 类型 |
|---|---|
| 知识库 | `KNOWLEDGE_BASE_CREATED`、`KNOWLEDGE_BASE_ACCESS_UPDATED`、`KNOWLEDGE_BASE_ARCHIVED`、`KNOWLEDGE_BASE_PURGED` |
| 接入 | `INGESTION_DOCUMENT_RECEIVED`、`INGESTION_STAGE_CHANGED`、`INGESTION_COMPLETED`、`INGESTION_FAILED` |
| 知识对象 | `EXTRACTION_COMPLETED`、`KNOWLEDGE_OBJECT_CREATED`、`KNOWLEDGE_OBJECT_UPDATED`、`KNOWLEDGE_OBJECT_STATUS_CHANGED`、`KNOWLEDGE_OBJECT_SUPERSEDED`、`KNOWLEDGE_OBJECT_EXPIRED` |
| 治理 | `GOVERNANCE_DECISION`、`CONFLICT_RESOLVED`、`ASSERTION_APPROVED`、`ASSERTION_INVALIDATED`、`KNOWLEDGE_CONFLICT_DETECTED`、`KNOWLEDGE_JUDGMENT_COMPLETED`、`KNOWLEDGE_DERIVED_STALE` |
| 检索/权限 | `RETRIEVAL_EXECUTED`、`ACL_CHANGED` |
| 其它 | `SOURCE_SYNCED`、`CHUNK_CHANGED`、`KNOWLEDGE_CANDIDATE_CREATED`、`INDEX_REBUILD_REQUESTED`、`EVALUATION_COMPLETED`、`KNOWLEDGE_GAP_DETECTED` |

```bash
curl -N -H 'X-API-Key: kos_ak_xxx' \
  'http://<host>:8080/api/v1/external/stream/events?knowledgeBaseId=1&types=INGESTION_COMPLETED,KNOWLEDGE_OBJECT_CREATED'
```

---

## 7. 管理侧接口（控制台使用）

路径前缀 `/api/external/admin`，需**控制台登录会话**且角色为**系统管理员**或**知识库管理员**（这些接口不接受 API Key）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/services` | 三通道总览：端点清单、开关状态、活跃 Key 数、OpenAPI/Swagger 地址 |
| GET | `/keys` | Key 列表（不含明文） |
| POST | `/keys` | 签发 Key（明文仅此一次返回） |
| POST | `/keys/{id}/revoke` | 吊销 Key，立即失效 |
| GET | `/principals` | 查询共享主体及其 Key、有效授权数量 |
| POST | `/keys/{id}/principal` | 加入现有主体或创建并绑定新主体；操作写审计 |
| GET | `/keys/{id}/knowledge-base-grants/config` | 查询知识库授权期限、原因和逐库标签范围 |
| PUT | `/keys/{id}/knowledge-base-grants/config` | 原子替换知识库授权、期限和逐库标签范围 |
| GET | `/keys/{id}/knowledge-base-grants` | 查询主体当前有效的知识库授权 |
| PUT | `/keys/{id}/knowledge-base-grants` | 原子替换知识库授权；请求体为 ID 数组，`[]` 撤销全部 |
| GET | `/keys/{id}/knowledge-base-grants/config` | 查询授权期限、原因和状态 |
| PUT | `/keys/{id}/knowledge-base-grants/config` | 原子替换授权并按知识库设置期限与原因 |
| GET | `/audits?keyId=&limit=` | 调用审计（REST/MCP/SSE 合并），字段含 `channel / method / knowledgeBaseId / statusCode / durationMs / traceId / message / createdTime` |
| GET | `/mcp/manifest` | MCP 服务清单：协议版本、工具定义（含入参 schema） |
| GET | `/mcp/calls?limit=` | MCP 通道最近调用 |

---

## 8. 调用 Demo

### 8.1 Python（推荐：问答 + 引用 + 溯源）

```python
import requests

BASE = "http://<host>:8080/api/v1/external"
KEY = "kos_ak_xxx"
HEADERS = {"X-API-Key": KEY, "Content-Type": "application/json"}


def ask(question: str, kb: int = 1, top_k: int = 5, answer_format: str = "default"):
    """answer_format: default（不传即此项）/ markdown / text / html。"""
    r = requests.post(
        f"{BASE}/query",
        headers=HEADERS,
        json={
            "knowledgeBaseId": kb,
            "question": question,
            "topK": top_k,
            "answerFormat": answer_format,
        },
        timeout=120,
    )
    r.raise_for_status()
    body = r.json()
    if not body["success"]:
        raise RuntimeError(f"{body['code']} {body['message']} traceId={body['traceId']}")
    data = body["data"]
    # 实际生效的格式：传了认不出来的取值时这里会退回 default。
    if data.get("answerFormat") != answer_format:
        print(f"提示：请求的 {answer_format!r} 未生效，实际按 {data.get('answerFormat')!r} 交付")
    print("答案：", data["answer"])
    for c in data.get("citations", []):
        print(f"  [{c['index']}] {c['title']}（{c['sourceUri']}）score={c['score']}")
    return data["queryId"]


def replay(query_id: str):
    r = requests.get(f"{BASE}/queries/{query_id}", headers=HEADERS, timeout=30)
    return r.json()["data"]["traceJson"]


if __name__ == "__main__":
    qid = ask("知识库如何做权限控制")                          # 默认格式
    qid = ask("知识库如何做权限控制", answer_format="markdown")  # 正文 + 引用原文章节
    qid = ask("知识库如何做权限控制", answer_format="html")      # 可直接嵌入页面的 HTML 片段
    print("链路：", replay(qid)[:200])
```

带**重试与退避**的健壮版本（应对 429/502）：

```python
import time, requests
from requests.exceptions import HTTPError


def call(method, path, **kw):
    for attempt in range(4):
        r = requests.request(
            method,
            f"http://<host>:8080/api/v1/external{path}",
            headers={"X-API-Key": KEY, "Content-Type": "application/json"},
            timeout=120,
            **kw,
        )
        if r.status_code in (429, 502, 503):
            time.sleep(2**attempt)
            continue
        r.raise_for_status()
        return r.json()
    raise RuntimeError("重试后仍失败")
```

### 8.2 Java（JDK 11+ `HttpClient`）

```java
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class KnowledgeOsClient {
    private static final String BASE = "http://<host>:8080/api/v1/external";
    private static final String KEY  = "kos_ak_xxx";
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** answerFormat: default（不传即此项）/ markdown / text / html。 */
    public String query(long kb, String question, int topK, String answerFormat) throws Exception {
        String body = String.format(
                "{\"knowledgeBaseId\":%d,\"question\":%s,\"topK\":%d,\"answerFormat\":%s}",
                kb, quote(question), topK, quote(answerFormat == null ? "default" : answerFormat));
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/query"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("X-API-Key", KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("调用失败 " + resp.statusCode() + " " + resp.body());
        }
        return resp.body();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
```

### 8.3 Node.js / 浏览器（fetch）

```javascript
const BASE = 'http://<host>:8080/api/v1/external';
const KEY = 'kos_ak_xxx';

// 检索接口不生成答案正文，answerFormat 对它没有意义；要不同格式的答案请用 /query。
async function search(question, kb = 1, topK = 5) {
  const res = await fetch(`${BASE}/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': KEY },
    body: JSON.stringify({ knowledgeBaseId: kb, question, topK }),
  });
  const body = await res.json();
  if (!body.success) throw new Error(`${body.code} ${body.message} traceId=${body.traceId}`);
  return body.data.candidates.map(c => ({
    title: c.title,
    score: c.score,
    text: c.content.slice(0, 200),
  }));
}
```

### 8.4 文本接入（Python）

```python
r = requests.post(
    f"{BASE}/documents/text",
    headers=HEADERS,
    json={
        "knowledgeBaseId": 1,
        "title": "2026-09 客服话术更新",
        "text": "一、退款时效：……\n二、升级路径：……",
        "permissionTags": ["客服组"],
    },
    timeout=180,
)
print(r.json()["data"]["documentId"], r.json()["data"]["chunkCount"])
```

### 8.5 事件订阅（Python，`sseclient`）

```python
import json, requests

r = requests.get(
    "http://<host>:8080/api/v1/external/stream/events",
    headers={"X-API-Key": KEY, "Accept": "text/event-stream"},
    params={"knowledgeBaseId": 1, "types": "INGESTION_COMPLETED,KNOWLEDGE_OBJECT_CREATED"},
    stream=True,
    timeout=None,
)
for line in r.iter_lines(decode_unicode=True):
    if not line:
        continue
    if line.startswith("event:"):
        current = line[6:].strip()
    elif line.startswith("data:"):
        payload = json.loads(line[5:].strip())
        print(current, payload)
```

---

## 9. 服务端开关与部署配置

对外三通道可在部署侧整体关闭（关闭后调用返回 **403**「对外接口服务已关闭」）：

```yaml
knowledge:
  external:
    enabled: true                    # 总开关
    mcp-enabled: true                # MCP 通道
    sse-enabled: true                # SSE 通道
    sse-heartbeat-seconds: 15        # 心跳间隔（最小 3）
    sse-max-minutes: 30              # 单连接最长存活（1–240）
    answer-chunk-chars: 40           # 流式答案切片字符数（最小 8）
    answer-chunk-delay-millis: 40    # 切片间隔（0–500）
    default-rate-limit-per-minute: 60
```

对应环境变量：`EXTERNAL_API_ENABLED`、`EXTERNAL_MCP_ENABLED`、`EXTERNAL_SSE_ENABLED`、`EXTERNAL_SSE_HEARTBEAT_SECONDS`、`EXTERNAL_SSE_MAX_MINUTES`、`EXTERNAL_ANSWER_CHUNK_CHARS`、`EXTERNAL_ANSWER_CHUNK_DELAY_MILLIS`、`EXTERNAL_DEFAULT_RATE_LIMIT`。

事件总线（SSE 推送的数据来源）是追加写，需要配保留期，否则 `t_platform_event` 只增不减：

```yaml
knowledge:
  platform-event:
    retention:
      enabled: true           # 关闭后不再清理
      days: 30                # 保留天数；<= 0 表示永不清理（仅调试用）
      batch-size: 1000        # 单批删除条数，避免长事务
      interval-ms: 3600000    # 清理间隔
```

对应环境变量：`PLATFORM_EVENT_RETENTION_ENABLED`、`PLATFORM_EVENT_RETENTION_DAYS`、`PLATFORM_EVENT_RETENTION_BATCH_SIZE`、`PLATFORM_EVENT_RETENTION_INTERVAL_MS`。

控制台「接口服务 → 事件总线」可直接翻事件历史（按知识库 / 类型 / 关键字筛选，按事件号向后翻页），
并展示当前活跃订阅路数。历史事件与 SSE 下发走同一套可见性规则：管理员看全量，外部 Key 看脱敏后的 detail。

在线接口文档：`/v3/api-docs`（OpenAPI）、`/swagger-ui.html`（Swagger UI）。

---

## 10. 排错清单

| 现象 | 原因 | 处理 |
|---|---|---|
| 401「缺少 API Key」 | 请求头名写错或漏传 | 使用 `X-API-Key` 或 `Authorization: Bearer` |
| 401「API Key 无效、已吊销或已过期」 | Key 被吊销/过期/明文抄错 | 重新签发；注意明文只在签发响应中出现一次 |
| 403「未授权 INGEST 权限」 | Key 的 scope 不含 INGEST | 重新签发并声明 scopes |
| 403「已绑定知识库，不能访问其他知识库」 | 传了非绑定知识库 | 改用绑定库，或签发不绑定库的 Key |
| 403「对外接口服务已关闭」 | 部署侧 `enabled=false` | 联系平台方开启 |
| 429 | 超配额 | 退避重试或申请提额 |
| 502「AI 服务调用失败」 | ai-service 不可用或参数异常（如 `knowledgeBaseId` 传了 null） | 检查 ai-service；未绑定库的 Key 务必显式传 `knowledgeBaseId` |
| 200 但 `data` 为空对象 | 编码不存在或不在可见范围 | 核对编码与知识库 |
| 答案出现「证据不足」 | 命中答案闸门，属预期行为 | 补充知识或调整问题 |

排障时请提供响应中的 `traceId`，平台方可据此在调用审计与日志中定位单次请求。

---

## 11. 第三方检索协议接口：`POST /kb/retrievals`

给「外部 Agent 平台按标准协议来检索」的场景（NovaWork standard，协议原文见
[external_kb_search_protocol.md](./external_kb_search_protocol.md)）。它不是 `/query` 的变体，
而是**按对方的协议形状返回**：成功就是 `{chunks:[...]}`，失败就是 `{code,message}` + 状态码，
外面不包 `ApiResponse`。

**请求**

```http
POST /api/v1/external/kb/retrievals
Authorization: Bearer {apiKey}
X-Knowledge-Base-Id: 10
Content-Type: application/json

{ "question": "年假政策是什么？", "topK": 5, "includeAnswer": false }
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `question` | 是 | 问题原文，UTF-8 透传 |
| `topK` | 否 | 期望条数上限；超出 50 取 50（不算错误），不传取 10 |
| `includeAnswer` | 否 | 默认 `false`：只召回、跳过答案生成。`true` 时连本系统生成的答案一起给，耗时更长 |

知识库由 `X-Knowledge-Base-Id` 指定：纯数字按知识库 id，其余按库编码 `base_code`；
查不到返回 404 `KB_NOT_FOUND`。

**响应**

```json
{
  "chunks": [
    {
      "id": "EVD_cc91a0be181b4bf9bc3c9a5dde328ac8",
      "content": "本项目交付范围包括……（原文）",
      "score": 0.87,
      "source": "02_项目星河交付说明_V1.0.docx",
      "metadata": {
        "docName": "一、适用范围与核心内容",
        "page": 3,
        "objectType": "CONTENT_UNIT",
        "contentUnitId": "605",
        "evidenceCode": "EVD_cc91a0be181b4bf9bc3c9a5dde328ac8",
        "channel": "bm25",
        "scoreOrigin": "rerank",
        "rawScore": 0.87
      }
    }
  ],
  "answer": {
    "status": "ok",
    "queryId": "Q_874eb67fadc143b49cfcf2a4d9cfa270",
    "text": "星河项目交付范围包含……",
    "format": "markdown",
    "confidence": 0.82,
    "conclusionSource": "RETRIEVED_KNOWLEDGE",
    "citations": []
  }
}
```

`answer` 仅在 `includeAnswer=true` 时出现。`chunks[].id` 取证据码，同一片段跨请求恒定。

**与 `/query` 的关系**：授权、配额、知识库可见性、调用审计走的是同一套服务，没有第二条更宽松
的路径；差别只在响应形状与是否生成答案。
