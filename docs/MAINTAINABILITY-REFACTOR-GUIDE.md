# 研发可维护性二次重构指南

## 目标

本轮不改变知识业务语义，重点降低研发修改成本和结构性回归风险。

## 前端职责拆分

`App.vue` 只负责导航、页面状态编排和 API action。业务展示拆到：

- `DataCenterPanel.vue`
- `ReviewPanel.vue`
- `QueryPanel.vue`
- `KnowledgeListPanel.vue`
- `GraphPanel.vue`

新增页面时优先增加独立 Panel；不要重新把完整业务模板堆回 `App.vue`。

## Backend Typed Projection

MyBatis 查询如果跨越 Service 边界，不应默认返回 `Map<String,Object>`。

权威快照已经改为：

`KnowledgeMapper -> AuthoritativeIndexSnapshotRow -> IndexSchemaService -> toPayload() -> AiClient`

这样数据库字段变化与 AI Contract 转换被明确隔离。

`Map<String,Object>` 仍允许存在于动态 AI JSON 边界，但不应继续向数据库业务模型扩散。

## 复杂度热点

执行：

```bash
python3 scripts/analyze-maintainability.py
```

它会报告大文件和 Python 高复杂度函数。当前优先级：

1. `retrieval/external.py`：下一轮可按 Elasticsearch/Milvus 分文件。
2. `graph_provider.py`：可继续分离 Cypher builder 与 provider。
3. `indexer.py`：可分离 snapshot normalization / delivery。
4. `retrieval/hybrid.py::search`：建议拆成 channel execution + fusion。
5. `KnowledgeExtractionService` / `DocumentIngestionService`：保持应用服务，避免继续增长。

## 异常语义

- 参数/状态非法：`IllegalArgumentException` / 4xx。
- 外部依赖失败：保留 cause，转换为明确的 integration failure。
- 可降级 Provider：必须日志记录 provider 与 fallback。
- Outbox 投递失败：由 Worker RETRY/DEAD 管理，不由业务事务吞掉。
- 禁止 silent broad exception。

## 测试覆盖盲区

静态/Smoke Gate 不能替代以下真实测试：

- Maven Java 编译与单元测试。
- MySQL 8 Testcontainers + `SKIP LOCKED` 多 Worker。
- Vue `npm run build`。
- Elasticsearch/Milvus/Neo4j 容器故障注入。
- 蓝绿 Alias 真切换和回滚。
- 10万/100万权威快照重建。

## 研发提交前

```bash
python3 scripts/analyze-maintainability.py
bash scripts/check-v11.sh
```

结构约束由 `check-maintainability-refactor.py` 固化，防止 `App.vue` 再次膨胀或权威快照退回 Map Projection。
