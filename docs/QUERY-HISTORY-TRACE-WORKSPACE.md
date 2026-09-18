# Query History + Query Trace Workspace

## 目标
把智能问答从“回答结果页”升级为可观测的企业知识检索工作台。

## 前端
技术栈继续保持：
`Vue 3 + TypeScript + Vite + Tailwind CSS + Element Plus`

新增：
- `QueryTracePanel.vue`
- `TraceStage.vue`
- QueryPanel 右侧 Query History
- 当前回答一键打开完整 Trace

Trace 展示链：
1. Query Rewrite
2. Router Decision
3. Multi Recall
4. Fusion / RRF
5. Rerank
6. Context Assembly
7. Citations
8. Token / Runtime
9. Final Answer

## 后端
复用已有 `t_query_trace`，不新增重复审计表。

新增：
`GET /api/knowledge/traces/{kb}?limit=30`

已有：
`GET /api/knowledge/trace/{qid}`

History 查询只返回摘要字段；用户打开具体 Trace 时才加载大型 JSON 字段，避免列表页一次传输大量 retrieval/context/citation JSON。

## 工程修复
本轮同时发现并修复上一轮 App.vue 中 `review` 方法错误混入 `KnowledgeObjectDetail` 类型参数的问题，并补充静态契约，避免该回归再次出现。

## 下一步
- Knowledge Version Snapshot + Diff
- Evidence 原文定位
- Trace 候选分数瀑布图
- Query Trace 与 Knowledge Detail 双向跳转
