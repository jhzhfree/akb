# llmkb V1.1 真实负载性能测试与容量规划

> 本报告针对 V1.1 本地 fallback 检索链路进行真实代码压测，并依据结果给出容量边界。测试结果不是云厂商或生产硬件 SLO；真实 MySQL、Neo4j、BGE、Ollama 全栈需在部署环境继续执行 HTTP 黑盒压测。

## 1. 测试结论

### 1.1 最重要结论

1. **原线性 Vector 实现存在明显 Python 热路径问题**：每次查询对每条 Python list 执行 `np.asarray + cosine`。100k 时单次约 2.42s。
2. 本轮增加 **VectorSnapshot(float32 matrix)** 后，100k 向量快照 warm 查询约 **21ms**；首次构建快照约 1~1.7s，额外内存约 150MB。
3. 优化后主瓶颈转移到 **BM25 全量扫描**。100k warm BM25 约 0.63~0.65s。
4. 100k warm Hybrid Query P50 约 **778ms**，P95 约 **1.47s**，但 4 并发吞吐只有 **0.90 QPS**，P95 约 **4.23s**。因此当前 fallback 仍不适合作为中高并发生产主检索。
5. 中文逐字 tokenizer 对内存影响很大：10k 条、816 字正文时，LexicalSnapshot 额外约 **534MB**，而 118 字正文时约 **120MB**。
6. 冲突检测的 focus/group 优化有效：100k Claim 在测试分布下仅发生 9 次实际 compare，约 213ms；naive 理论 Pair 为 4,999,950,000。

## 2. 测试环境

本次执行环境：

- CPU：5 vCPU（容器执行环境）
- RAM：约 5.8GiB
- Python：3.13.5
- NumPy：项目现有依赖
- 当前执行环境无 Docker、Maven、NVIDIA GPU
- 因此本报告“已实测”覆盖 Python 检索核心；MySQL/Testcontainers、Neo4j、BGE GPU、Ollama LLM 属于部署环境待测项。

项目正式运行仍以 Python 3.10 为目标。本次 Python 3.13 结果用于容量方向与瓶颈定位，不应直接作为生产 SLA。

## 3. 数据模型与负载

主测试每条对象：

- Embedding：384 维 float
- 正文：约 118 个中文字符
- 类型比例：CONTENT_UNIT / CARD / CLAIM 混合
- 状态：EFFECTIVE
- 权限标签：无（权限过滤热路径未作为本轮主变量）
- Source：50 个合成来源
- Query：`如何完成Tomcat证书部署流程和知识裁决`
- Query 路由：CARD + BM25 + Vector
- Reranker：轻量 lexical fallback
- LLM：Mock，因此结果代表 **检索基础延迟，不包含真实 LLM/BGE 延迟**。

另外增加 10k × 816 中文字符的长 Chunk 对照测试。

## 4. 主测试结果（优化后）

| 对象数 | JSON 文件 | Index+Lexical 内存增量 | Vector Matrix 估算增量 | Warm Query P50 | Warm Query P95 | 4并发 QPS | 4并发 P95 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 10k | 22.34MB | 273.9MB | ~15MB | 97.5ms | 102.0ms | 10.02 | 383ms |
| 50k | 111.83MB | 1399.5MB | ~75MB | 366.2ms | 573.2ms | 2.27 | 1695ms |
| 100k | 223.71MB | ~2810MB | ~150MB | 778.2ms | 1470.5ms | 0.90 | 4230ms |

100k 的内存值来自独立分阶段测试：进程 baseline 约 98MB，JSON load 后约 1714MB，LexicalSnapshot 后约 2908MB；再增加 VectorSnapshot 约 150MB，进程总 RSS 约 3.0GB 量级。

## 5. 冷启动 / 首次预热成本

| 对象数 | JSON Cold Load | LexicalSnapshot Build | VectorSnapshot First Build/Query | 估算完全预热时间 |
|---:|---:|---:|---:|---:|
| 10k | 0.39s | 0.47s | 0.19s | ~1.1s |
| 50k | 3.58s | 2.75s | 0.85s | ~7.2s |
| 100k | 8.48s | 6.72s | 1.71s | ~16.9s |

建议：若继续保留 JSON fallback，增加显式 `prewarm()`，并让 readiness 在核心 KB 预热完成后再变为 READY，避免第一位用户承担初始化成本。

## 6. Vector 优化前后对比

优化前逐文档 Python/Numpy cosine：

| 对象数 | Vector P50（优化前） |
|---:|---:|
| 10k | 232ms |
| 50k | 1158ms |
| 100k | 2424ms |

优化后 `VectorSnapshot`：

| 对象数 | 首次构建+查询 | Warm Vector |
|---:|---:|---:|
| 10k | ~190ms | ~2~3ms |
| 50k | ~852ms | ~15ms |
| 100k | ~1.0~1.7s | ~21ms |

100k 矩阵额外约 149~150MB RAM。这个优化已经落入本轮代码，并通过现有 15 项 RC Gate。

## 7. 长 Chunk 对容量的影响

10k 条、816 字中文正文：

- JSON：40.1MB
- Cold Load：405ms
- JSON 对象增量：168MB
- LexicalSnapshot Build：2.46s
- LexicalSnapshot 增量：534MB
- Index + Lexical 总增量：702MB（未含约 15MB VectorSnapshot）
- Warm BM25：68ms

对比 118 字正文：LexicalSnapshot 约 120MB。

结论：当前中文 tokenizer 使用逐字 token，**长中文 Chunk 的主要代价是内存和预热时间，而 warm BM25 查询延迟主要仍随文档条数增长**。若大量 Chunk 接近 900 字，必须考虑：

- 中文分词器（jieba / pkuseg / analyzer）或直接迁移 Elasticsearch analyzer；
- 不在 Python 内存中保留每个字符 token tuple；
- BM25 使用倒排索引而不是 per-document scan。

## 8. 并发退化

4 并发测试：

- 10k：10.02 QPS，P95 383ms
- 50k：2.27 QPS，P95 1.69s
- 100k：0.90 QPS，P95 4.23s

100k 并发延迟呈明显阶梯：约 1.39s → 2.14s → 3.08s → 4.43s。

说明当前 Query 热路径仍有大量同步 Python BM25/typed recall 工作。即使 Vector 已矩阵化，单进程 FastAPI 并不能把这部分 CPU 工作自动变成高并发吞吐。

生产化方向：

1. BM25 迁移 Elasticsearch / Elasticsearch。
2. Vector 迁移 Milvus / Milvus / pgvector / Elasticsearch Vector。
3. Python Query Orchestrator 只保留路由、融合、rerank、context assembly。
4. 多 worker 只能作为临时手段，因为每 worker 会复制 Python Index/Lexical/Vector 内存。

## 9. 冲突检测

| Claim 数 | 实际耗时 | focus 后 compare | naive Pair |
|---:|---:|---:|---:|
| 10k | ~75ms | 0 | 49,995,000 |
| 50k | ~83ms | 4 | 1,249,975,000 |
| 100k | ~213ms | 9 | 4,999,950,000 |

当前 `subject/predicate + focusCodes` 思路是正确的。下一阶段更应把候选预筛完全放到 SQL/索引层，Python 只处理少量候选。

## 10. 容量分级建议

### Tier A：<= 10k 有效检索对象

可继续使用当前 V1.1 fallback，适用：

- 开发 / 演示 / 小团队内部知识库
- 低并发（约 1~4 并发）
- 检索基础 P50 目标 < 200ms

仍建议真实生产启用专业索引，但不是硬性阻断。

### Tier B：10k ~ 30k

进入过渡区：

- 如果 QPS < 1、延迟要求宽松，fallback 尚可；
- 如果需要 P95 < 1s 或并发 >= 4，建议提前迁移；
- 优先迁移 BM25，因为 VectorSnapshot 已显著缓解向量 fallback。

### Tier C：30k ~ 50k

建议把专业检索引擎列为 **生产强要求**：

- 当前 50k warm Query P50 已约 366ms；
- 4 并发 P95 约 1.7s；
- 仅 Python fallback 额外内存约 1.5GB。

推荐：Elasticsearch(BM25) + Milvus(Vector) + Neo4j(Graph)。

### Tier D：>= 100k

当前 JSON fallback 仅建议作为：

- 降级能力
- 离线开发
- 数据恢复/验证工具

不应作为在线主检索：

- Warm Query P50 ~ 0.78s，P95 ~ 1.47s（尚未计入真实 BGE/LLM）
- 4 并发 P95 ~ 4.23s
- 进程约 3GB 内存（短 Chunk 数据）
- 完全预热约 17s

## 11. 32GB RAM / RTX 4060 Ti 16GB 单机容量建议

在 32GB 主机上，即使 100k fallback 从“能装进内存”角度仍可运行，**也不代表应该这样部署**。延迟先于内存成为瓶颈。

若单机同时运行 MySQL + Redis + Neo4j + AI Service + Ollama/BGE，建议大致预留：

- OS / page cache：4~6GB
- MySQL：3~5GB
- Neo4j：3~5GB
- Redis：1~2GB（按实际缓存上限）
- Python AI/Orchestrator：2~4GB
- Elasticsearch（若同机）：至少 4GB heap + page cache
- Milvus：按向量规模 2~6GB 起步

因此在 32GB 单机上，不建议长期同时把 Elasticsearch、Neo4j、Milvus、MySQL 和大模型全部以高规格运行。更合理的是：

- 小规模：MySQL + Neo4j + Milvus + Python + GPU LLM，全文检索暂用 MySQL/轻量方案；
- 中规模：Elasticsearch/Milvus 至少拆一项到独立节点；
- 100k+ 且并发上升：检索层与 LLM 推理层分离部署。

## 12. 推荐的下一版生产检索架构

```text
MySQL (authoritative state)
      |
      +--> Elasticsearch  : BM25 / exact / analyzer
      +--> Milvus      : Vector / metadata filter
      +--> Neo4j       : Graph traversal
                |
                v
        Retrieval Router
                |
        Parallel Multi-Recall
                |
          Weighted RRF
                |
        BGE Reranker Top20~50
                |
        Context Assembly
                |
              LLM
```

特别注意：专业检索服务应并行召回，而不是在 Python 中按 channel 串行扫描。

## 13. 还未在当前环境实测的项目

由于当前执行环境没有 Docker、Maven、GPU，因此以下指标必须在你的真实部署机继续测：

- MySQL 批量 ContentUnit/Evidence TPS、P95
- Flyway + MyBatis 全链路
- Neo4j 1/2/3 hop traversal P95
- BGE reranker-v2-m3：Top20/50、batch 4/8、GPU 显存、P50/P95
- Ollama Qwen2.5-7B：首 token、tokens/s、完整回答延迟
- HTTP 全链路 Query P50/P95/P99
- 上传/解析/抽取长文档吞吐

项目新增 `scripts/http_load_test.py`，用于部署后黑盒测试：

```bash
python scripts/http_load_test.py \
  --base-url http://127.0.0.1:8080 \
  --kb 1 \
  --requests 200 \
  --concurrency 8 \
  --out performance/results/http-load-real.json
```

建议按并发 1 / 2 / 4 / 8 / 16 逐级执行，不要直接从 16 开始。

## 14. 建议性能 SLO（V1.1 RC）

把“检索”和“生成回答”分开设 SLO：

### Retrieval SLO

- P50 <= 300ms
- P95 <= 800ms
- P99 <= 1500ms
- 错误率 < 0.5%

超过 30k~50k 对象时，不再允许 JSON fallback 作为生产主索引。

### Full Answer SLO（有真实 LLM/BGE）

建议首阶段：

- P50 <= 3s
- P95 <= 8s
- P99 <= 15s
- 超时必须返回检索证据或明确失败，而不是无限等待

最终数值应在真实 Ollama/BGE 部署后重新定标。

## 15. 本轮落地代码

本轮根据压测结果实际增加/修改：

- `HybridRetriever.VectorSnapshot`：索引 generation 级 float32 matrix 缓存
- Vector Top-K：矩阵乘法 + `argpartition`
- Vector ACL：Top-K 前 permission mask
- LexicalSnapshot：增加 `documents_by_type`
- Card/Claim recall：只扫描对应类型分区
- `scripts/load_test_worker.py`
- `scripts/load_test_100k_phases.py`
- `scripts/load_test_100k_final.py`
- `scripts/load_test_extra.py`
- `scripts/http_load_test.py`
- `performance/results/*` 原始数据

所有原有 V1.1 RC Gate 在修改后继续通过。
