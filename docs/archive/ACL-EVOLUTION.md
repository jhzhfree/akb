# ACL 权威化与传播治理 · 阶段合订

V1.1 之前用 13 个独立 `.md` 描述同一项建设（MySQL 权威 ACL → 治理工作台 → 状态机 → 重建编排 → 机器可校验资格 → 清单快照 → Neo4j 屏障 → 图谱生成 → 多跳 GraphRAG → 跨源实体消歧 → 证据投影 → 不可变证据绑定）的逐轮交付记录。每篇 1–3 KB，格式高度一致。
本合订按时间顺序拼接全部 13 个阶段，正文**逐字保留**，仅标题整体降一级。理解 ACL 的现状（Phase 13）建议从最末段开始向前读。

| # | 阶段 | 核心交付 |
|---|---|---|
| 1 | Phase 1 — Authoritative ACL | ACL 落到 MySQL 权威层，传播到所有检索视图 |
| 2 | Phase 2 — Identity / RBAC / ABAC | 来自企业身份而非客户端 |
| 3 | Phase 3 — Governance / Propagation / Audit | 治理工作台、Outbox、审计 |
| 4 | Phase 4 — Engineering Closure | 状态机、Code Quality 回归 |
| 5 | Phase 5 — Rebuild Orchestrator | 闭环 `REBUILD_REQUIRED`，激活屏障 |
| 6 | Phase 6 — Machine-Verifiable Leakage Qualification | 去掉人工 `passed=true` |
| 7 | Phase 7 — Authoritative Manifest | 资格基准从 ES 升到 MySQL snapshot |
| 8 | Phase 8 — Neo4j Generation Barrier | Neo4j 与 ES 同流程 |
| 9 | Phase 9 — Authoritative Graph Generation | Entity/Relation/Correspondence 落 MySQL |
| 10 | Phase 10 — Graph ACL + Manifest Qualification + Multi-hop GraphRAG | 跨源实体收敛 + 多跳合规 |
| 11 | Phase 11 — Cross-Source Entity Resolution / Non-Broadening ACL | 跨源覆盖不扩大权限 |
| 12 | Phase 12 — Evidence-Scoped Graph Projection | 实体身份 vs 证据事实分离 |
| 13 | Phase 13 — Immutable Evidence Binding + Citation-Safe GraphRAG | 证据不可变 + 引用安全 |



---

> **来源**：`docs/AUTHORITATIVE-ACL-PHASE1.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Authoritative ACL Phase 1

### Goal
Persist ACL at the MySQL authority layer and propagate it into every retrieval view instead of rebuilding restricted knowledge as public.

### Contract
- Empty `permission_tags` means public.
- Non-empty tags mean the caller must possess at least one matching tag.
- Retrieval ACL is applied before Top-K.
- Request-body `permissionTags` is not an authoritative identity source.
- Backend resolves access tags only from a disabled-by-default trusted upstream header contract.

### Persistence
Flyway V10 adds JSON `permission_tags` to `t_source`, `t_knowledge_card`, and `t_claim`.
Source ACL is inherited by extracted Card/Claim and graph entities/relations.
Authoritative rebuild now emits persisted tags instead of `[]`.

### Retrieval
Elasticsearch, Milvus, local fallback and Neo4j use permission tags before Top-K.
Neo4j also filters graph neighbors and relationships to prevent side-channel leakage.

### Detail APIs
Knowledge object, Evidence, ContentUnit and Graph detail use the trusted ACL context and fail closed to public-only when no trusted identity tags are available.

### Identity boundary
`knowledge.acl.trusted-header-enabled=false` by default.
Only when deployment has an authenticated gateway/SSO that strips client-supplied headers and injects `X-Authenticated-Permission-Tags` should the trusted-header mode be enabled.

### Remaining Phase 2 blockers
This round does not claim enterprise identity integration is complete. Still required before Production GO:
- bind tags to the actual corporate user/role/department identity provider rather than a generic trusted-header contract;
- apply ACL to all management/list/report APIs, not only retrieval/detail paths;
- execute live MySQL + ES + Milvus + Neo4j leakage tests for anonymous/team-a/team-b;
- verify rebuild and blue/green preserve ACL under failures;
- audit permission changes and trigger reindex/graph updates.



---

> **来源**：`docs/AUTHORITATIVE-ACL-PHASE2.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Authoritative ACL Phase 2 — Enterprise Identity / RBAC / ABAC

### Goal
Resolve permission tags from authenticated enterprise identity instead of trusting client-provided tags, and close list-page leakage for Source/Card/Claim.

### Identity model
V11 adds `t_acl_subject`, `t_acl_role`, `t_acl_subject_role`, `t_acl_role_tag`, `t_acl_subject_tag`, `t_acl_department_tag`, and `t_acl_access_audit`.

Effective tags are the union of direct subject tags, role tags, and department tags. `X-Authenticated-User-Id` is the identity key injected by a trusted SSO/gateway boundary. When DB identity resolution is enabled, caller-supplied permission-tag headers are not authoritative.

Defaults: `KNOWLEDGE_ACL_IDENTITY_DB_ENABLED=true`, `KNOWLEDGE_ACL_TRUSTED_HEADER_ENABLED=false`.

### List leakage closure
`/sources/{kb}`, `/cards/{kb}`, and `/claims/{kb}` now apply the same public-or-tag-intersection rule used by detail/query retrieval. Anonymous callers only see public rows.

### Leakage matrix
Automated persona contract covers Anonymous, Team-A, Team-B, and Admin. Public knowledge is visible to all; restricted knowledge requires at least one matching permission tag.

### Remaining blockers
The V11 audit table is schema-ready but access decision audit writes are not yet wired to every endpoint. Real SSO user synchronization, ACL administration APIs, ACL-change reindex propagation, live MySQL migration, and end-to-end ES/Milvus/Neo4j leakage qualification remain required before Production GO.



---

> **来源**：`docs/ACL-PHASE3-GOVERNANCE-PROPAGATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 3 — Governance, Propagation, Audit

### Scope
This round adds an ACL governance workbench, a durable ACL change ledger, Transactional Outbox propagation, and access-audit recording for protected knowledge-detail reads.

### Data model
Flyway V12 adds `t_acl_change` and enriches `t_acl_access_audit` with trace/request metadata.

### Change flow
Admin UI → MySQL authoritative ACL → `t_acl_change` → same-transaction `ACL_CHANGED` Outbox → AI `/ai/index/object/acl` → local / Elasticsearch / Neo4j patch.

Milvus scalar partial-update is deliberately not claimed safe. The provider returns `requiresReindex=true`; Source ACL changes return `requiresAuthoritativeRebuild=true` because they cascade to chunks/evidence/derived knowledge. These remain production blockers until the worker automatically converts those signals into a verified authoritative rebuild/reindex workflow.

### Audit
Knowledge Detail writes ALLOW/DENY audit decisions with subject, KB, resource, action, effective tags, traceId and request URI. Phase 4 should move this to a centralized interceptor/policy enforcement layer so every protected API is covered consistently.

### UI
A new 权限治理 tab shows Subjects, Roles, ACL changes and access audits, and supports SOURCE/CARD/CLAIM permission-tag changes.



---

> **来源**：`docs/ACL-PHASE4-ENGINEERING-CLOSURE.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 4 — Engineering Closure

### 目标
修复 Phase 3 的 Code Quality 回归，并把 ACL Change 从“写入 Outbox”升级为可观察的传播状态机。

### 传播状态机
`PENDING → COMPLETED | REBUILD_REQUIRED | FAILED`

- `COMPLETED`：ACL_CHANGED 已成功传播，且目标响应不要求权威重建。
- `REBUILD_REQUIRED`：Milvus 标记 requiresReindex，或 SOURCE ACL 触发 source cascade；禁止错误标记完成。
- `FAILED`：Outbox 达到最大重试次数进入 DEAD。
- Retry 中仍保持未完成状态。

`t_acl_change` 新增 `propagation_result`、`last_error`。`completed_time` 沿用 V12 已有字段，V13 不重复新增。

### Worker Closure
IndexOutboxWorker 在 ACL_CHANGED 投递后读取 AI 响应：requiresAuthoritativeRebuild=false → COMPLETED；true → REBUILD_REQUIRED；DEAD → FAILED。使用 Outbox `idempotencyKey = changeCode` 关联 ACL Change。

### Audit Reliability
AclAuditService 不再静默吞掉持久化异常，改为 ERROR 日志。审计写失败不能导致受保护数据被放行。

### Code Quality Closure
修复 Elasticsearch/Milvus provider、App.vue、TypeScript interface、AclAdministrationPanel 和 ACL Java Javadoc 的 Phase 3 回归。`check-code-quality.py` 本轮实际通过。

### 尚未自动化的重建
本轮故意不在普通 Outbox Worker 中直接执行全量 blue/green rebuild。Source ACL cascade / Milvus reindex 涉及 schema release、checksum、high-watermark、activation barrier，应该由专用 ACL Rebuild Orchestrator 完成：green generation → authoritative rebuild → consistency validation → ACL leakage smoke → activate → ACL Change COMPLETED。

### 实际验证
PASS：ACL Phase 4 Contract、Code Quality、Frontend TypeScript Structural、Mapper（97 statements）、DB（34 tables / 32 mapper tables / 12 trace columns）、MyBatis XML、shell syntax、focused pytest 6 passed、ZIP integrity。

静态 Gate 扩展为 40 项。

未执行/未声称：Maven、vue-tsc/Vite production build、Flyway V13 live MySQL、Docker full-stack、Live ES/Milvus/Neo4j、ACL rebuild orchestrator live、four-persona leakage E2E。



---

> **来源**：`docs/ACL-PHASE5-REBUILD-ORCHESTRATOR.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 5 — Rebuild Orchestrator & Activation Barrier

### 目标
将 Phase 4 的 `REBUILD_REQUIRED` 闭环为独立的 ACL 重建任务，禁止未经过资格验证的 green generation 被激活。

### 状态机

`PENDING → REBUILDING → VALIDATING → READY → ACTIVATING → COMPLETED`

失败路径进入 `FAILED`。

### V14
新增 `t_acl_rebuild_job`，持久化：
- jobCode / changeCode / knowledgeBaseId
- schemaVersion / releaseId
- state
- qualificationState / qualificationJson
- errorText / timestamps

每个 ACL Change 只允许一个 rebuild job，避免重复重建。

### Orchestrator
新增 `AclRebuildOrchestrator`：
1. 仅接受 `REBUILD_REQUIRED` ACL Change；
2. 调用既有 `IndexSchemaService.rebuild()` 生成 green release；
3. authoritative snapshot 的 count/checksum/high-watermark 校验仍由 IndexSchemaService 执行；
4. rebuild READY 后，ACL job 停在 `VALIDATING`；
5. qualification 必须显式 PASSED 才进入 READY；
6. activate() 强制检查 `state=READY && qualificationState=PASSED`；
7. 激活成功后 ACL Change 才改为 COMPLETED。

### Activation Barrier
没有 qualification 证据时不能调用 schema activation。这样把“重建成功”和“允许切流”拆成两个独立条件。

当前 qualification API 接受外部机器可验证测试结果作为 evidence。下一轮应把 Anonymous / Team-A / Team-B / Admin 四身份 leakage runner 直接接入该步骤，替代人工提交 passed=true。

### API
- `POST /api/knowledge/acl/rebuild/{changeCode}/start`
- `POST /api/knowledge/acl/rebuild/{jobCode}/qualify?passed=true|false`
- `POST /api/knowledge/acl/rebuild/{jobCode}/activate`
- `GET /api/knowledge/acl/rebuild/{kb}`

### 实际验证
通过：
- ACL Phase 5 contract
- Code Quality
- Mapper contract: 102 statements
- DB contract: 35 tables / 33 mapper tables / 12 trace columns
- MyBatis XML
- shell syntax
- focused sync pytest: 5 passed

环境限制：blue-green 两个 async pytest 因当前环境缺少 pytest-asyncio/anyio 插件无法执行，不属于业务断言失败。

未执行：Maven、vue-tsc/Vite build、Live MySQL/Flyway V14、Docker、Live ES/Milvus/Neo4j、自动四身份 leakage qualification。



---

> **来源**：`docs/ACL-PHASE6-MACHINE-LEAKAGE-QUALIFICATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 6 — Machine-Verifiable Leakage Qualification

### Goal
Remove the operator-controlled `passed=true` qualification switch. A rebuild job can become READY only from machine-produced evidence.

### Barrier
`VALIDATING -> machine qualifier -> PASSED/FAILED -> READY/FAILED -> activation`

The backend calls `/ai/index/schema/acl-qualify/{kb}` with the rebuild job's exact `schemaVersion`. The qualifier reads the physical green Elasticsearch/Milvus targets; it never switches aliases.

Personas: anonymous, team-a, team-b, admin. For each provider the result persists visible ids, allowed ids, leaked ids and missing ids into `qualification_json`. Any leaked id makes `qualified=false`, the job becomes FAILED, and the existing activation barrier refuses activation.

### Scope honesty
This phase machine-verifies the two blue/green retrieval engines managed by the current schema release: Elasticsearch and Milvus. Neo4j already has pre-query ACL filtering, but it is not generation-pinned to the ES/Milvus schema release, so this phase reports `graph=NOT_IN_BLUE_GREEN_SCHEMA_RELEASE` instead of falsely certifying graph generation consistency. Evidence/ContentUnit APIs remain protected by backend ACL enforcement but are not exercised by this green-index qualifier.

The expected ACL set is derived from green Elasticsearch metadata. Existing Phase 5 authoritative snapshot count/checksum/high-watermark validation remains a prerequisite and detects missing/extra snapshot rows. A future stronger gate should pass a signed/hashed authoritative ACL manifest from MySQL into qualification, eliminating Elasticsearch as the expected-set reference.

### Executed checks
- Phase 6 machine qualification contract: PASS
- Code quality: PASS
- Mapper contract: PASS (102 statements)
- DB contract: PASS (35 tables / 33 mapper tables / 12 trace columns)
- Python compile: PASS
- Focused retrieval/release pytest: 6 passed
- shell syntax: PASS

Not executed: Maven, vue-tsc/Vite build, live MySQL/Flyway, live ES/Milvus/Neo4j, Docker full stack, four-persona live E2E.

Production remains NO-GO.



---

> **来源**：`docs/ACL-PHASE7-AUTHORITATIVE-MANIFEST-GENERATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 7 — Authoritative ACL Manifest + Cross-Engine Generation Barrier

### 目标
将 ACL Qualification 的权威基准从 Elasticsearch 提升为 MySQL authoritative snapshot，并降低 Elasticsearch/Milvus 顺序 alias 切换造成的 mixed-generation 风险。

### 权威 Manifest
V15 新增 `t_index_acl_manifest`，并在 `t_index_schema_release` 保存 `generation_id`、`acl_manifest_hash`、`acl_manifest_count`。

Manifest SHA-256 对每个 authoritative row 的以下字段做稳定序列化：
- objectKey
- contentHash
- status
- sorted permissionTags

因此 ACL 变化即使正文不变，也会改变 Manifest Hash。

重建时 MySQL 生成 Manifest；Qualification 请求把 expectedManifestHash/count 传给 AI 服务。AI 分别扫描指定 physical green Elasticsearch/Milvus target，计算实际 Manifest，并与 MySQL expected manifest 比较。Hash/count 不一致直接 FAILED。

### Phase 6 修正
Phase 6 使用 Elasticsearch `_id`（SHA-256 internal id）而 Milvus 使用 `kb:objectId` 做 persona set，会造成 live 环境 ID 域不一致。本轮统一 qualification identity 为业务 `object_id`。

### Generation Barrier
重建文档增加 `generation_id=schemaVersion`，ES/Milvus candidate metadata 均携带 generation_id。
HybridRetriever 对外部 ES/Milvus recall 做 fail-closed 检查：
- 出现多个 generation → `CROSS_ENGINE_GENERATION_MISMATCH`
- 新 generation 与 legacy/missing generation 同时出现 → 同样 fail closed

这不能让两个独立系统的 alias update 变成真正分布式原子事务，但能阻止 mixed-generation 查询继续进入融合/上下文/LLM。

### Neo4j 边界
Neo4j 仍未纳入 blue/green generation release。本轮没有虚假宣称三引擎原子切换。Qualification 明确返回：
`generationConsistency=ES_MILVUS_PINNED_GRAPH_PENDING`。
下一阶段需要 Graph Generation / graph read pointer 后，才能形成 ES + Milvus + Neo4j 同代屏障。

### 实际验证
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



---

> **来源**：`docs/ACL-PHASE8-NEO4J-GENERATION-BARRIER.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 8 — Neo4j Generation + Three-Engine Barrier

### Goal
Bring Neo4j into the same governed generation and ACL qualification path as Elasticsearch and Milvus without claiming a distributed atomic commit across three independent engines.

### Governed graph generation
Each authoritative snapshot row is projected to a Neo4j `KnowledgeObject` node keyed by:

- `knowledgeBaseId`
- `generationId`
- `objectId`

The node persists `objectType`, `objectKey`, `contentHash`, `status`, `permissionTags`, title and content. Green rebuild deletes only the requested generation and never moves the active pointer.

`(:IndexGenerationPointer {knowledgeBaseId})` stores `activeGenerationId`. Query reads use this pointer before applying lifecycle and ACL filters.

### Three-engine rebuild
The authoritative MySQL page is now fanned out to:

1. Elasticsearch physical green index
2. Milvus physical green collection
3. Neo4j generation-scoped governed projection

Java `IndexSchemaService` requires all three counts to equal the authoritative snapshot count. A missing/unavailable graph therefore cannot silently pass production rebuild validation.

### Manifest qualification
Neo4j computes the same canonical SHA-256 manifest fields:

`objectKey + contentHash + status + sorted(permissionTags)`

Machine qualification compares MySQL's expected manifest with ES, Milvus and Neo4j, then runs Anonymous / Team-A / Team-B / Admin visibility checks against all three physical/generation targets.

### Activation and query safety
Activation still updates independent engines sequentially; it is not a distributed transaction. Neo4j activation is an atomic pointer update inside Neo4j. During the short cross-engine switch window, Hybrid Retrieval collects generation IDs from ES, Milvus and the Neo4j active pointer. If more than one generation is observed, or a generation is missing while another is present, query fails closed with `CROSS_ENGINE_GENERATION_MISMATCH`.

This closes silent mixed-generation reads, but it can cause temporary query unavailability during activation. A future availability round should introduce a request-level generation coordinator or dual-read grace protocol if zero-downtime activation is required.

### Important graph limitation
This phase generation-scopes the **governed retrieval projection** (`KnowledgeObject`) so ACL/manifest/generation safety can be verified. It does **not** yet rebuild the full semantic Entity/Relation graph from the authoritative MySQL snapshot, because the current authoritative snapshot contains ContentUnit/Card/Claim but not Entity/Relation rows. Existing legacy semantic graph writes remain separate.

Do not describe Phase 8 as full graph-lifecycle closure. The next graph round should add authoritative Entity/Relation snapshot rows, generation-scoped edges, relation ACL manifesting, and generation-safe neighborhood traversal.



---

> **来源**：`docs/ACL-PHASE9-AUTHORITATIVE-GRAPH-GENERATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 9 — Authoritative Entity/Relation Graph Generation

### 目标
将 Entity、Relation、Claim→Entity、Card→Entity 纳入 MySQL 权威图谱快照，并构建 generation-scoped Neo4j 图，避免旧图谱节点绕过 Generation Barrier。

### 数据模型
V16 新增 Entity/Relation 的 `source_id` 与 `permission_tags`，并新增 `t_claim_entity`、`t_card_entity`、`t_index_graph_manifest`。

### 权威图谱快照
`listAuthoritativeGraphSnapshot` 从 MySQL 产生四类记录：ENTITY、RELATION、CLAIM_ENTITY、CARD_ENTITY。每行包含稳定 graphKey、状态、ACL、contentHash；Java 对规范化 payload 计算独立 Graph Manifest SHA-256。

Graph Manifest 与 Retrieval Manifest 分离：两者对象全集不同，不错误地要求 Hash 相同。

### Neo4j Generation
- Entity: `(:EntityGen {knowledgeBaseId,generationId,code,...})`
- Entity Relation: `[:RELATION_GEN {generationId,code,relationType,permissionTags,...}]`
- Knowledge→Entity: `[:ABOUT_ENTITY_GEN {generationId,role,permissionTags,...}]`
- Green rebuild 不移动 `IndexGenerationPointer`。
- Active traversal 必须匹配 pointer 的 generationId，并对起点、终点和路径上的每条边执行 ACL。

### 安全语义
权限过滤发生在图遍历阶段，而不是 TopK 之后。任一节点或边不满足 caller permissionTags，则该路径不可进入 GraphRAG 上下文。

### 本轮实际验证
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

### 仍需解决
1. 现有 Entity/Relation 生产写入链尚未全面赋值 source_id/permission_tags；V16 只建立权威字段与重建契约，下一轮需把提取/裁决写入链做 ACL inheritance closure。
2. Graph Manifest 已生成并持久化，但尚未做 Neo4j 回读 manifest 的机器比对；下一轮应把它加入 Phase 6/7 qualification gate。
3. 多跳 GraphRAG 需要独立的 path scoring、cycle control、token budget 和 evidence binding。
4. 三引擎 activation 仍是 fail-closed barrier，不是分布式原子提交。



---

> **来源**：`docs/ACL-PHASE10-GRAPH-ACL-GRAPHRAG.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## V1.1 ACL Phase 10 — Graph ACL Inheritance + Manifest Qualification + Multi-hop GraphRAG

### Scope
This round closes the Phase-9 gap between extracted graph candidates and the MySQL authoritative graph, adds machine verification of the dedicated graph manifest, and introduces generation-safe ACL-aware multi-hop traversal.

### Implemented
- Flyway V17 adds graph ACL provenance and ACL fields for Claim/Card→Entity links.
- KnowledgeExtractionService persists extracted Entity/Relation objects into MySQL with source-derived permission tags and provenance.
- Explicit Claim/Card entity links inherit the knowledge object's ACL contract.
- ACL rebuild qualification now passes the MySQL graph manifest hash/entity/relation counts to the AI qualification gate.
- Neo4j recomputes the physical generation graph manifest from EntityGen, RELATION_GEN and ABOUT_ENTITY_GEN rows; mismatches fail qualification.
- Added generation_multihop traversal: active-generation pinning, node+edge+evidence ACL pre-filtering, simple-path cycle control, max depth/limit and token budget.
- Added `/ai/graph/{knowledge_base_id}/{entity_code}/multihop`.

### Security invariants
1. MySQL remains authoritative.
2. Entity/Relation ACL derives from the source/extraction ACL context; no graph edge may broaden access.
3. Multi-hop traversal filters every node and relation before a path is returned.
4. Evidence binding is filtered under the same generation and ACL context.
5. Graph manifest mismatch blocks READY/activation.

### Executed validation
PASS:
- ACL Phase 10 contract: 10 checks.
- Code quality contract.
- Python py_compile for routes/indexer/graph provider.
- MyBatis XML parse.
- check-v11.sh shell syntax.
- Focused pytest with PYTHONPATH=ai-service: 9 passed in 0.19s.

Not executed / not claimed:
- Maven/Java compilation.
- Flyway V17 against live MySQL.
- Live Neo4j manifest recomputation.
- Live ES/Milvus/Neo4j full-stack qualification.
- Vite/vue-tsc production build.

### Remaining blocker
Entity Resolution can merge candidates from multiple sources. The current persistence path uses the current extraction source ACL. Production-grade merge semantics must compute a non-broadening ACL policy across all supporting sources/evidence, with conflict/authority rules. This should be Phase 11.

Production status remains NO-GO until live qualification and cross-source ACL merge semantics are proven.



---

> **来源**：`docs/ACL-PHASE11-CROSS-SOURCE-ENTITY-RESOLUTION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## V1.1 ACL Phase 11 — Cross-Source Entity Resolution / Non-Broadening ACL

### 目标
解决同一实体/关系被多个 Source 共同支撑时，后一次抽取覆盖前一次 ACL 导致的隐性权限扩大问题。

### 本轮实现
- Flyway V18：新增 `t_entity_acl_scope`、`t_relation_acl_scope`。
- 每个 Entity/Relation 保留 `source_id + document_id + permission_tags + scope_hash + evidence_origin` 的来源级 ACL contribution。
- `KnowledgeExtractionService` 在 Entity Resolution / Relation persistence 后写入 ACL scope contribution。
- permission tags 排序后计算 SHA-256 scope hash，避免标签顺序造成伪差异。
- Entity/Relation 新增 `acl_merge_policy`。
- 单一 scope：`SINGLE_SCOPE`，保持原 ACL。
- 多个不同 scope：`EVIDENCE_SCOPED`，canonical Entity/Relation ACL 设置为 `['__evidence_scoped__']`。

### Non-Broadening 语义
禁止把 Team-A 与 Team-B 合并成 `[Team-A, Team-B]`，因为当前 tag 语义是 OR，这会让任一团队读取合并后的全部实体内容。

因此跨 scope 合并时采用 fail-closed quarantine：

`SINGLE_SCOPE -> normal retrieval`

`MULTI_SCOPE -> EVIDENCE_SCOPED -> canonical object not directly visible`

这保证 Entity Resolution 不会扩大任一原始 Evidence 的可见范围。

### 为什么本轮没有宣称 Evidence-Scoped GraphRAG 已完全完成
当前 Neo4j Generation 仍以 canonical EntityGen / RELATION_GEN 为主要投影。V18 已经把 source-scoped ACL contribution 变成 MySQL 权威事实，但还没有把每个 contribution 投影为独立的 Neo4j Evidence Facet。

因此本轮选择安全的 fail-closed，而不是错误地把多 scope ACL 做 UNION。

下一阶段应增加：
- `EntityFacetGen` / `RelationFacetGen`
- facet -> Evidence / Source binding
- facet-scoped ABOUT / RELATION traversal
- persona-aware facet selection
- canonical entity identity 与 restricted facts 分离
- facet manifest / generation qualification

### 实际验证
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

### Release judgement
Engineering contract: PASS.
Production: NO-GO.

主要剩余 blocker 是 Evidence-Scoped Graph Projection 与 live leakage qualification。



---

> **来源**：`docs/ACL-PHASE12-EVIDENCE-SCOPED-GRAPH-PROJECTION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## ACL Phase 12 — Evidence-Scoped Graph Projection

### Objective
Separate canonical entity identity from source/evidence-scoped facts so cross-source entity resolution never broadens ACL visibility.

### Authoritative model
V19 adds `t_entity_facet`, `t_relation_facet`, and `t_index_graph_facet_manifest`.
Extraction persists one stable facet per `canonical-code + ACL scope hash`; canonical Entity/Relation can remain `EVIDENCE_SCOPED` and fail closed while facets preserve source-specific names, descriptions, relations and permission tags.

### Neo4j generation projection
A green generation now contains:
- `EntityGen`: canonical identity.
- `EntityFacetGen -[:FACET_OF]-> EntityGen`: source-scoped descriptive fact.
- `RelationFacetGen -[:FACET_SOURCE]-> EntityGen` and `-[:FACET_TARGET]-> EntityGen`: source-scoped relationship fact.

Facet nodes carry `generationId`, `permissionTags`, `contentHash`, and status. Canonical identity is not treated as an ACL union.

### Manifest qualification
The authoritative graph snapshot now includes `ENTITY_FACET` and `RELATION_FACET`. Java computes both the full graph manifest and a facet-only manifest. Neo4j recomputes its physical manifest including facet projections, so missing/extra/modified protected facts change qualification output.

### Evidence-scoped GraphRAG
`GET /ai/graph/{knowledgeBaseId}/{entityCode}/facet-multihop` performs persona-aware traversal over visible RelationFacetGen facts. ACL is applied to the facet before it is returned. Response declares `FACET_PRE_FILTER_NON_BROADENING` and `CANONICAL_ENTITY_IDENTITY_ONLY`.

### Verification actually executed
- Phase 12 contract: 11 checks PASS.
- Code quality contract: PASS.
- Python py_compile: PASS.
- MyBatis XML parse: PASS.
- Focused ACL/graph/dual retrieval pytest: 5 passed in 0.12s.

### Not executed / not claimed
- Maven/Java compilation (Maven unavailable).
- Live MySQL + Flyway V19.
- Live Neo4j facet rebuild/manifest.
- Vite/vue-tsc production build.
- Docker full stack.
- Live three-engine activation qualification.

### Remaining boundary
Facet rows preserve source/document identifiers in MySQL, but Phase 12 does not yet project first-class EvidenceGen/SourceGen nodes into Neo4j. The next phase should add immutable evidence bindings and citation-safe path assembly, then verify that every returned facet has an accessible evidence chain.



---

> **来源**：`docs/ACL-PHASE13-IMMUTABLE-EVIDENCE-CITATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## V1.1 ACL Phase 13 — Immutable Evidence Binding + Citation-Safe GraphRAG

### Scope
Phase 13 closes the gap between evidence-scoped graph facts and citable evidence. A returned protected graph fact must have at least one evidence/source binding visible to the current persona.

### Changes
- Flyway V20 adds immutable entity/relation facet-to-evidence binding tables and graph evidence manifest.
- Authoritative graph rebuild now reads evidence bindings from MySQL and includes them in the green-generation rebuild request.
- Neo4j projects generation-scoped `EvidenceGen` and `SourceGen` plus `SUPPORTED_BY` and `DERIVED_FROM` edges.
- New citation-safe GraphRAG endpoint pre-filters RelationFacet, Evidence and Source ACL before returning a path.
- Each returned path must contain at least one visible citation; paths without evidence are dropped.
- Evidence manifest SHA-256 is generated from authoritative bindings for release qualification data.

### Security semantics
`Canonical Entity -> Facet -> Evidence -> Source` is intentionally separated. Canonical identity does not grant access to protected facts. A relation facet is usable for answer context only when facet ACL, evidence ACL and source ACL all permit the persona.

### Validation actually executed
- Phase 13 contract: 8/8 PASS.
- Code quality contract: PASS.
- Python py_compile: PASS.
- MyBatis XML parse: PASS.
- Focused pytest files requested in this environment were absent: `no tests ran`; not counted as PASS.

### Not executed / not claimed
- Maven / Java compilation.
- Live MySQL + Flyway V20.
- Live Neo4j evidence projection.
- Docker full stack.
- Live ES/Milvus/Neo4j release qualification.
- Vite/vue-tsc production build.

### Remaining blocker
The current authoritative binding query associates active evidence by source. Production-grade binding should be tightened to exact ContentUnit/Evidence IDs produced by extraction/adjudication rather than source-level association. The next phase should make Evidence IDs first-class outputs of Entity/Relation extraction and add machine-verifiable citation completeness/faithfulness gates.

Production remains NO-GO until live qualification evidence exists.
