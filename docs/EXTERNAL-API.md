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

三者的能力边界完全一致：**外部能做的事，控制台都能做；控制台能做的，外部不会多一件**。

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
  -d '{"knowledgeBaseId": 1, "question": "知识库如何做权限控制", "topK": 3}'
```

返回的 `data.answer` 是答案，`data.citations` 是逐条引用，`data.queryId` 可用于链路回放（见 §4.10）。

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
| `READ` | 检索与问答、读取知识对象 | 除 `/documents/text` 外的全部接口 |
| `INGEST` | 文档/文本接入 | `POST /documents/text`、MCP 工具 `ingest_text` |
| `WRITE` | 知识对象治理 | 预留，具备 `WRITE` 即隐含 READ 与 INGEST |

不传 `scopes` 时默认为 `READ`。无对应 scope 调用会返回 403。

### 2.3 知识库归属规则

| Key 形态 | 行为 |
|---|---|
| 绑定了知识库 | 只能访问该知识库；请求里传别的 `knowledgeBaseId` → **403** |
| 未绑定知识库 | 必须显式传 `knowledgeBaseId`，且该知识库需满足对外访问规则；未传参数会一直透传到下游并报错，**调用方务必显式传值** |

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
| `traceId` | 链路追踪 ID，排障时提供给平台方 |
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
  "message": "该 API Key 已绑定知识库，不能访问其他知识库",
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

### 4.1 GET /bases — 可见知识库列表

**参数**：无。

**返回**：数组，元素字段 `id / baseCode / name / description / status / visibility / createdByName`。

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

**返回 `data` 主要字段**

| 字段 | 说明 |
|---|---|
| `queryId` | 本次问答 ID，用于 `/queries/{queryId}` 回放 |
| `answer` | 生成的答案文本（未通过证据校验时会给出拒答说明） |
| `citations` | 引用数组：`index / title / channel / score / sourceName / sourceUri / contentUnitId / objectType / evidenceHash / pageNo` |
| `candidates` | 融合后的候选片段：`id / channel / content / score / rrfScore / rerankScore / quality / scoreBreakdown …` |
| `strategies` | 实际使用的检索策略，如 `["card","bm25","vector"]` |
| `router` | 意图路由结果（问题类型、命中规则、原因） |
| `rewrite` | 查询改写结果 |
| `recall` | 各通道召回明细与耗时 |
| `fusion` / `reranker` | 融合与重排明细 |
| `confidence` | 答案置信度 |
| `answerQualification` | 答案合格性校验（证据完整性、忠实度等闸门） |
| `model` | 实际使用的模型标识 |
| `tokenUsage` | token 消耗 |
| `trace` | 各阶段耗时与统计（`totalLatencyMs`、`stages` 等） |
| `traceId` | 链路 ID |

**示例**

```bash
curl -X POST http://<host>:8080/api/v1/external/query \
  -H 'X-API-Key: kos_ak_xxx' -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":1,"question":"知识库如何做权限控制","topK":3}'
```

```json
{
  "success": true,
  "code": "OK",
  "traceId": "fcaa8330e26e461a94a285b31343133e",
  "data": {
    "queryId": "Q_c512ffdfce7e498d9c50795a655c3c43",
    "question": "知识库如何做权限控制",
    "questionType": "PROCEDURE",
    "questionTypeLabel": "流程/操作类",
    "strategies": ["card", "bm25", "vector"],
    "contextChars": 1842,
    "durationMs": 3120,
    "confidence": 0.42,
    "answer": "现有知识不足以形成全部可验证的结论；未通过证据完整性与忠实度校验的内容已阻止输出。",
    "citations": [
      {
        "index": 1,
        "id": "71",
        "title": "sample.txt",
        "channel": "bm25",
        "score": 0.365,
        "sourceName": "sample.txt",
        "sourceUri": "upload://sample.txt",
        "pageNo": null,
        "contentUnitId": 71,
        "objectType": "CONTENT_UNIT",
        "evidenceHash": "3bd5e095bb09e3afe139b9f6f83566bc…",
        "sourceVersion": 1
      }
    ],
    "model": "openai",
    "trace": { "totalLatencyMs": 3120, "candidateCount": 35, "citationCount": 3 }
  }
}
```

> `answer` 出现「证据不足」类表述是答案闸门在起作用：知识不足以支撑结论时平台会拒答，而不是编造。

### 4.3 POST /search — 只检索不生成答案

适合已有自有 Prompt、只想取上下文的系统。入参与 `/query` 相同，返回**不含 `answer`**：

`queryId / knowledgeBaseId / question / strategies / router / rewrite / recall / reranker / candidates / citations`

```bash
curl -X POST http://<host>:8080/api/v1/external/search \
  -H 'X-API-Key: kos_ak_xxx' -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":1,"question":"算力分配规范","topK":5}'
```

### 4.4 GET /cards — 知识卡片清单

**参数**：`knowledgeBaseId`（可选）。

**返回数组元素**：`id / knowledgeCode / knowledgeBaseId / type / title / summary / content / confidence / status / permissionTags / version`。

```bash
curl -H 'X-API-Key: kos_ak_xxx' \
  'http://<host>:8080/api/v1/external/cards?knowledgeBaseId=1'
```

### 4.5 GET /cards/{code} — 知识卡片详情（含证据链）

**路径参数**：`code` = 知识卡片编码（`knowledgeCode`）。
**查询参数**：`knowledgeBaseId`（可选）。

### 4.6 GET /claims — 断言清单

**返回数组元素**：`id / claimCode / knowledgeBaseId / subject / predicate / objectValue / conditionJson / qualifierJson / polarity / confidence / certainty / status / permissionTags / validFrom / validTo / version`。

### 4.7 GET /claims/{code} — 断言详情

**路径参数**：`code` = 断言编码（`claimCode`）。

### 4.8 GET /evidence/{code} — 证据原文与出处

**路径参数**：`code` = 证据编码。
返回该证据的原文内容、来源文档、页码/位置与哈希，用于答案溯源展示。

### 4.9 GET /graph/{code} — 图谱邻域

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `code` | 是 | — | 实体或知识对象编码 |
| `knowledgeBaseId` | 否 | Key 绑定值 | 目标知识库 |
| `depth` | 否 | 2 | 跳数 |
| `limit` | 否 | 50 | 返回上限 |

```bash
curl -H 'X-API-Key: kos_ak_xxx' \
  'http://<host>:8080/api/v1/external/graph/K_263631…?knowledgeBaseId=1&depth=2&limit=50'
```

### 4.10 GET /queries/{queryId} — 问答链路回放

按 `queryId` 取回完整检索与生成轨迹，用于结果解释与审计。

```bash
curl -H 'X-API-Key: kos_ak_xxx' \
  http://<host>:8080/api/v1/external/queries/Q_c512ffdfce7e498d9c50795a655c3c43
```

```json
{
  "success": true,
  "code": "OK",
  "data": {
    "traceId": "cb418db646674b25b038f0406a3c45ea",
    "traceJson": "{\"stages\":[{\"key\":\"rewrite\",\"label\":\"查询改写\",…},{\"key\":\"route\",\"label\":\"意图路由\",…},{\"key\":\"recall\",\"label\":\"多通道召回\",…},{\"key\":\"acl_filter\",\"label\":\"ACL 与状态过滤\",…}]}"
  }
}
```

`traceJson` 为字符串化的阶段明细，便于前端直接渲染"查询改写 → 意图路由 → 多通道召回 → ACL 过滤 → 融合 → 重排 → 生成"全链路。

### 4.11 GET /overview — 知识库概览计数

**参数**：`knowledgeBaseId`（可选）。

```json
{
  "success": true,
  "code": "OK",
  "data": {
    "knowledgeBaseId": 1,
    "sourceCount": 15,
    "documentCount": 19,
    "chunkCount": 51,
    "cardCount": 81,
    "effectiveCardCount": 0,
    "claimCount": 80,
    "pendingReviewCount": 183,
    "openConflictCount": 4,
    "assessmentCount": 20
  }
}
```

### 4.12 POST /documents/text — 文本接入（需要 INGEST）

把一段文本送入与控制台上传**完全相同的解析与摄入管道**（分块 → 抽取 → 生成卡片/断言 → 入索引）。

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `knowledgeBaseId` | Long | 否（未绑定 Key 必填） | 目标知识库 |
| `text` | String | **是** | 正文内容 |
| `title` | String | 否 | 标题，作为来源名；缺省 `external-ingest`，并自动补 `.txt` 后缀 |
| `permissionTags` | String[] | 否 | 权限标签，内部以逗号拼接 |

**返回 `data`**

`runId / runCode / sourceId / documentId / filename / chunkCount / chunkRevision / status / index / standardIndex / standard / knowledge`

```bash
curl -X POST http://<host>:8080/api/v1/external/documents/text \
  -H 'X-API-Key: kos_ak_xxx' -H 'Content-Type: application/json' \
  -d '{
        "knowledgeBaseId": 1,
        "title": "2026-09 客服话术更新",
        "text": "一、退款时效：……\n二、升级路径：……",
        "permissionTags": ["客服组"]
      }'
```

```json
{
  "success": true,
  "code": "OK",
  "data": {
    "runId": 512,
    "runCode": "RUN_xxx",
    "sourceId": 233,
    "documentId": 466,
    "filename": "2026-09 客服话术更新.txt",
    "chunkCount": 6,
    "chunkRevision": 1,
    "status": "READY",
    "knowledge": { "cards": 2, "claims": 3 }
  }
}
```

摄入是异步管道中的长任务，返回 `status: "READY"` 表示已入库；后续可在控制台任务中心或事件订阅中跟踪处理进度。

---

## 5. MCP Server（智能体接入）

平台在本通道扮演 **MCP Server**，外部智能体 / IDE / Agent 作为 MCP 客户端接入。

- 端点：`POST /api/mcp`
- 协议：JSON-RPC 2.0，`protocolVersion = 2024-11-05`，`serverInfo = knowledge-os / 1.2.0`
- 鉴权：与 REST 相同的 API Key（`X-API-Key` 或 `Authorization: Bearer`）
- 方法：`initialize`、`tools/list`、`tools/call`、`resources/list`、`resources/read`、`ping`

### 5.1 初始化

```bash
curl -X POST http://<host>:8080/api/mcp \
  -H 'X-API-Key: kos_ak_xxx' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": { "listChanged": false }, "resources": { "subscribe": false, "listChanged": false } },
    "serverInfo": { "name": "knowledge-os", "version": "1.2.0" },
    "instructions": "用 knowledge_query 提问会返回带引用的答案；knowledge_search 只做检索。所有工具都要求 knowledgeBaseId 在 API Key 授权范围内。"
  }
}
```

### 5.2 工具清单

| 工具 | 入参（加粗为必填） | 说明 |
|---|---|---|
| `knowledge_query` | **knowledgeBaseId**、**question**、topK | 可追溯问答，返回答案、引用与 queryId |
| `knowledge_search` | **knowledgeBaseId**、**query**、topK | 只检索不生成答案 |
| `list_knowledge_bases` | — | 列出当前 Key 可见知识库 |
| `list_knowledge_cards` | **knowledgeBaseId** | 知识卡片清单 |
| `get_knowledge_card` | **knowledgeBaseId**、**code** | 卡片详情与证据链 |
| `list_claims` | **knowledgeBaseId** | 断言清单 |
| `get_claim` | **knowledgeBaseId**、**code** | 断言详情 |
| `get_evidence` | **knowledgeBaseId**、**evidenceCode** | 证据原文与出处 |
| `graph_neighbors` | **knowledgeBaseId**、**code**、depth、limit | 图谱邻域 |
| `ingest_text` | **knowledgeBaseId**、**text**、title、permissionTags | 文本接入（需 INGEST） |

### 5.3 调用工具

```bash
curl -X POST http://<host>:8080/api/mcp \
  -H 'X-API-Key: kos_ak_xxx' -H 'Content-Type: application/json' \
  -d '{
        "jsonrpc":"2.0","id":2,"method":"tools/call",
        "params":{
          "name":"knowledge_query",
          "arguments":{"knowledgeBaseId":1,"question":"退款时效是多久","topK":5}
        }
      }'
```

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{ "type": "text", "text": "{ …JSON 字符串… }" }],
    "structuredContent": { "queryId": "Q_xxx", "answer": "…", "citations": [ … ] },
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

**参数**：`question`（**必填**）、`knowledgeBaseId`（可选）、`topK`（可选）。

**事件序列**

| 事件 | 载荷 | 说明 |
|---|---|---|
| `stage` | `{"knowledgeBaseId":1,"stage":"REWRITE","time":1789970566684}` | 依次 REWRITE → RECALL → FUSION → RERANK → ANSWER |
| `delta` | 字符串（答案切片） | 逐片输出答案正文 |
| `citation` | `{"citations":[ … ]}` | 引用一次性下发 |
| `done` | `{"queryId","knowledgeBaseId","question","answer","model","trace"}` | 完成帧 |
| `error` | 错误信息字符串 | 异常结束 |

真实帧（chunked 解码后）：

```
event:stage
data:{"knowledgeBaseId":1,"stage":"REWRITE","time":1789970566684}

event:stage
data:{"knowledgeBaseId":1,"stage":"RECALL","time":1789970566685}
```

```bash
curl -N -H 'X-API-Key: kos_ak_xxx' -H 'Accept: text/event-stream' \
  'http://<host>:8080/api/v1/external/stream/query?knowledgeBaseId=1&question=%E7%9F%A5%E8%AF%86%E5%BA%93%E6%9D%83%E9%99%90&topK=3'
```

浏览器端：

```javascript
const es = new EventSource(
  'http://<host>:8080/api/v1/external/stream/query?knowledgeBaseId=1&question=' +
    encodeURIComponent('知识库如何做权限控制')
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


def ask(question: str, kb: int = 1, top_k: int = 5):
    r = requests.post(
        f"{BASE}/query", headers=HEADERS, json={"knowledgeBaseId": kb, "question": question, "topK": top_k}, timeout=120
    )
    r.raise_for_status()
    body = r.json()
    if not body["success"]:
        raise RuntimeError(f"{body['code']} {body['message']} traceId={body['traceId']}")
    data = body["data"]
    print("答案：", data["answer"])
    for c in data.get("citations", []):
        print(f"  [{c['index']}] {c['title']}（{c['sourceUri']}）score={c['score']}")
    return data["queryId"]


def replay(query_id: str):
    r = requests.get(f"{BASE}/queries/{query_id}", headers=HEADERS, timeout=30)
    return r.json()["data"]["traceJson"]


if __name__ == "__main__":
    qid = ask("知识库如何做权限控制")
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

    public String query(long kb, String question, int topK) throws Exception {
        String body = String.format(
                "{\"knowledgeBaseId\":%d,\"question\":%s,\"topK\":%d}",
                kb, quote(question), topK);
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
