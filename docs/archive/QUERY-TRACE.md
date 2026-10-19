# Query Trace 检索可观测 · 三轮合订

V1.1 把"为什么召回、排名怎么变、耗时在哪里、治理在哪执行"从开发者用的 JSON Trace，迭代成产品 / 运维 / 知识治理都能用的检索解释工作台。三轮各 1–2 KB，本合订按时间顺序拼接。

| 轮次 | 阶段 | 关键能力 |
|---|---|---|
| 1 | Trace 可视化 | Recall → RRF → Rerank → Selected Context 链路渲染 |
| 2 | Deep Observability | 召回通道可解释、排名变化、耗时定位、治理点 |
| 3 | History + Workspace | 检索历史 + 多查询工作台 |



---

> **来源**：`docs/QUERY-TRACE-VISUALIZATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Query Trace Visualization

### 目标
把原先面向开发者的 JSON Trace，升级成面向产品、运维和知识治理人员的检索解释工作台。

### 可视化流水线
`Recall → Weighted RRF → Rerank → Selected Context`

展示：
- 各召回通道候选数量
- 候选最终排序
- RRF score
- Rerank score
- 双得分相对条形轨迹
- Selected Context 数量
- Source / Page / ContentUnit 定位

原始 JSON 仍保留在折叠面板，便于研发排障。

### 可导航候选
点击候选复用现有 Citation 导航：
1. contentUnitId → Evidence Viewer / 原文
2. CARD / CLAIM → Knowledge Detail
3. 无定位信息 → 明确提示

### Trace Contract
AI `selectedContext` 新增：
- title
- objectType
- contentUnitId
- sourceName
- pageNo

完整 `traceJson` 已包含 candidates，因此历史 Trace 也可以读取结构化候选；新产生 Trace 的 selectedContext 信息更完整。

### 注意
RRF 与 rerank 分值属于不同评分空间，UI 采用各自独立归一化条形展示，不能把条长解释成同一概率尺度。

### 下一步
- 各通道 Recall Top-N 独立展开
- 候选从各通道到 RRF 的排名变化 Sankey/flow
- latency waterfall
- ACL filter / dedup / conflict filtering 可视化



---

> **来源**：`docs/QUERY-TRACE-DEEP-OBSERVABILITY.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Query Trace Deep Observability

### 本轮目标
继续把 Hybrid / GraphRAG 的检索链路从“候选结果可视化”推进到“为什么召回、排名如何变化、耗时在哪里、治理策略在哪里执行”。

### 新增能力

#### 1. Recall Channel Top-N
新增 `RecallChannelExplorer.vue`，分别展示 BM25、Vector/Milvus、Card、Claim、Graph。每个通道保留原始 rank、score、Source/Page/ContentUnit 定位，并可点击回跳 Knowledge/Evidence。

#### 2. Rank Movement
RRF 后保存 `fusionRank`，Rerank 后保存 `rerankRank`、`rankDelta`、`channelRanks`。前端展示 `#6 → #2 ↑4`，解释候选经过 Reranker 后的升降。

#### 3. Latency Waterfall
新增 `LatencyWaterfall.vue`，展示 Retrieval、Fusion/RRF、Rerank、LLM、Total，以及各 Recall Channel latency。AI Retriever 新增 `search_with_diagnostics()`，旧 `search()` contract 保持兼容。

#### 4. Retrieval Governance
新增 `RetrievalPolicyPanel.vue`，展示 Lifecycle status filter、ACL pre-TopK、RRF object-id dedup、Conflict/status policy。不伪造 rejected count；当前未采集淘汰数量时明确不展示数量。

#### 5. ACL 重要说明
本轮进一步暴露一个既有生产阻断项：ES/Milvus/Local 已按 permissionTags pre-TopK 过滤，但 Neo4j Graph ACL 尚未权威接线。Trace 明确标记 `graphAcl = NOT_AUTHORITATIVELY_WIRED`，UI 显示 ACL `PARTIAL`，不错误宣称全链路 ACL 已完成。这与既有 Production NO-GO 结论一致。

### 验证
实际通过：Deep Observability Contract、Query Trace Visualization、Evidence Viewer、Knowledge Version、Query History、Knowledge Detail、TypeScript Structural、Mapper/DB/Code Quality、Python py_compile、MyBatis XML、Shell syntax，以及 Dual Retrieval + Release Qualification focused pytest（6 passed）。

静态 Gate 扩展到 36 项。

未执行：Maven、vue-tsc/Vite production build、Live MySQL、Docker、Live ES/Milvus/Neo4j、ACL E2E。



---

> **来源**：`docs/QUERY-HISTORY-TRACE-WORKSPACE.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Query History + Query Trace Workspace

### 目标
把智能问答从“回答结果页”升级为可观测的企业知识检索工作台。

### 前端
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

### 后端
复用已有 `t_query_trace`，不新增重复审计表。

新增：
`GET /api/knowledge/traces/{kb}?limit=30`

已有：
`GET /api/knowledge/trace/{qid}`

History 查询只返回摘要字段；用户打开具体 Trace 时才加载大型 JSON 字段，避免列表页一次传输大量 retrieval/context/citation JSON。

### 工程修复
本轮同时发现并修复上一轮 App.vue 中 `review` 方法错误混入 `KnowledgeObjectDetail` 类型参数的问题，并补充静态契约，避免该回归再次出现。

### 下一步
- Knowledge Version Snapshot + Diff
- Evidence 原文定位
- Trace 候选分数瀑布图
- Query Trace 与 Knowledge Detail 双向跳转
