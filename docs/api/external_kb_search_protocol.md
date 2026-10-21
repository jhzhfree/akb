# NovaWork 外部知识库检索协议（standard）

> 版本：v1.1（2026-10-21）
> 适用对象：为 NovaWork 提供外部知识库检索能力的服务系统（连接 API 协议类型选择 `standard` 时按本规范实现）
> 状态：**本文档为对接方实现依据**，NovaWork 侧已按本规范实现客户端（`internal/ai/kbretrieval/adapter.go`）

## 1. 协议总览

| 项目 | 约定 |
|---|---|
| 传输 | HTTP/1.1 或 HTTP/2，`POST` 方法，请求/响应均为 JSON（UTF-8） |
| 端点 | `POST {endpoint}`，其中 `{endpoint}` 为**用户在 NovaWork「连接 API」中配置的完整请求地址**（含路径，如 `https://kb.example.com/api/v1/knowledge-bases/retrievals`）。NovaWork 原样使用该地址发起请求，**不追加任何 URI 部分**——端点路径形态完全由外部系统自行设计，NovaWork 不做约束 |
| 认证 | 每请求携带 `Authorization: Bearer {apiKey}`，apiKey 在 NovaWork「连接 API」配置中由调用方提供 |
| 无状态 | 检索接口必须无状态、可幂等重试；不支持分页（结果条数由 `topK` 控制） |
| 超时 | NovaWork 侧默认 10s（可按库/组覆写 1-60s）；服务端应在 5s 内完成检索 |

## 2. 请求

```http
POST /api/v1/knowledge-bases/retrievals HTTP/1.1
Host: kb.example.com
Authorization: Bearer apikey-demo-0001
X-Knowledge-Base-Id: 101
Content-Type: application/json; charset=utf-8

{
  "question": "年假政策是什么？",
  "topK": 5
}
```

> 本例中用户配置的 endpoint 为 `https://kb.example.com/api/v1/knowledge-bases/retrievals`（完整地址，NovaWork 原样请求）。

### 请求头

| Header | 必填 | 说明 |
|---|---|---|
| `Authorization` | 是 | `Bearer {apiKey}`，apiKey 在 NovaWork「连接 API」配置中由调用方提供 |
| `X-Knowledge-Base-Id` | 是 | 目标知识库的外部 id（原样字符串，NovaWork 侧来自知识库配置的「外部知识库 id」；数字 id 传十进制文本，如 `101`） |
| `Content-Type` | 是 | `application/json; charset=utf-8` |

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `question` | string | 是 | 用户问题原文（UTF-8，可能包含中文，不截断）；服务端自行负责 query 改写/向量化 |
| `topK` | number(int) | 是 | 期望召回条数上限；服务端**必须**返回 ≤ topK 条 |

## 3. 成功响应

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "chunks": [
    {
      "id": "doc-12-chunk-37",
      "content": "根据公司休假制度，入职满一年的员工年假为 5 天，每满一年增加 1 天，上限 15 天。",
      "score": 0.92,
      "source": "员工手册/休假制度.pdf",
      "metadata": {
        "docName": "休假制度",
        "page": 3
      }
    }
  ]
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `chunks` | array | 是 | 召回片段列表；无命中时返回空数组 `[]`（**空结果是正常响应，不是错误**） |
| `chunks[].id` | string | 否 | chunk 唯一标识；**必须跨请求稳定**（同一片段多次召回返回相同 id，NovaWork 用它做多库融合去重）。缺省时 NovaWork 以内容 SM3 摘要（国密标准）兜底去重 |
| `chunks[].content` | string | 是 | 召回文本（UTF-8，非空）；长片段可由 NovaWork 侧按输出预算截断，服务端无需预处理 |
| `chunks[].score` | number(float) | 是 | 相关度得分，**范围 [0,1]，语义为「越大越相关」**；跨请求可比较（NovaWork 侧按阈值过滤与 RRF 融合依赖此语义）。若内部为距离型得分（越小越好），服务端必须先归一转换为相似度 |
| `chunks[].source` | string | 否 | 来源标识（文档名/路径/链接），用于展示与溯源 |
| `chunks[].metadata` | object | 否 | 附加键值对，NovaWork 原样透传（不解释、不修改）；值必须为 JSON 标量或嵌套对象 |

### 排序与条数约束

- `chunks` 按 `score` **降序**排列（服务端排序）
- 条数 ≤ 请求 `topK`
- 重复内容片段服务端自行去重（同一文档同一段落只返回一次）

## 4. 错误响应

HTTP 状态码承载错误语义，body 为结构化 JSON：

```http
HTTP/1.1 404 Not Found
Content-Type: application/json; charset=utf-8

{
  "code": "KB_NOT_FOUND",
  "message": "知识库不存在: 101"
}
```

| 状态码 | 场景 | 建议 code |
|---|---|---|
| 400 | 参数非法（缺字段/类型错/topK ≤ 0/缺 `X-Knowledge-Base-Id` 头） | `INVALID_ARGUMENT` |
| 401 | apiKey 无效或未携带 | `UNAUTHORIZED` |
| 403 | key 有效但无该知识库访问权限 | `FORBIDDEN` |
| 404 | `X-Knowledge-Base-Id` 对应的知识库不存在 | `KB_NOT_FOUND` |
| 500 | 服务内部错误 | `INTERNAL` |
| 503 | 检索依赖不可用（向量库故障等） | `UNAVAILABLE` |

- `code`：机器可读错误码（大写下划线，上表外可自定义）
- `message`：人可读描述（NovaWork 会原文记录到检索告警）
- 允许仅 5xx 时不带 JSON body，但建议始终返回上述结构

### NovaWork 侧行为（对接方知悉）

- 单库失败（超时/4xx/5xx/协议错误）**不会中断整体检索**：该库降级为空结果，错误信息记录到本次检索的 warnings 提示
- 全部库均失败时，本次检索整体报错提示
- NovaWork 忽略请求/响应中的未知字段（前向兼容）；新增必填字段必须在版本升级中协商

## 5. 兼容性与扩展

- 协议演进只做**增量可选字段**，不删改现有字段语义
- 服务端不支持的可选能力（如未来的 rerank 下推）以忽略处理，不得报错
- 请求 `topK` 上限由服务端自行限制（超出取内部上限），不视为错误

## 6. 对接自检清单

- [ ] 请求到达用户配置的完整 endpoint 地址（NovaWork 原样使用，不追加路径——路径由服务方与配置方约定）
- [ ] `Authorization: Bearer {apiKey}` 头解析正确
- [ ] `X-Knowledge-Base-Id` 头读取正确（数字 id 传十进制文本）
- [ ] `question` 中文 UTF-8 透传无损
- [ ] `topK` 截断生效
- [ ] 空结果返回 200 + `{"chunks":[]}`
- [ ] `score` ∈ [0,1] 且越大越相关
- [ ] 同一片片跨请求 id 稳定
- [ ] 401/403/404/500 均有结构化错误 body

## 7. 我方实现（本系统侧）

> 本节由本系统补充：说明上文协议是如何被满足的，以及协议之外的扩展。
> 对应接口：`POST /api/v1/external/kb/retrievals`

| 协议要求 | 实现 |
|---|---|
| `Authorization: Bearer {apiKey}` | 已支持，与 `X-API-Key` 等价 |
| `X-Knowledge-Base-Id` | 纯数字按知识库 id，其余按库编码 `base_code` 解析；查不到返回 404 `KB_NOT_FOUND` |
| `question` / `topK` | `topK` 超出 50 取 50（协议允许不报错），≤ 0 返回 400 |
| `chunks[].id` | 证据码 `EVD_…`，同一片段跨请求恒定（可被用于多库融合去重） |
| `chunks[].content` | 候选原文，非空 |
| `chunks[].score` | 归一化到 [0,1]，见下 |
| `chunks[].source` | 来源文档名 |
| `chunks[].metadata` | docName / sourceUri / page / objectType / contentUnitId / evidenceCode / channel / scoreOrigin / rawScore |
| 空结果 | 200 + `{"chunks":[]}` |
| 错误 | 400 `INVALID_ARGUMENT` / 401 `UNAUTHORIZED` / 403 `FORBIDDEN` / 404 `KB_NOT_FOUND` / 429 `RESOURCE_EXHAUSTED` / 500 `INTERNAL`，body 均为 `{code, message}` |

**协议外扩展**（NovaWork 忽略未知字段，不影响对接）：

| 字段 | 说明 |
|---|---|
| `includeAnswer`（请求体） | 默认 `false`：只召回、跳过答案生成（实测 0.4s）。置 `true` 走完整链路，并在响应 `answer` 上返回本系统生成的答案（实测 6.5s） |
| `answer`（响应体） | `status` / `queryId` / `text` / `format` / `confidence` / `conclusionSource` / `citations[]` |

**超时建议**：只召回（默认）远在 5s 内。若开启 `includeAnswer`，请把该库在 NovaWork 侧的超时覆写为 30s 以上——
撞上超时在协议侧是「整库降级为空结果」，比慢一点严重得多。

**score 归一化**：本系统的分数有两种来源。启用重排时 `rerankScore` 天然落在 [0,1]（启发式重排是加权和、
BGE cross-encoder 是 sigmoid 输出），直接采用；未启用重排时是上界不明的融合分，压缩为 `s/(s+1)`——
只依赖分数自身，因此同一分数在任何请求里都得到同一个值（满足协议「跨请求可比较」）。
`metadata.scoreOrigin` 指明本次走的是哪条路（`rerank` / `compressed` / `none`）。
