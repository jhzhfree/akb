# 调试、测试与故障定位手册

## 1. 排障优先级

先区分问题层级：

```text
HTTP transport
→ Java business state
→ Outbox/index consistency
→ retrieval recall
→ rerank/context
→ LLM generation
```

不要看到“回答不对”就直接修改 prompt。

## 2. 上传失败

检查顺序：
1. Backend trace；
2. `/ai/documents/parse`；
3. 文件格式/大小；
4. `t_source/t_document/t_content_unit` 是否已提交；
5. Outbox 是否产生；
6. extract 是否失败。

如果“文档入库成功、索引失败”，应该修索引/Outbox，不要删除权威数据重传。

## 3. 查询慢

优先读取 QueryTrace，再决定是召回、重排、上下文还是 LLM 阶段的问题。


查看阶段：
- rewrite；
- BM25；
- vector；
- graph；
- rerank；
- LLM。

典型判断：
- BM25 慢 + provider=local：Elasticsearch fallback；
- vector 慢 + provider=local：Milvus fallback 或本地大规模；
- rerank 慢：BGE/GPU；
- retrieval 快但总耗时长：LLM。

## 4. 查不到已批准知识

检查：
1. MySQL status 是 APPROVED/EFFECTIVE？
2. 对应 Outbox SUCCEEDED？
3. Elasticsearch/Milvus payload status？
4. object `index_version` 是否被旧事件覆盖？
5. alias 指向正确 release？
6. ACL permission_tags？

## 5. Outbox 一直 RETRY

看 `last_error` 和 delivery attempt。常见：
- Elasticsearch mapping error；
- Milvus dimension mismatch；
- Neo4j unavailable；
- AI Service timeout；
- target path/API contract 变化。

## 6. 蓝绿 rebuild 失败

### COUNT_MISMATCH

比较：
- expectedCount；
- snapshotCount；
- elasticsearchCount；
- milvusCount。

### SOURCE_CHANGED

这是保护机制。说明重建期间发生新 Outbox 事件。重新发起 rebuild，或者未来升级成 snapshot + delta catch-up，不要强行 activate。

## 7. 关键测试说明

`test_v11_pipeline.py`：最小查询/索引管线。

`test_v11_governance.py`：治理 + reranker/graph fallback。

`test_adjudication.py`：可信/冲突/无证据三类评分。

`test_dual_retrieval.py`：Elasticsearch/Milvus REST contract 与 provider 语义。

`test_outbox_index_version.py`：状态版本不会被旧事件回写。

`test_blue_green_schema.py`：物理 target + alias activation。

`test_performance_regression.py`：provider singleton、snapshot cache、conflict focus 等性能契约。

## 8. 添加测试的判断

修 bug 时必须先问：这个 bug 能否变成一个 regression test？

优先级：
1. 数据一致性；
2. 权限；
3. 索引版本；
4. API contract；
5. 性能热点；
6. UI 行为。

## 9. 本地无 Docker 时

仍可运行：

```bash
./scripts/check-v11.sh
```

但不能声称以下已验证：
- MySQL Flyway 真迁移；
- SKIP LOCKED 多 Worker；
- Elasticsearch/Milvus 真 alias；
- Prometheus/Grafana 真抓取；
- frontend production build（若 npm deps 未安装）。
