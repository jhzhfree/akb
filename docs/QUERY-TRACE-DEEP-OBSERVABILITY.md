# Query Trace Deep Observability

## 本轮目标
继续把 Hybrid / GraphRAG 的检索链路从“候选结果可视化”推进到“为什么召回、排名如何变化、耗时在哪里、治理策略在哪里执行”。

## 新增能力

### 1. Recall Channel Top-N
新增 `RecallChannelExplorer.vue`，分别展示 BM25、Vector/Milvus、Card、Claim、Graph。每个通道保留原始 rank、score、Source/Page/ContentUnit 定位，并可点击回跳 Knowledge/Evidence。

### 2. Rank Movement
RRF 后保存 `fusionRank`，Rerank 后保存 `rerankRank`、`rankDelta`、`channelRanks`。前端展示 `#6 → #2 ↑4`，解释候选经过 Reranker 后的升降。

### 3. Latency Waterfall
新增 `LatencyWaterfall.vue`，展示 Retrieval、Fusion/RRF、Rerank、LLM、Total，以及各 Recall Channel latency。AI Retriever 新增 `search_with_diagnostics()`，旧 `search()` contract 保持兼容。

### 4. Retrieval Governance
新增 `RetrievalPolicyPanel.vue`，展示 Lifecycle status filter、ACL pre-TopK、RRF object-id dedup、Conflict/status policy。不伪造 rejected count；当前未采集淘汰数量时明确不展示数量。

### 5. ACL 重要说明
本轮进一步暴露一个既有生产阻断项：ES/Milvus/Local 已按 permissionTags pre-TopK 过滤，但 Neo4j Graph ACL 尚未权威接线。Trace 明确标记 `graphAcl = NOT_AUTHORITATIVELY_WIRED`，UI 显示 ACL `PARTIAL`，不错误宣称全链路 ACL 已完成。这与既有 Production NO-GO 结论一致。

## 验证
实际通过：Deep Observability Contract、Query Trace Visualization、Evidence Viewer、Knowledge Version、Query History、Knowledge Detail、TypeScript Structural、Mapper/DB/Code Quality、Python py_compile、MyBatis XML、Shell syntax，以及 Dual Retrieval + Release Qualification focused pytest（6 passed）。

静态 Gate 扩展到 36 项。

未执行：Maven、vue-tsc/Vite production build、Live MySQL、Docker、Live ES/Milvus/Neo4j、ACL E2E。
