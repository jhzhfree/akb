# Octop 集成指南

AKB 可作为 Octop 的企业知识来源，通过 AKB MCP Server 让 Agent 使用受控的知识检索工具。AKB 保留知识内容、证据、审核状态与检索权限；Octop 保存连接配置并把授权的 MCP 工具提供给 Agent。

## 连接参数

| 参数 | 值 |
| --- | --- |
| Transport | Streamable HTTP |
| MCP URL | `https://<AKB 域名>/api/mcp` |
| Header | `X-API-Key: <AKB API Key>` |
| 建议权限 | `READ` |
| MCP 工具 | `knowledge_search`、`knowledge_query`、`list_knowledge_bases` 等 |

建议为每个知识空间创建绑定的 API Key，并只授予 `READ`。绑定 Key 会限制可访问的知识库；MCP 请求携带的 `knowledgeBaseId` 仍由 AKB 服务端验证。API Key 不应写入 Agent Prompt、业务文档或代码仓库。

在 Octop 的 **Agent → Connectors → 自定义 MCP** 中选择 **添加 AKB 知识库**，填写 MCP URL 与 `X-API-Key`，保存后执行探测。将该连接加入目标 Agent 的 MCP 配置，再由 Agent 调用 `knowledge_search` 获取段落、候选与引用，或调用 `knowledge_query` 获取带引用答案。

## 工具参数

`knowledge_search` 的关键参数：

```json
{
  "knowledgeBaseId": 23,
  "query": "员工年假政策",
  "topK": 5,
  "permissionTags": ["hr"],
  "minScore": 0.65
}
```

`knowledge_query` 使用 `question` 代替 `query`，并可指定 `answerFormat`。`permissionTags` 是检索过滤条件，不能代替知识库 ACL。返回中的 `queryId` 和 `citations` 可用于追踪与展示知识来源；MCP 查询也会在 AKB 记录为 MCP 来源。

## 联调检查

1. MCP 探测能列出 `knowledge_search` 和 `knowledge_query`。
2. 使用 Key 绑定的知识库检索成功，响应含 `queryId` 和引用信息。
3. 尝试查询未授权知识库时，AKB 拒绝请求。
4. 查询日志能区分 MCP 来源，并保留对应 API Key 与知识库信息。
5. 在 Octop 中移除该 MCP 后，目标 Agent 不再收到其工具。

AKB 的版本化 REST 接口也提供 `POST /api/v1/external/search` 与 `POST /api/v1/external/query`。需要客户端直接管理检索流程时，可使用 REST；Octop Agent 运行时集成优先使用 MCP，以复用 Octop 已有的凭据加密、连接探测和 Agent 工具选择能力。
