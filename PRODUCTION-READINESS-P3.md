# V1.1 Production Readiness / Release Qualification P3

## 1. 发布原则
Production GO 必须由可复现证据驱动。静态 Contract、MockTransport、代码审查只能证明“具备进入真实验收的资格”，不能替代真实 Elasticsearch/Milvus/MySQL/Java 全栈验证。

## 2. 强制证据包
`performance/results/` 最终必须存在：
- `release-benchmark.json`：10K/100K ingestion 与 ES/Milvus P50/P95/P99。
- `retrieval-quality.json`：Recall@10、MRR、NDCG@10。
- `acl-qualification.json`：匿名、team-a、team-b ACL 泄漏测试。
- `consistency-qualification.json`：MySQL authoritative count、ES/Milvus delta、snapshot checksum、high-watermark。
- `blue-green-qualification.json`：持续查询期间 activate + rollback，queryFailures=0。
- `fault-injection-qualification.json`：ES/Milvus/Worker 故障与恢复。
- `build-qualification.json`：Maven package、Testcontainers、Flyway V7 MySQL 8 实迁移结果。

缺任何一项，`release-go-no-go.py` 必须返回 NO-GO。

## 3. ACL 安全
公开知识用空权限集合表达。Provider 负责把该语义映射到各数据库：Elasticsearch 省略 `permission_tags` 字段；Milvus 写入 `__public__` sentinel，并使用 `json_contains_any` 做动态 JSON 数组过滤。任何 restricted object 出现在未授权查询结果中都是 P0 blocker。

## 4. 三方一致性
当前 authoritative release 证据必须同时满足：
- `integrityState=VERIFIED`；
- snapshot checksum 非空；
- `sourceHighWatermark == sourceEndWatermark`；
- snapshot / Elasticsearch / Milvus count == expectedCount；
- drift API 的 Elasticsearch/Milvus delta 均为 0。

注意：V1.1 checksum 是 MySQL authoritative snapshot checksum，不宣称它是 ES/Milvus 全内容 checksum。生产审计应保留 releaseId、checksum、水位和目标物理索引/Collection。

## 5. 质量数据集
Gold set 必须人工复核，禁止仅用 LLM 自生成答案作为 gold。建议至少 200 条，且每类不少于 15 条：事实、SOP、精确错误码、Card、Claim、关系、多跳、冲突/版本、ACL、No-answer。记录 query、relevantIds、forbiddenIds、callerPermissionTags、审核人和数据版本。

## 6. 性能与容量
V1.1 初始 Release Gate：ES P95 <= 300ms；Milvus P95 <= 200ms；Recall@10 >= .80；NDCG@10 >= .75。必须分别跑 10K 与 100K；正式容量结论按真实 chunk 长度、embedding 维度、副本数和并发重新测定，不从 JSON fallback 压测外推生产容量。

## 7. 生产参数基线
Elasticsearch：生产必须启用认证/TLS、持久卷、JVM heap 明确配置、禁止 swap、设置合理 shard/replica、ILM/快照策略；中文 analyzer 在真实语料 A/B 后确定。
Milvus：etcd/对象存储必须持久化；生产认证/TLS、备份、监控必须启用；ANN index/metric/search params 必须用真实 Recall/Latency 数据选择，不硬编码“最佳”参数。

## 8. 备份恢复
MySQL 是权威业务状态，必须先验证 MySQL 全量+增量恢复。Elasticsearch/Milvus 是可重建检索视图，但仍建议保留快照/备份缩短 RTO。灾备演练顺序：恢复 MySQL -> 校验 Flyway -> 启动 Outbox/Worker -> 从 authoritative snapshot 重建 green targets -> checksum/watermark/count 验证 -> alias activate -> 查询/ACL smoke。

## 9. 最终签字
技术负责人确认 Build/Integration；数据/知识负责人确认 Gold Set；安全负责人确认 ACL；运维负责人确认备份恢复与故障注入；项目负责人确认 SLO/容量。任何 blocker 未关闭不得签 GO。
