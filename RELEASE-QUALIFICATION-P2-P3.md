# Elasticsearch + Milvus Release Qualification P2/P3

## 目标
P2/P3 不再以 Mock Contract 作为上线证据，而是要求真实 Elasticsearch、Milvus、MySQL、Backend/Worker 环境产生机器可读的验收证据。

## 部署拓扑修正
Milvus 3.0.1 官方 Docker Compose Standalone 采用 `milvus-etcd + milvus-minio + milvus-standalone`。项目开发/生产 Compose 已按该拓扑调整。单独启动一个 Milvus 容器不再作为正式 Qualification 拓扑。

## 性能资格
建议分级执行：

```bash
python scripts/release-qualification-benchmark.py --documents 10000 --queries 300
python scripts/release-qualification-benchmark.py --documents 100000 --queries 1000 --batch 500
```

Runner 使用 batch streaming，避免一次性在 Python 内存构造 100K×384 vectors。

记录：ingestion docs/s、ES/Milvus P50/P95/P99、最终 count。

## 检索质量
准备 JSONL：
`{"query":"...","relevantIds":["K1"],"retrievedIds":["K1","K2"]}`

执行：

```bash
python scripts/release-quality-eval.py performance/datasets/rq.jsonl --k 10
```

基线默认要求 Recall@10 >= 0.80，NDCG@10 >= 0.75。生产数据集必须覆盖事实、SOP、精确错误码、关系、多跳、冲突/版本、ACL 场景。

## 故障注入
在一次性 Qualification 环境执行：

```bash
KB=1 ./scripts/release-fault-injection.sh
```

必须观察：ES/Milvus outage -> RETRY；恢复后 backlog 清零；Worker kill 后 stale PROCESSING 可恢复；不能出现同 aggregate 越序。

## 蓝绿零停机
准备两个已经 READY 的 schema 后执行：

```bash
python scripts/release-blue-green-live.py --old-schema schema1 --new-schema schema2
```

切换和 rollback 期间持续查询，要求 queryFailures=0。

## Go/No-Go
机器证据齐全后：

```bash
python scripts/release-go-no-go.py
```

默认阈值：ES P95 <=300ms、Milvus P95 <=200ms、Recall@10>=0.80、NDCG@10>=0.75。

此外人工强制 Blocker：
- Maven/Testcontainers 未通过；
- Flyway V7 未在 MySQL 8 验证；
- fault injection 未通过；
- alias switch/rollback 未通过；
- ACL 泄漏测试失败；
- 数据 checksum/high-watermark 不一致。

任一 Blocker 存在即 NO-GO。
