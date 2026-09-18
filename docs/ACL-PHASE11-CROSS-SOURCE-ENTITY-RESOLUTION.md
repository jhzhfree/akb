# V1.1 ACL Phase 11 — Cross-Source Entity Resolution / Non-Broadening ACL

## 目标
解决同一实体/关系被多个 Source 共同支撑时，后一次抽取覆盖前一次 ACL 导致的隐性权限扩大问题。

## 本轮实现
- Flyway V18：新增 `t_entity_acl_scope`、`t_relation_acl_scope`。
- 每个 Entity/Relation 保留 `source_id + document_id + permission_tags + scope_hash + evidence_origin` 的来源级 ACL contribution。
- `KnowledgeExtractionService` 在 Entity Resolution / Relation persistence 后写入 ACL scope contribution。
- permission tags 排序后计算 SHA-256 scope hash，避免标签顺序造成伪差异。
- Entity/Relation 新增 `acl_merge_policy`。
- 单一 scope：`SINGLE_SCOPE`，保持原 ACL。
- 多个不同 scope：`EVIDENCE_SCOPED`，canonical Entity/Relation ACL 设置为 `['__evidence_scoped__']`。

## Non-Broadening 语义
禁止把 Team-A 与 Team-B 合并成 `[Team-A, Team-B]`，因为当前 tag 语义是 OR，这会让任一团队读取合并后的全部实体内容。

因此跨 scope 合并时采用 fail-closed quarantine：

`SINGLE_SCOPE -> normal retrieval`

`MULTI_SCOPE -> EVIDENCE_SCOPED -> canonical object not directly visible`

这保证 Entity Resolution 不会扩大任一原始 Evidence 的可见范围。

## 为什么本轮没有宣称 Evidence-Scoped GraphRAG 已完全完成
当前 Neo4j Generation 仍以 canonical EntityGen / RELATION_GEN 为主要投影。V18 已经把 source-scoped ACL contribution 变成 MySQL 权威事实，但还没有把每个 contribution 投影为独立的 Neo4j Evidence Facet。

因此本轮选择安全的 fail-closed，而不是错误地把多 scope ACL 做 UNION。

下一阶段应增加：
- `EntityFacetGen` / `RelationFacetGen`
- facet -> Evidence / Source binding
- facet-scoped ABOUT / RELATION traversal
- persona-aware facet selection
- canonical entity identity 与 restricted facts 分离
- facet manifest / generation qualification

## 实际验证
PASS：
- Phase 11 contract：7 checks
- Code Quality
- Mapper Contract：118 statements
- DB Contract：41 tables / 41 mapper tables / 12 trace columns
- MyBatis XML
- check-v11.sh shell syntax
- Focused ACL/Graph/Dual Retrieval pytest：5 passed

未执行/未声称：
- Maven / Java compile
- Live MySQL / Flyway V18
- Live Neo4j facet projection（本轮尚未实现）
- Vite/vue-tsc
- Docker full-stack
- Production qualification

## Release judgement
Engineering contract: PASS.
Production: NO-GO.

主要剩余 blocker 是 Evidence-Scoped Graph Projection 与 live leakage qualification。
