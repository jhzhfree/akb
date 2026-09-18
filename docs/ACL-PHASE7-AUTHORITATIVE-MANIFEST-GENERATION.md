# ACL Phase 7 — Authoritative ACL Manifest + Cross-Engine Generation Barrier

## 目标
将 ACL Qualification 的权威基准从 Elasticsearch 提升为 MySQL authoritative snapshot，并降低 Elasticsearch/Milvus 顺序 alias 切换造成的 mixed-generation 风险。

## 权威 Manifest
V15 新增 `t_index_acl_manifest`，并在 `t_index_schema_release` 保存 `generation_id`、`acl_manifest_hash`、`acl_manifest_count`。

Manifest SHA-256 对每个 authoritative row 的以下字段做稳定序列化：
- objectKey
- contentHash
- status
- sorted permissionTags

因此 ACL 变化即使正文不变，也会改变 Manifest Hash。

重建时 MySQL 生成 Manifest；Qualification 请求把 expectedManifestHash/count 传给 AI 服务。AI 分别扫描指定 physical green Elasticsearch/Milvus target，计算实际 Manifest，并与 MySQL expected manifest 比较。Hash/count 不一致直接 FAILED。

## Phase 6 修正
Phase 6 使用 Elasticsearch `_id`（SHA-256 internal id）而 Milvus 使用 `kb:objectId` 做 persona set，会造成 live 环境 ID 域不一致。本轮统一 qualification identity 为业务 `object_id`。

## Generation Barrier
重建文档增加 `generation_id=schemaVersion`，ES/Milvus candidate metadata 均携带 generation_id。
HybridRetriever 对外部 ES/Milvus recall 做 fail-closed 检查：
- 出现多个 generation → `CROSS_ENGINE_GENERATION_MISMATCH`
- 新 generation 与 legacy/missing generation 同时出现 → 同样 fail closed

这不能让两个独立系统的 alias update 变成真正分布式原子事务，但能阻止 mixed-generation 查询继续进入融合/上下文/LLM。

## Neo4j 边界
Neo4j 仍未纳入 blue/green generation release。本轮没有虚假宣称三引擎原子切换。Qualification 明确返回：
`generationConsistency=ES_MILVUS_PINNED_GRAPH_PENDING`。
下一阶段需要 Graph Generation / graph read pointer 后，才能形成 ES + Milvus + Neo4j 同代屏障。

## 实际验证
- ACL Phase 7 contract: PASS (9 checks)
- Code Quality: PASS
- Mapper Contract: PASS (104 statements)
- DB Contract: PASS (36 tables / 34 mapper tables / 12 trace columns)
- Frontend TypeScript structural: PASS
- MyBatis XML: well-formed
- Python py_compile: PASS
- Shell syntax: PASS
- Focused pytest: 6 passed in 0.17s

未执行/未声称：Maven、vue-tsc/Vite production build、Live Flyway V15、Docker、Live ES/Milvus/Neo4j、真实 100k manifest 扫描性能、三引擎 generation E2E。

Production 仍为 NO-GO。
