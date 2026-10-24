# Octop 集成指南

AKB 可作为 Octop 的企业知识来源，通过 AKB MCP Server 让 Agent 使用受控的知识检索工具。AKB 保留知识内容、证据、审核状态与检索权限；Octop 保存连接配置并把授权的 MCP 工具提供给 Agent。

## 连接参数

| 参数 | 值 |
| --- | --- |
| Transport | MCP Streamable HTTP, stateless JSON response mode |
| MCP URL | `https://<AKB 域名>/api/mcp` |
| Header | `X-API-Key: <AKB API Key>` |
| 建议权限 | 按需 `SEARCH`、`QUERY`；需要列出知识库时增加 `LIST`，读取卡片/证据时增加 `READ_OBJECT` |
| MCP 工具 | `knowledge_search`、`knowledge_query`、`list_knowledge_bases` 等 |

建议为每个知识空间创建绑定的 API Key，并按 Agent 使用方式授予细分 scope：仅生成答案授予 `QUERY`，仅检索候选授予 `SEARCH`，需要展示知识库目录授予 `LIST`，读取知识卡片或证据详情再授予 `READ_OBJECT`。MCP `tools/list` 会按授权过滤工具，服务端也会在每次调用时复核 scope。旧 Key 的 `READ` 仍兼容为上述四项读取权限。绑定 Key 会限制可访问的知识库。旧调用可继续传 `knowledgeBaseId`；新调用可用稳定的 `knowledgeBaseCode`（即知识库 `baseCode`），绑定 Key 也可省略两者。AKB 只会在该 Key 可见的知识库中解析编码，再执行原有绑定、scope 与 ACL 检查；同时传入不匹配的 ID 和编码会被拒绝。API Key 不应写入 Agent Prompt、业务文档或代码仓库。

在 Octop 的 **Agent → Connectors → 自定义 MCP** 中选择 **添加 AKB 知识库**，填写 MCP URL 与 `X-API-Key`，保存后执行探测。将该连接加入目标 Agent 的 MCP 配置，再由 Agent 调用 `knowledge_search` 获取段落、候选与引用，或调用 `knowledge_query` 获取带引用答案。

数字员工使用时，还需在 Octop 将连接加入目标企业场景，并通过数字员工的 `akb-knowledge-sources` 接口绑定 `mcp_server` 与 `knowledge_base_code`。Octop 会在计划快照和审批时复核绑定；AKB Key 仍是远端权限边界。

## 工具参数

`knowledge_search` 的关键参数：

```json
{
  "knowledgeBaseCode": "HR-POLICY",
  "query": "员工年假政策",
  "topK": 5,
  "permissionTags": ["hr"],
  "minScore": 0.65
}
```

`knowledge_query` 使用 `question` 代替 `query`，并可指定 `answerFormat`。`knowledgeBaseId` 与 `knowledgeBaseCode` 至少选一个（绑定 Key 可都省略）。`permissionTags` 是检索过滤条件，不能代替知识库 ACL。返回中的 `queryId` 和 `citations` 可用于追踪与展示知识来源；MCP 查询也会在 AKB 记录为 MCP 来源。

## 联调检查

1. MCP 探测能列出 `knowledge_search` 和 `knowledge_query`。
2. 使用 Key 绑定的知识库检索成功，响应含 `queryId` 和引用信息。
3. 尝试查询未授权知识库时，AKB 拒绝请求。
4. 查询日志能区分 MCP 来源，并保留对应 API Key 与知识库信息。
5. 在 Octop 中移除该 MCP 后，目标 Agent 不再收到其工具。

AKB 的版本化 REST 接口也提供 `POST /api/v1/external/search` 与 `POST /api/v1/external/query`。需要客户端直接管理检索流程时，可使用 REST；Octop Agent 运行时集成优先使用 MCP，以复用 Octop 已有的凭据加密、连接探测和 Agent 工具选择能力。

当前 MCP 端点使用 JSON 响应，不建立会话、不推送 SSE 事件；HTTP GET 按 Streamable HTTP 约定返回 405。带 JSON-RPC `id` 的请求返回 JSON-RPC 响应；不带 `id` 的 `notifications/*` 通知返回 HTTP 202 和空响应体。协议初始化协商版本 `2025-06-18`。服务端工具与资源列表不提供订阅或变更通知。
