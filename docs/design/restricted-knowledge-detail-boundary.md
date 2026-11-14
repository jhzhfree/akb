# 受限知识详情投影边界

## 本轮行为

知识详情接口中，`permissionTags == null` 保持既有无约束访问语义；空列表表示 PUBLIC_ONLY，非空列表表示公开资料与获授权标签资料。空列表不是管理员身份。

CARD/CLAIM 的受限请求仍由 `getKnowledgeObject(kb, type, code, permissionTags)` 的 SQL 检查主对象。主对象不可见时返回既有空结果。主对象可见不意味着其来源、历史版本、关联冲突或治理文本可见。

现有附属查询没有逐项 ACL，所以受限请求不会调用这些查询，也不会调用图谱：`evidence/conflicts/reviews/assessments/versions` 返回空数组，`graph` 返回空对象，同时返回：

```json
{"detailsRestricted": true, "omittedSections": ["evidence", "conflicts", "reviews", "assessments", "versions", "graph"]}
```

这些空集合表示因授权边界**未加载**，不是不存在记录。客户端应展示“部分详情因权限保护暂不可用”，不能据此显示“无历史/无证据”。无约束调用保留原完整详情，响应不强制增加 marker。

CHUNK/CONTENT_UNIT 的旧详情投影没有标签检查，因此受限请求在任何 mapper 读取之前抛出固定 `ACL_FILTERED_CHUNK_DETAIL_UNAVAILABLE`，不包含对象编号或正文。已有独立 content-unit/evidence detail 的 source 标签 SQL 不在此轮改动范围。

## 兼容影响与恢复条件

受限用户在 REST/MCP 的 CARD/CLAIM 详情中不再获取附属正文；控制台通用对象详情中的受限 CHUNK/CONTENT_UNIT 请求暂不可用。不能把本轮收窄描述为完整逐项 ACL 已实现。

后续恢复某个附属段必须同时证明其 KB 归属、当前对象/来源标签、关联对象权限及历史正文授权；邻块不能仅因与主块同文档而自动授权。应覆盖同 KB A/B 权限差异、PUBLIC_ONLY、撤权及旧缓存，不得用主对象可见性替代子项授权。

本轮测试使用实际服务方法和 mock mapper，验证响应与不调用未经授权查询；不是生产数据库或跨 IdP 联调证明。
