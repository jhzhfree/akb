# ACL Phase 9 — Authoritative Entity/Relation Graph Generation

## 目标
将 Entity、Relation、Claim→Entity、Card→Entity 纳入 MySQL 权威图谱快照，并构建 generation-scoped Neo4j 图，避免旧图谱节点绕过 Generation Barrier。

## 数据模型
V16 新增 Entity/Relation 的 `source_id` 与 `permission_tags`，并新增 `t_claim_entity`、`t_card_entity`、`t_index_graph_manifest`。

## 权威图谱快照
`listAuthoritativeGraphSnapshot` 从 MySQL 产生四类记录：ENTITY、RELATION、CLAIM_ENTITY、CARD_ENTITY。每行包含稳定 graphKey、状态、ACL、contentHash；Java 对规范化 payload 计算独立 Graph Manifest SHA-256。

Graph Manifest 与 Retrieval Manifest 分离：两者对象全集不同，不错误地要求 Hash 相同。

## Neo4j Generation
- Entity: `(:EntityGen {knowledgeBaseId,generationId,code,...})`
- Entity Relation: `[:RELATION_GEN {generationId,code,relationType,permissionTags,...}]`
- Knowledge→Entity: `[:ABOUT_ENTITY_GEN {generationId,role,permissionTags,...}]`
- Green rebuild 不移动 `IndexGenerationPointer`。
- Active traversal 必须匹配 pointer 的 generationId，并对起点、终点和路径上的每条边执行 ACL。

## 安全语义
权限过滤发生在图遍历阶段，而不是 TopK 之后。任一节点或边不满足 caller permissionTags，则该路径不可进入 GraphRAG 上下文。

## 本轮实际验证
PASS:
- Phase 9 structural contract: 12 checks
- Code Quality Contract
- Mapper Contract: 108 statements
- DB Contract: 39 tables / 39 mapper tables / 12 trace columns
- Frontend TypeScript structural contract
- MyBatis XML parse
- Python py_compile
- ZIP integrity

未执行/未声称：
- Maven（当前环境无 mvn）
- npm install / vue-tsc / Vite production build
- Live Flyway V16 MySQL
- Live Neo4j generation rebuild
- Live ES/Milvus/Neo4j activation
- pytest focused suite（指定文件在当前工程中不存在，结果为 no tests ran）

## 仍需解决
1. 现有 Entity/Relation 生产写入链尚未全面赋值 source_id/permission_tags；V16 只建立权威字段与重建契约，下一轮需把提取/裁决写入链做 ACL inheritance closure。
2. Graph Manifest 已生成并持久化，但尚未做 Neo4j 回读 manifest 的机器比对；下一轮应把它加入 Phase 6/7 qualification gate。
3. 多跳 GraphRAG 需要独立的 path scoring、cycle control、token budget 和 evidence binding。
4. 三引擎 activation 仍是 fail-closed barrier，不是分布式原子提交。
