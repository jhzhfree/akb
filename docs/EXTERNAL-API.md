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
| `traceId` | 与 `queryId` **同值**；信封层的 `traceId` 是后端这次 HTTP 调用的 ID，两者别混用 |
| `question` | 问题原文 |
| `questionType` / `questionTypeLabel` | 意图分类枚举与中文标签，如 `DEFAULT` / `通用问答` |
| `rewrittenQuery` / `rewrittenQueries` / `rewrite` | 查询改写结果：`rewrite = {variants, used, latencyMs}` |
| `router` / `routerNote` | 意图路由：`{questionType, strategies, reason, matchedRule, configuredStrategies}`；`routerNote` 是给排查用的一句话说明 |
| `conclusionSource` | 结论来源：`{type, label, graphPaths, evidenceKinds}`；`type` 取值 `RETRIEVED_KNOWLEDGE`（证据检索）/ `GRAPH_TRAVERSAL`（图谱遍历推导）/ `DERIVED_KNOWLEDGE`（系统推导），取优先级高的那种 |
| **检索过程** | |
| `channels` / `strategies` | 本次实际启用的通道，如 `["card","vector","bm25","claim"]` |
| `recall` | 各通道召回明细，形如 `{bm25:[…], vector:[…], card:[…], claim:[…]}`；每项含 `id / channel / title / score / metadata / contentChars`，**默认不含正文**（正文只在 `candidates` 里给一次） |
| `fusion` | 融合参数与样本：`{algorithm:"weighted_rrf", k, weights, qualitySignals, qualitySample, latencyMs}` |
| `reranker` | 重排器：`{name, enabled, topN, latencyMs}` |
| `reranked` | 本次是否真的跑了重排 |
| `candidates` | 融合 + 重排后的候选：`id / channel / title / content / score / rrfScore / rerankScore / quality / qualityBoost / chunkBoost / chunkSignals / fusionRank / rerankRank / rankDelta / channelRanks / scoreBreakdown`（`scoreBreakdown.whyMatched` 是人话版的「为什么命中」，`filteredBy` 如 `TOPK_CUTOFF` 说明它被什么卡过） |
| `selectedContext` | 真正装配进 Prompt 的候选，与 `citations` **同源同序**：超出字符预算被截断的不会进来，也就不会成为证据 |
| `context` | 拼装好的上下文原文，带 `[证据N]` 标号 |
| `topK` / `returned` / `minScore` / `effectiveTopK` | 数量口径，见下 |
| **答案与校验** | |
| `answer` | 答案正文，按 `answerFormat` 交付；未通过闸门时是弃答说明 |
| `answerFormat` | 实际生效的交付格式；请求不传时为 `default` |
| `citations` | 引用数组，字段见下 |
| `claims` | 参与生成的断言：`{claimId, subject, predicate, object, conditions, confidence, status}` |
| `answerVerification` | 证据闸门：`{gateStatus, policy, factualClaimCount, supportedClaimCount, citationCount, completenessScore, faithfulnessScore, unsupportedClaims, blockReasons, claims[]}` |
| `structuredAnswerVerification` | claim 级蕴含校验的**统计口径**：`gateStatus / claimCount / supportedCount / partialCount / contradictedCount / insufficientCount`；逐条明细不在这里，统一读 `answerQualification.claims`（两份是同一份判定，避免各发一遍口径分叉） |
| `answerQualification` | 最终合格性：`{gateStatus, policyVersion, evidenceGateStatus, structuredGateStatus, blockReasons, decisionReasons, originalAnswerHash, deliveredAnswerHash, claimCount, claims[]}`；`claims[]` 每条含 `claimId / statement / citationIndexes / evidenceIds / evidenceStatuses / entailmentScore / polarityConflict / decision / confidence / includedInFinalAnswer` |
| **统计** | |
| `contextChars` / `durationMs` / `confidence` | 上下文字符数、端到端耗时（ms）、答案置信度 |
| `model` / `tokenUsage` | 模型标识与 token 消耗（当前 `tokenUsage` 各字段恒为 `null`） |
| `trace` | 阶段耗时与统计：`rewriteLatencyMs / retrievalLatencyMs / fusionLatencyMs / rerankLatencyMs / llmLatencyMs / totalLatencyMs / recallCounts / retrievalProviders / channelLatencyMs / filters / candidateCount / citationCount / answerEvidenceGate / citationCompleteness / citationFaithfulness / structuredAnswerGate / claimEntailmentSupported / claimEntailmentContradicted / finalAnswerGate / answerPolicyVersion / generationIds / stages / queryPolicy` |

**数量口径**（最容易读错的三个数）

| 字段 | 含义 |
|---|---|
| `effectiveTopK` | 请求 `topK` 夹取后真正生效的召回上限（1–50，不传或小于 1 取 10），由后端写入 |
| `topK` | 服务端实际使用的召回上限，正常情况下与 `effectiveTopK` 同值 |
| `returned` | 融合、重排与 `minScore` 过滤后**实际给出的候选条数**，等于 `candidates.length`；它可能**大于** `topK`（原文段落补足会把够格的片段补回来），也可能小于（阈值筛掉） |

> 想知道「我要 N 条、回来几条」：比 `topK` 与 `returned`。
> 想知道「我的 N 有没有被上限夹过」：比请求值与 `effectiveTopK`——「要 200 条回来 50 条」是上限所致，不是库里只有 50 条。

**引用字段 `citations[]`**

`index / id / title / channel / source / score / sourceName / sourceUri / pageNo / contentUnitId / objectId / objectType / evidenceCode / evidenceHash / generationId / sourceVersion / excerpt`

- `index` 与答案正文里的 `[证据N]` 一一对应
- `objectType` 取值：`CONTENT_UNIT`（原文段落）/ `CARD`（知识卡片）/ `CLAIM`（断言）
- `evidenceCode` 用于 `GET /evidence/{code}` 取权威原文；`evidenceHash` 是内容指纹
- `excerpt` 是被引用的原文摘录：`markdown` / `html` 两种交付格式会把它带进答案正文

**示例**

```bash
curl -X POST http://<host>:8080/api/v1/external/query \
  -H 'X-API-Key: kos_ak_xxx' -H 'Content-Type: application/json' \
  -d '{"knowledgeBaseId":1,"question":"知识库如何做权限控制","topK":3,"answerFormat":"markdown"}'
```

相同的问题、只改 `answerFormat`，拿到的就是三种不同形状的答案：

| 请求 `answerFormat` | `data.answer` 的形状 |
|---|---|
| 不传 / `default` | 模型原文，引用另在 `citations` 里 |
| `markdown` | 答案正文 + `### 引用原文` 章节 |
| `text` | 纯文本，Markdown 标记与证据章节都已去掉 |
| `html` | `<p>` 正文 + `<section class="answer-citations">` 引用原文 |

真实响应（`answerFormat` 未传，故为 `default`；`recall` / `candidates` / `selectedContext` 只留一条，实际按召回条数下发）：

```json
{
  "success": true,
  "code": "OK",
  "message": "成功",
  "traceId": "bbe063cfe5ef40d485a166ae8d87624c",
  "data": {
    "queryId": "Q_6ad0c6eef9e24abc8f598c2e537c00cf",
    "traceId": "Q_6ad0c6eef9e24abc8f598c2e537c00cf",
    "question": "星河项目交付范围？",
    "questionType": "DEFAULT",
    "questionTypeLabel": "通用问答",
    "conclusionSource": {
      "type": "RETRIEVED_KNOWLEDGE",
      "label": "证据检索",
      "graphPaths": [],
      "evidenceKinds": ["RETRIEVED_KNOWLEDGE"]
    },
    "rewrittenQuery": "星河项目交付范围？",
    "routerNote": "通用问答｜card + vector + bm25 + claim｜未命中任何路由规则，按默认通道组合处理。",
    "channels": ["card", "vector", "bm25", "claim"],
    "contextChars": 1086,
    "durationMs": 1627,
    "reranked": true,
    "confidence": 0.704,
    "claims": [
      {
        "claimId": "CLM_686089ada63d44579066e20e0a25f58b",
        "subject": "一、适用范围与核心内容",
        "predicate": "DESCRIBES",
        "object": "一、适用范围与核心内容",
        "conditions": [],
        "confidence": null,
        "status": "UNCERTAIN"
      }
    ],
    "rewrittenQueries": ["星河项目交付范围？"],
    "rewrite": { "variants": ["星河项目交付范围？"], "used": "星河项目交付范围？", "latencyMs": 0 },
    "router": {
      "questionType": "DEFAULT",
      "strategies": ["card", "vector", "bm25", "claim"],
      "reason": "未命中任何路由规则，按默认通道组合处理。",
      "matchedRule": "default",
      "configuredStrategies": ["card", "vector", "bm25", "claim"]
    },
    "strategies": ["card", "vector", "bm25", "claim"],
    "recall": {
      "bm25": [
        {
          "id": "K_84c3703122084eb2a1d6d239bb7064b7",
          "channel": "bm25",
          "title": "项目星河交付说明 V1.0",
          "score": 24.440683,
          "metadata": {
            "object_type": "CARD",
            "source_name": "知识卡片",
            "status": "DRAFT",
            "generation_id": "vdata3",
            "evidence_code": "EVD_d1ad82d958a54375b794ea66a2b58934",
            "retrieval_provider": "elasticsearch"
          },
          "contentChars": 43
        }
      ]
    },
    "fusion": {
      "algorithm": "weighted_rrf",
      "k": 60,
      "weights": { "bm25": 1.15, "vector": 1.15, "card": 1.1, "claim": 1.1, "graph": 1.05 },
      "qualitySignals": { "authority": 0.25, "freshness": 0.2, "confidence": 0.1, "evidence": 0.1 },
      "qualitySample": [{ "id": "603", "authority": 0.5, "freshness": 0.6, "confidence": 0.5, "evidence": 0, "boost": 1.295 }],
      "latencyMs": 0
    },
    "candidates": [
      {
        "id": "605",
        "channel": "bm25",
        "title": "一、适用范围与核心内容",
        "content": "一、适用范围与核心内容\n\n星河项目建设企业搜索平台，交付范围包括知识采集、混合检索、问答溯源及权限审计。",
        "score": 0.75,
        "metadata": {
          "object_type": "CONTENT_UNIT",
          "document_id": 69,
          "content_unit_id": 605,
          "source_name": "02_项目星河交付说明_V1.0.docx",
          "heading_path": "一、适用范围与核心内容",
          "generation_id": "vdata3",
          "evidence_code": "EVD_8208b712f0344486b5669013fb896fd8"
        },
        "qualityBoost": 1.295,
        "chunkBoost": 1.5786,
        "chunkSignals": { "headingHit": 1, "coverage": 0.8571 },
        "preRerankScore": 23.420093948180998,
        "rerankScore": 0.75,
        "fusionRank": 3,
        "rerankRank": 1,
        "rankDelta": 2,
        "channelRanks": { "bm25": 5, "vector": 3 },
        "scoreBreakdown": {
          "channels": [
            { "channel": "bm25", "label": "BM25 全文", "rank": 5, "weight": 1.15, "rrfContribution": 0.017692 },
            { "channel": "vector", "label": "向量", "rank": 3, "weight": 1.15, "rrfContribution": 0.018254 }
          ],
          "chunkSignals": { "headingHit": 1, "coverage": 0.8571 },
          "finalScore": 0.75,
          "whyMatched": [
            "BM25 全文命中第 5 名（原始分 -，通道权重 1.15）",
            "向量命中第 3 名（原始分 -，通道权重 1.15）",
            "原文段落加成 ×1.58（标题命中 是，问题词覆盖 86%）"
          ],
          "filteredBy": []
        }
      }
    ],
    "topK": 3,
    "returned": 6,
    "minScore": null,
    "reranker": { "name": "lexical-cross-feature", "enabled": true, "topN": 0, "latencyMs": 1 },
    "selectedContext": [
      { "id": "605", "title": "一、适用范围与核心内容", "channel": "bm25", "score": 0.75, "rerankScore": 0.75, "objectType": "CONTENT_UNIT", "sourceName": "02_项目星河交付说明_V1.0.docx", "fusionRank": 3, "rerankRank": 1, "rankDelta": 2 }
    ],
    "context": "[证据1] 一、适用范围与核心内容（来源：02_项目星河交付说明_V1.0.docx）\n一、适用范围与核心内容\n\n星河项目建设企业搜索平台…",
    "citations": [
      {
        "index": 1,
        "id": "605",
        "title": "一、适用范围与核心内容",
        "channel": "bm25",
        "source": "bm25",
        "score": 0.75,
        "sourceName": "02_项目星河交付说明_V1.0.docx",
        "sourceUri": "upload://02_项目星河交付说明_V1.0.docx",
        "pageNo": null,
        "contentUnitId": 605,
        "objectId": "605",
        "objectType": "CONTENT_UNIT",
        "evidenceCode": "EVD_8208b712f0344486b5669013fb896fd8",
        "evidenceHash": "3d402fabc960cdea9d74be4b3d761593230444ba81fbc041aec453bf8ce6fe58",
        "generationId": "vdata3",
        "sourceVersion": null,
        "excerpt": "一、适用范围与核心内容 星河项目建设企业搜索平台，交付范围包括知识采集、混合检索、问答溯源及权限审计。计划2026年11月完成试运行。…"
      }
    ],
    "answer": "## 星河项目交付范围\n- 星河项目建设企业搜索平台，交付范围包括**知识采集、混合检索、问答溯源及权限审计** [证据1][证据2]。",
    "answerFormat": "default",
    "answerVerification": {
      "gateStatus": "PASS",
      "policy": "FACTUAL_CLAIM_REQUIRES_EXACT_VISIBLE_EVIDENCE",
      "factualClaimCount": 1,
      "supportedClaimCount": 1,
      "citationCount": 6,
      "completenessScore": 1,
      "faithfulnessScore": 1,
      "unsupportedClaims": [],
      "blockReasons": { "missingCitation": 0, "nonExactEvidence": 0, "lowFaithfulness": 0 },
      "claims": [
        {
          "text": "星河项目建设企业搜索平台，交付范围包括知识采集、混合检索、问答溯源及权限审计 [证据1][证据2]。",
          "claim": "星河项目建设企业搜索平台，交付范围包括知识采集、混合检索、问答溯源及权限审计 。",
          "citationIndexes": [1, 2],
          "validCitationIndexes": [1, 2],
          "exactEvidence": true,
          "faithfulnessScore": 1,
          "supported": true
        }
      ]
    },
    "structuredAnswerVerification": {
      "gateStatus": "PASS",
      "policy": "CLAIM_LEVEL_ENTAILMENT_AND_CONFLICT_AWARE",
      "claimCount": 1,
      "supportedCount": 1,
      "partialCount": 0,
      "contradictedCount": 0,
      "insufficientCount": 0
    },
    "answerQualification": {
      "gateStatus": "PASS",
      "policyVersion": "answer-policy-v1",
      "evidenceGateStatus": "PASS",
      "structuredGateStatus": "PASS",
      "blockReasons": { "missingCitation": 0, "nonExactEvidence": 0, "lowFaithfulness": 0 },
      "decisionReasons": [],
      "originalAnswerHash": "b373b8f1f50dc8b50c4d18d397b0b6fe0183ffa65b8ceb1aad75817391492796",
      "deliveredAnswerHash": "b373b8f1f50dc8b50c4d18d397b0b6fe0183ffa65b8ceb1aad75817391492796",
      "claimCount": 1,
      "claims": [
        {
          "claimId": "claim-1",
          "statement": "星河项目建设企业搜索平台，交付范围包括知识采集、混合检索、问答溯源及权限审计 。",
          "citationIndexes": [1, 2],
          "evidenceIds": ["EVD_8208b712f0344486b5669013fb896fd8", "EVD_d1ad82d958a54375b794ea66a2b58934"],
          "evidenceStatuses": ["SUPPORTED", "DRAFT"],
          "entailmentScore": 1,
          "polarityConflict": false,
          "decision": "SUPPORTED",
          "confidence": 1,
          "policyVersion": "answer-policy-v1",
          "includedInFinalAnswer": true
        }
      ]
    },
    "model": "openai",
    "tokenUsage": { "promptTokens": null, "completionTokens": null, "totalTokens": null },
    "trace": {
      "rewriteLatencyMs": 0,
      "questionType": "DEFAULT",
      "retrievalLatencyMs": 265,
      "fusionLatencyMs": 0,
      "rerankLatencyMs": 1,
      "llmLatencyMs": 1360,
      "totalLatencyMs": 1627,
      "recallCounts": { "bm25": 15, "vector": 20, "card": 5, "claim": 4 },
      "retrievalProviders": { "bm25": ["elasticsearch"], "vector": ["milvus"], "card": ["elasticsearch"], "claim": ["elasticsearch"] },
      "channelLatencyMs": { "bm25": 15, "vector": 220, "card": 12, "claim": 12 },
      "filters": {
        "status": "RETRIEVABLE_STATUSES_PRE_TOPK",
        "acl": "PERMISSION_TAGS_PRE_TOPK_ALL_CHANNELS",
        "dedup": "RRF_BY_OBJECT_ID",
        "conflict": "STATUS_POLICY"
      },
      "candidateCount": 6,
      "citationCount": 6,
      "answerEvidenceGate": "PASS",
      "citationCompleteness": 1,
      "citationFaithfulness": 1,
      "structuredAnswerGate": "PASS",
      "claimEntailmentSupported": 1,
      "claimEntailmentContradicted": 0,
      "finalAnswerGate": "PASS",
      "answerPolicyVersion": "answer-policy-v1",
      "generationIds": ["vdata3"],
      "stages": [
        { "key": "rewrite", "label": "查询改写", "latencyMs": 0, "inputCount": 1, "outputCount": 1 },
        { "key": "recall", "label": "多通道召回", "latencyMs": 265, "inputCount": 1, "outputCount": 44 },
        { "key": "llm", "label": "答案生成", "latencyMs": 1360, "inputCount": 6, "outputCount": 1, "detail": { "model": "openai" } }
      ],
      "queryPolicy": { "version": "query-policy-v1", "rrfK": 60, "contextMaxChars": 8000, "rerankEnabled": true }
    },
    "effectiveTopK": 3
  }
}
```

> 上面这份响应的 `topK=3` 而 `returned=6`：3 是本次生效的召回上限，6 是融合重排后真正给出的候选条数（原文段落补足会把够格的片段补回来）。二者不等是正常现象，不代表参数没生效。

> `answer` 出现「证据不足」类表述是答案闸门在起作用：知识不足以支撑结论时平台会拒答，而不是编造。

> `answerFormat` 回传的是实际生效的格式：请求里写 `answerFormat: "pdf"` 时，这里会是 `"default"`——认不出来的取值静默退回默认，调用方据此就知道自己的取值没被接受。

### 4.3 POST /search — 只检索不生成答案

适合已有自有 Prompt、只想取上下文的系统。入参与 `/query` 相同（`answerFormat` 可传但对返回内容无影响：这里没有答案正文），返回**不含 `answer`**。`minScore` 同样生效——返回的 `returned` 是过滤后实际给出的条数，与 `topK` 不一致时说明是阈值筛掉的。

返回字段（`/query` 的子集：**不含** `answer` / `answerFormat` / `answerVerification` / `answerQualification` / `context` / `selectedContext` / `trace`，因为没有生成这一步）：

`queryId / knowledgeBaseId / question / topK / effectiveTopK / returned / minScore / strategies / router / rewrite / recall / reranker / candidates / citations`

其中 `topK` / `effectiveTopK` / `returned` 的口径与 `/query` 完全一致（见 4.2「数量口径」）；`citations` 的字段也与 `/query` 相同，可直接按 `index` 与 `candidates` 对齐。

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
| `knowledge_query` | **knowledgeBaseId**、**question**、topK、minScore（可选） | 可追溯问答，返回答案、引用与 queryId |
| `knowledge_search` | **knowledgeBaseId**、**query**、topK、minScore（可选） | 只检索不生成答案 |
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
          "arguments":{"knowledgeBaseId":1,"question":"退款时效是多久","topK":5,"answerFormat":"markdown","minScore":0.6}
        }
      }'
```

`minScore` 是 `0`–`1` 的最低相关度阈值，与 REST `/query`、`/search` 和 SSE `/stream/query`
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
