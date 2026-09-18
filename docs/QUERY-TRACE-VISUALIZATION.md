# Query Trace Visualization

## 目标
把原先面向开发者的 JSON Trace，升级成面向产品、运维和知识治理人员的检索解释工作台。

## 可视化流水线
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

## 可导航候选
点击候选复用现有 Citation 导航：
1. contentUnitId → Evidence Viewer / 原文
2. CARD / CLAIM → Knowledge Detail
3. 无定位信息 → 明确提示

## Trace Contract
AI `selectedContext` 新增：
- title
- objectType
- contentUnitId
- sourceName
- pageNo

完整 `traceJson` 已包含 candidates，因此历史 Trace 也可以读取结构化候选；新产生 Trace 的 selectedContext 信息更完整。

## 注意
RRF 与 rerank 分值属于不同评分空间，UI 采用各自独立归一化条形展示，不能把条长解释成同一概率尺度。

## 下一步
- 各通道 Recall Top-N 独立展开
- 候选从各通道到 RRF 的排名变化 Sankey/flow
- latency waterfall
- ACL filter / dedup / conflict filtering 可视化
