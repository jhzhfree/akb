# 研发技术实现详解（核心培训文档）

> 面向对象：即将接手/维护本工程的研发人员。
> 目标：不读一遍全量代码也能知道**改哪里、为什么这样改、改坏会波及什么**。
> 阅读方式：按"场景"递进，每个场景回答三件事 —— **代码在哪**（实现）、**为什么这么写**（原理）、**当初解决了什么问题**（设计取舍）。

版本基线：Spring Boot 2.7.18 / Java 8（`backend/pom.xml`）、FastAPI（`ai-service`）、Vue 3 + Vite（`frontend`）、MySQL + Elasticsearch + Milvus + Neo4j。

---

## 0. 先建立全局心智模型

### 0.1 三个进程、四种存储

```
浏览器 (Vue3, 5173)
   │  /api/**
   ▼
backend (Java 8080)  ──权威数据的唯一写入者──► MySQL（权威源）
   │  /ai/**  (RestTemplate, 读超时 180s)
   ▼
ai-service (Python 12000) ──派生视图的写入者──► ES / Milvus / Neo4j
```

`backend` 的 AI 地址来自 `application.yml:53`：`ai-service-base-url: ${AI_SERVICE_BASE_URL:http://127.0.0.1:12000}`。

**最重要的一条架构纪律：MySQL 是权威源（authoritative），ES/Milvus/Neo4j 全部是派生视图（derived view）。**
这解释了本工程里几乎所有"绕一圈"的设计：

| 现象 | 根因 |
|---|---|
| 写完业务表不直接写 ES，而是插一条 outbox 事件 | 跨库事务无法保证一致，用 Transactional Outbox 保证"业务与索引最终一致" |
| 索引失败只把文档标 `DIRTY`，**不回滚业务数据** | 索引是派生视图，失败不能否定权威数据（`IndexOutboxWorker.java:138-141` 注释明确写了这一条） |
| 检索永远带上 `permissionTags` 过滤 | ES/Milvus 里的数据是"已发布视图"，权限必须在查询侧下推，不能依赖"只把有权限的数据写进去" |
| 有 `rebuild / activate / rollback` 三套索引操作 | 索引可重建，所以能蓝绿切换；权威数据不可重建，所以要有不可变证据 |

### 0.2 一条数据从无到有的命名体系

读懂代码的一半工作量是读懂编码前缀，`IdGenerator` 统一发放：

| 前缀 | 含义 | 出处 |
|---|---|---|
| `KB_` | 知识库（实体主键仍是自增 id） | `KnowledgeBaseService` |
| `SRC_` / `DOC_` | 来源 / 文档 | `DocumentIngestionService.createSource/createDocument` |
| `CU_` | Content Unit（切片/证据单元） | `DocumentIngestionService.java:495` |
| `KC_` | Knowledge Card（知识卡片） | `KnowledgeExtractionService.persistCards` |
| `CLM_` | Claim（可验证断言） | `KnowledgeExtractionService.persistClaims` |
| `Q_` | 一次问答 | `KnowledgeQueryService.java:65` |
| `ACLR-` / `ACL-UUID` | ACL 重建作业 / ACL 变更 | `AclRebuildOrchestrator.start` / `AclAdministrationService.changeResourceAcl` |
| `kos_ak_…` | API Key 明文前缀 | `ApiKeyService.java:52-71` |

> 约定：所有对外暴露的标识用 `xxxCode`（稳定、可引用），内部主键用自增 `id`（可重建）。跨服务调用一律传 code。

### 0.3 分层与包结构（`backend/src/main/java/com/jh/knowledgeos`）

```
controller  33 个：只做参数绑定 + 权限断言 + 调 service + 包 ApiResponse
service     77 个：业务编排、事务边界、事件发布、跨服务调用
mapper      29 个 MyBatis 接口 + resources/mapper/*.xml（SQL 全在 XML 里）
domain      22 个 POJO，与表一一对应
dto         controller 出入参
config      拦截器、线程池、属性绑定、启动诊断
common      枚举与常量（Permissions / UserRole / KbScenarioTemplates / AnswerFormat…）
projection  只读投影（给前端/外部 API 的裁剪视图）
```

**读代码顺序建议**：`controller`（看接口契约）→ `service`（看编排）→ `mapper/*.xml`（看真实 SQL）。
不要从 `domain` 入手，POJO 不带业务逻辑，看了也建立不了模型。

---

## 1. 场景一：新建一个知识库 —— 场景模板如何"一键塑造"整条链路

### 1.1 代码在哪

- 创建/编辑：`KnowledgeBaseService`、`KnowledgeBaseDialog.vue`（前端）
- 场景模板常量：`common/KbScenarioTemplates.java`
- 场景配置落地：`service/KbScenarioService.java`、`t_kb_scenario`（迁移脚本 `V44__kb_scenario_profile.sql`）

### 1.2 五种场景与模板结构

`KbScenarioTemplates.java:41-55` 定义 `GENERAL / OPS / POLICY / BUSINESS / VULN`，`DEFAULT_SCENARIO = GENERAL`。
模板不是"一个标签"，而是一份 **profile JSON**，一次 `seed` 同时写入三类配置：

```java
// KbScenarioService.java:151-158  seed()
applyChunkPolicy(kb, section(section(profile.get("generation")).get("chunk")));   // ① 切片策略
// ② 派生规则（derivation）：该场景默认启用哪些知识派生
// ③ 检索 profile：通道权重、topK、答案格式、graph 开关
```

| 配置段 | 落点 | 谁读 |
|---|---|---|
| `generation.chunk` | `ChunkPolicyService` 的 per-KB 行（`V33__chunk_policy_per_knowledge_base.sql`） | 上传时随解析请求发给 `ai-service` |
| `retrieval.*` | `t_kb_scenario.profile_json` | `KbScenarioService.queryOverride(kb)` 每次问答注入 `policyOverride` |
| `derivation.*` | 派生规则表 | `KnowledgeDerivationService` / `DerivationScheduler` |

### 1.3 设计取舍：为什么是"模板 seed + 覆写"而不是"配置项快照"

`ChunkPolicyService.java:84-93` 的注释是关键：

```java
/**
 * An untouched library follows the chunking of its scenario template; only an edit turns
 * the stored row into the answer, so a library that never touched the slider still benefits
 * when the template improves.
 */
public boolean customized(Long kb) { return kb != null && mapper.getChunkPolicy(kb) != null; }
```

即：**没改过切片策略的库不写行，运行时回落到场景模板**；一旦在控制台改了才落库定死。
这样"模板优化"能对存量库生效，又不覆盖用户显式调优过的库。

> 排障提示：改了场景模板但某库切片没变，先查 `customized(kb)` —— 它已被手工改过。

### 1.4 场景如何一直传到问答

```
建库 seed(scenario)
   └─► t_kb_scenario.profile_json
         └─► KbScenarioService.queryOverride(kb) ──► KnowledgeQueryService.java:72-74
               └─► payload["policyOverride"] ──► ai-service QueryEngine.merge_override
```

所以"某个库答得不好"的第一个排查点：`GET` 该库的场景 profile → 看 `retrieval` 段通道权重是否合理。

---

## 2. 场景二：上传一份文档 —— 摄入全链路（本工程最复杂的一条链）

### 2.1 时序总览

```
POST /api/knowledge/documents/upload (MultipartFile)
  ▼ KnowledgeIngestionController
  ▼ DocumentIngestionService.upload()  :89
      ├─ tracked():100  创建任务中心任务（TaskCenterService），全程状态可追踪
      ├─ SourceFileStorageService 落原文（文件内容可回看/重解析）
      ├─ aiClient.post(/ai/documents/parse, {file, chunkPolicy})   ← 解析+切片在 Python 侧
      └─ runIngestion():184
            ├─ PARSE   : 拿到 standard（标准文档模型）+ chunks（标准化切片）
            ├─ PERSIST : 一个事务写 Source / Document / ContentUnit / Evidence
            │            └─ 同事务 enqueue 两条 outbox 事件（chunks / standard）
            ├─ EXTRACT : 调 /ai/extract 生成 Card / Claim（在事务外！）
            ├─ POSTPROCESS : 抽取主事务中登记图谱、冲突、实体消歧子阶段
            └─ READY/PARTIAL : 失败阶段保留检查点并退避重试；手动重放不重新调模型
```

### 2.2 关键设计一：事务边界划在"AI 调用"之外

`DocumentIngestionService.java:209-213` 的注释把取舍说得很清楚：

```java
// Retried on deadlock: a batch runs several documents through this write set at once,
// and every INSERT here is rolled back cleanly by the database, so redoing the whole
// transaction is a no-op rather than a duplicate. The expensive AI calls sit outside
// it precisely so that a retry never pays for them twice.
IngestionState state = transactionRunner.retryingOnDeadlock(() -> { ... });
```

要点：

1. **解析（`/ai/documents/parse`）在事务外**：它是纯函数（文件 → JSON），失败重试代价可控。
2. **抽取（`/ai/extract`）也在事务外**：因为它要调 LLM，可能几十秒；把它放进事务会导致长事务 + 死锁。
3. **只有 DB 写在事务内**，且用 `TransactionRunner.retryingOnDeadlock` 包裹，批处理并发写同一批表时死锁自动重放。
4. 事务内的 INSERT 全部是幂等的（同一 revision 重放结果相同），所以重放安全。

### 2.3 关键设计二：revision + supersede（切片可回溯）

```java
// DocumentIngestionService.java:226-231
documentMapper.supersedeDocumentChunks(document.getId());          // 旧切片作废
int revision = retryDocumentId == null ? 1 : documentMapper.nextChunkRevision(document.getId());
List<Map<String,Object>> indexChunks = persistChunks(..., revision);// 新切片带新 revision
documentMapper.updateDocumentParsed(document.getId(), text, indexChunks.size(), "PARSED");
```

- 重传同一文档：**保留原 Source / Document 身份**（:222 注释），只让切片升版本。
  原因：`Evidence` 行引用切片，若重建文档身份，历史证据就断链了。
- 幂等键带 revision：`"DOCUMENT_CHUNKS:" + documentId + ":" + revision`（:234），重传不会重复投递索引。

### 2.4 关键设计三：Transactional Outbox

落库与"通知索引"必须原子，但 ES/Milvus 不支持 XA。方案是同事务插一条事件表：

```java
// DocumentIngestionService.java:232-240
Map<String,Object> indexEvent = outboxService.enqueue(
        knowledgeBaseId, "DOCUMENT",
        "KB:" + knowledgeBaseId + ":DOCUMENT:" + document.getId(),   // aggregateKey
        "DOCUMENT_CHUNKS_UPSERT", AiApiPaths.INDEX_CHUNKS,
        buildIndexReference(...),                                      // 只存引用，不存大 payload
        "DOCUMENT_CHUNKS:" + document.getId() + ":" + revision);       // idempotencyKey
```

`IndexOutboxService.enqueue`（`:47-86`）的三个机制：

1. `@Transactional(propagation = Propagation.MANDATORY)` —— **强制调用方必须有事务**，从编译期杜绝"写了业务表却忘发事件"。
2. 幂等键去重（`:54-57`）+ 并发插入冲突兜底（`:78-84`）：并发同 revision 时不会产出两条事件。
3. **聚合版本号**（`:58-63`）：`incrementIndexAggregateVersion(aggregateKey)` 后把 `indexVersion` 写进 payload。
   索引侧按版本**丢弃乱序的旧事件**——这是"后发先至"问题的正解。

投递侧 `IndexOutboxWorker`：

| 机制 | 实现 | 位置 |
|---|---|---|
| 抢占与租约 | 每次领取使用唯一 lease token；成功、重试、死信更新都必须匹配当前 `locked_by`，过期 Worker 不能覆盖新租约 | `IndexOutboxWorker` / `IndexMapper.xml` |
| 锁超时回收 | `recoverStaleIndexOutbox(threshold)`，默认 300s | `:199-206` |
| 指数退避 | `delay = min(900, retryBase * 2^min(10, attempt-1))`，默认 base=2s | `:131` |
| 死信 | `attempt >= maxAttempts(默认8)` → `markIndexOutboxDead` + 文档标 `DIRTY` | `:135-141` |
| 只存引用、投递前补齐 | `hydrateDocumentPayload()` 投递时才查切片正文 | `IndexOutboxService.java:176-187` |
| 嵌套错误识别 | `failOnNestedError()` 递归找 `error` 字段，HTTP 200 包错误也判失败 | `:208-223` |
| 投递审计 | `insertIndexDeliveryAttempt` 每次尝试留痕 | `:118 / :145` |

> **运维接口**：`KnowledgeIndexOperationsController` 提供 `replay / skip / list / stats / drift`。
> `drift()`（`IndexOutboxService.java:148-160`）对比权威计数与 ES/Milvus 计数，是判断"索引是不是漏了"的第一手段。

### 2.5 关键设计四：切片策略 per-KB（解析侧）

`ChunkPolicyService`（255 行）把切片从"AI 服务的两个环境变量"提升为"随知识库存储的策略"：

```java
// :38-39  五种策略
SEMANTIC | FIXED | SENTENCE | STRUCTURE | PARENT_CHILD
// :154-159  越界夹紧：overlap 不允许超过窗口一半，否则整篇被重复复制
int size    = clamp(..., 200, 4000);
overlap     = Math.min(overlap, size / 2);
minSize     = Math.min(minSize, size / 2);
```

策略语义（`:172-180`，控制台直接展示这段文案）：

| 策略 | 适合 | 说明 |
|---|---|---|
| `SEMANTIC`（默认 900/120） | 通用 | 语义块累积到窗口上限，表格与代码独立成块，遇新章节即切 |
| `FIXED` | 无结构长文本 | 窗口滑窗 + 真实重叠 |
| `SENTENCE` | 叙述型 | 以句为最小单位聚合，切点必在句末 |
| `STRUCTURE` | 手册/规程 | 每个标题路径一块，超长才硬切 |
| `PARENT_CHILD` | 长文档 | 章节为父块、窗口为子块；**检索命中子块，回答回看父块** |

切片真正执行在 `ai-service/app/services/standard_document.py`：`build_units()`（:695）按 strategy 分发，
`_next_cut()`（:615）/ `_snap_to_boundary()`（:603）负责在预算内找**不切断句子**的切点，
`_make_unit()`（:655）把 `strategy` 写进每个切片 —— 所以事后能解释"这块为什么这么切"。

### 2.6 关键设计五：原文、标准文档、证据三层

一次上传同时产出三样东西，别混淆：

| 层 | 存储 | 用途 |
|---|---|---|
| 原文 | `SourceFileStorageService`（文件系统/对象存储） | 溯源、重解析、用户下载 |
| 标准文档（standard） | MySQL + ES（`DOCUMENT_STANDARD_UPSERT` 事件） | 结构化正文，卡片生成与"文档级检索"读它 |
| 切片 + 证据（ContentUnit/Evidence） | MySQL + ES/Milvus（`DOCUMENT_CHUNKS_UPSERT`） | 向量/关键词检索的基本单位 |

证据（Evidence）是**不可变**的：一旦生成就带 `evidenceHash` 与 `source_version`，引用它的 Claim/Card 不会因原文改动而"漂移"。这是"答案可审计"的地基（迁移脚本 `V20__immutable_evidence_graph_binding.sql`、`V21__exact_evidence_binding.sql`）。

### 2.7 失败与重试语义

```java
// DocumentIngestionService.java:284-298  catch 块
observabilityService.fail(run.getId(), "FAILED", ex);
documentMapper.updateDocumentState(failedDocument, "FAILED", stageHolder[0], msg, retryCount);
publisher.emitUser(PlatformEventTypes.INGESTION_FAILED, ..., detail);
```

- `stageHolder` 是数组而不是普通变量：Java 匿名内部类不能捕获可变局部变量，用 `String[]` 让 catch 块能读到"死在哪一阶段"（`PARSE/CHUNK/EXTRACT/READY`）。这是常见的 Java 8 写法，别误改成 `String`。
- `IngestionRun` + `IngestionObservabilityService` 记录每阶段事件，前端 `IngestionLogPanel` 直接消费。
- 用户可见的重试入口：`DocumentIngestionService.retry(documentId)`（:124），走同一条链，`retryContext` 保留原身份。

---

## 3. 场景三：从文本到知识 —— 抽取、实体归一、版本与生命周期

### 3.1 抽取（Extraction）

`KnowledgeExtractionService.extract()`（`:106-107`）：

```java
Map<String,Object> response = aiClient.post(AiApiPaths.EXTRACT, request);
```

- 输入：文档 + 切片 + 权限标签 + 权威度 + 场景的 `extraction` 配置（`KbScenarioService.extraction(kb)`）。
- 输出：`cards`（Knowledge Card，`KC_`）与 `claims`（Claim，`CLM_`）。
  - **Card**：面向人的知识条目（一段可复用的结论/步骤）。
  - **Claim**：面向机器的可验证断言（subject/predicate/object），冲突检测与图谱都基于它。
- 落库后各发一条 outbox 事件：`CARD_CREATE:<code>:1`（`:274`）、`CLAIM_CREATE:<code>:1`（`:327`），再写图谱批量事件（`:144` `GRAPH_BATCH_UPSERT`）。

**降级设计**：`EntityResolutionService.resolveAndMerge()` 在 AI 不可用时标记 `degraded=true` 继续导入 —— 实体归一是**质量步骤，不是正确性闸门**。
理解这一点，才能判断哪些告警需要立刻处理、哪些可以排队。

### 3.2 实体归一（Entity Resolution）

- 自动：`EntityResolutionService.resolveAndMerge()` 归一化 mentions → 调 `/ai/entity-resolve` → 在 `transactionRunner.retryingOnDeadlock` 内先 `reassignCardEntityLinks/reassignClaimEntityLinks` 改指，再 `updateEntityStatus(..., "MERGED")` 退役重复节点。
  **顺序很重要**：先搬链接再退役，否则引用会指向已废弃实体。
- 人工：`EntityGovernanceService`（别名增删、`merge`、`updateStatus` 把误合并的实体改回 `ACTIVE` 即撤销）。状态白名单 `ALLOWED_STATUSES`（`:29`）。
- 所有合并都在死锁重试事务里 —— 批量改指向是典型的死锁高发写集。

### 3.3 版本与生命周期

- `KnowledgeLifecycleService` / `KnowledgeGovernanceService`：状态机（草稿 → 待审 → 已批准/生效 → 废弃）。
- 只有 `APPROVED/EFFECTIVE` 才进检索（本地回退通道 `hybrid.allowed` 明确按 status 过滤）。
- 状态变化通过 `outboxService.enqueueObjectStatus()`（`IndexOutboxService.java:88-99`）以 `OBJECT_STATUS_CHANGED` 传播到索引侧，幂等键含 `operationId`，保证"同一次操作重复点击"只生效一次。

### 3.4 派生知识（Derivation）

`DerivationRuleCatalog` / `KnowledgeDerivationService` / `DerivationScheduler`：按规则从已有 Card/Claim 派生新知识（如"按期汇总"、"按实体聚合"）。
设计上它与"摄入"解耦：派生结果仍是普通 Card/Claim，走同一套审批、索引、权限通道 —— **没有第二条写入路径**，这是保持权限一致性的关键。

---

## 4. 场景四：一次提问 —— 检索与生成主链路（本工程最难的一条链）

### 4.1 分层：Java 只做"编排 + 落库"，算法全在 Python

```
KnowledgeQueryController.query()            :87-94
   ├─ accessService.assertRead(kb)                     知识库级读权限
   ├─ aclContextResolver.resolve(permissionTags)        行级标签（三态）
   └─ KnowledgeQueryService.query()          :62-91
         ├─ idGenerator.next("Q_")
         ├─ scenarios.queryOverride(kb)  → policyOverride
         ├─ aiClient.post(/ai/query, payload)
         ├─ traceMapper.insertTrace(...)                完整 trace 落库
         ├─ persistAnswerEvidenceAssessment(...)        完整性/忠实度
         ├─ persistAnswerQualification(...)             逐 claim 资格判定
         └─ announceRetrieval(...)                      平台事件（区分 USER / API_KEY）
```

`AiClient.post()` 就是一行 `restTemplate.postForObject(...)`，**没有重试、没有包装**。
超时在 `RestConfig`/`KnowledgeProperties`：连接 10s、读 180s（LLM 可能很慢）。
要加重试/熔断时，改这一个点即可（这是刻意的"唯一出口"设计）。

### 4.2 Python 侧流水线（`ai-service/app/services/query_engine.py`）

`QueryEngine.query()` 的阶段顺序（改任何一步都要清楚它在哪一层）：

```
load_policy + merge_override      场景模板 + 本次 override 合成策略
  → rewrite                       查询改写（同义词 + LLM 变体）
  → route                         意图路由（关键词最长匹配 + priority）
  → 通道生效集                    决定本次启用 bm25/vector/card/claim/graph 哪些
  → recall（多通道并行）          每通道召回，召回量 ≥ topK 即停（有界重试）
  → RRF fusion                    加权倒数排名融合
  → chunk 保底                    _chunk_candidate_floor：候选不足时用切片补齐
  → quality/chunk boost           乘性加成（质量信号 + 标题命中）
  → rerank                        asyncio.to_thread + Semaphore 限并发，失败降级保序
  → context + citation            组装上下文与引用（_context）
  → LLM generate                  生成答案
  → 三道门（gate）                证据门 / 结构化门 / 资格门
  → stages 时间线                 rewrite/route/recall/acl_filter/fusion/rerank/context/llm/citation
  → render_answer                 按 answerFormat 渲染（最后一步！）
```

**为什么 `render_answer` 放最后**：答案的"结构化校验"（structured gate）必须作用在模型输出的原始结构上；先渲染成 Markdown/HTML 再校验就无从校验了。

### 4.3 融合算法：加权 RRF（`retrieval/hybrid.py`）

```python
# hybrid.py rrf()  L575
scores[doc_id] += weight / (k + rank)     # k = policy.fusion.rrfK
# 再 heapq.nlargest(top_k)，并保留 channelScores / channelRanks / matched_channels
```

- 通道权重来自 `channel_weights(policy)`；每通道召回预算来自 `recall_sizes(policy)`。
- RRF 的选择理由：各通道分数不可比（BM25 是无界分数、向量是余弦），**只有排名可比**，所以融合排名而非融合分数；`k` 控制"高位的优势衰减"。
- 融合之后才做乘性加成：`qualityBoost`（新鲜度 365 天半衰期、质量信号缺失取中性）与 `chunkBoost`（标题命中 + 中文 bigram/拉丁词覆盖）。

### 4.4 双检索引擎与降级（`hybrid.search_with_diagnostics`）

```
检索模式 RETRIEVAL_MODE ∈ { auto, external, dual }   （core/config.py:77）
  auto/external → 走 ES(bm25) + Milvus(vector)
  失败时按 settings.retrieval_external_required 决定是否降级本地
```

- ES：`elasticsearch_provider.search()` 的 filter = 知识库 + ACL + document_id。
- Milvus：`milvus_provider.search()` 的 filter 用 `json_contains_any(permission_tags, [...])`。
- 诊断信息里明确标出 `PRE_TOPK` —— 表示 ACL 是**在 topK 之前下推**的，不是先召回再过滤。
  这条决定了"某用户看不到某文档"是过滤生效，而不是排序靠后被截断。

### 4.5 答案的门与评分

| 环节 | 代码 | 作用 |
|---|---|---|
| 证据门 | `answer_evidence_gate.py` | 结论必须有证据支撑，否则降级为"仅检索结果" |
| 结构化门 | `structured_answer_gate.py` | 答案必须能解析成约定结构（供前端分块渲染） |
| 资格门 | `answer_qualification.py` + `answer_claims.py` | 逐 claim 判定是否被证据支持，落 `t_answer_claim_assessment` |
| 冲突检测 | `conflict_detector.py` | 按 `(subject, predicate)` 分桶后组内两两比较（避免 O(N²)）；subject/predicate 同而 object 异才计冲突；`DISJOINT` 视为版本更替直接跳过；极性冲突 0.92 / 数值冲突 0.68，`OVERLAPPING` ×0.85 |
| 渲染 | `answer_formatting.py` | `default/markdown/text/html` 四格式；Markdown 追加"### 引用原文"（模型已写则不去重追加）；HTML 先 escape 再出标签（防注入） |

### 4.6 Trace：可观测性是产品功能，不是日志

`KnowledgeQueryService.toTrace()`（`:185-214`）把 `rewrittenQueries / router / recall / fusion / rerank / selectedContext / citations` 全部存 JSON，
延迟优先取 trace 自带的 `totalLatencyMs`，`stages` 单独成列。
前端 `QueryTracePanel` + `LatencyWaterfall` + `ScoreBreakdownPanel` 直接消费它。

> 这就是"为什么回答慢/为什么没答上来"的标准排查入口：**先看 trace 的 stages 时间线，再看 score breakdown 的 rrfContribution**。

### 4.7 评测：把"答得好不好"变成可回归的数字

`EvaluationService`（纯 Java，不依赖外部）：

- 模式：`RETRIEVAL`（只打 `/ai/retrieve`，不含 LLM 延迟）/ `FULL`（打 `/ai/query`）。
- 指标：`hit@1/3/5`、`recall@5`、`MRR`、`NDCG@10`、`latency P50/P95`、`citationAccuracy`、`faithfulness`。
- 候选身份匹配很宽松：`candidateAliases()` 里 `objectCode / unitCode / claimCode / id` 任一命中即算对（避免编码变更导致假失败）。
- 回归判定 `applyBaseline()`：与基线比 hit@1/MRR/recall@5，延迟超 `LATENCY_REGRESSION_RATIO` 也判 `REGRESSED`。
- 同步执行（`EvaluationController.java:82-86`，注释说明"宁可阻塞也不给半写的运行"）；失败用例只标 `FAILED` 不拖垮整轮；`hitAt5==0` 的用例会额外发 `KNOWLEDGE_GAP_DETECTED` 事件 —— 把"知识缺口"变成治理输入。

---

## 5. 场景五：权限 —— 从登录到 ES 过滤器的完整链路

### 5.1 认证：两套体系

| 通道 | 实现 | 位置 |
|---|---|---|
| 会话 | `Authorization: Bearer` 或 `X-Auth-Token` → `AuthService.authenticate()` → `AuthContext.set()` + MDC | `config/AuthInterceptor.java:56-101` |
| API Key | `kos_ak_` 前缀，库里只存 SHA-256（明文仅返回一次） | `service/ApiKeyService.java:52-71 / :151` |

拦截器顺序（`WebMvcSecurityConfig.java:27-31`）：
`AuthInterceptor(order 1, /api/**)` → `KnowledgeBaseAccessInterceptor(order 2, /api/knowledge/**)` → `PermissionInterceptor(order 3)`。
`/api/v1/external/`、`/api/mcp` 在白名单里（`AuthInterceptor:40-41`），它们走 API Key。

高危索引运维另有旁路：`OpsRbacFilter`（仅 `/api/knowledge/index/`），`X-Ops-Token` 映射 0/1/2/3 级，GET=1、replay/skip=2、写=3。

### 5.2 授权三层，越权防御也在三层

1. **接口层（路径 → 权限码）**：`PermissionInterceptor.java:31-48` 的规则表，区分 `WRITE_ONLY` 与 `ALL_METHODS`。
   `common/Permissions.require()` 抛 `SecurityException` → 全局映射 403。
   角色降级策略很关键：`UserRole.of()` 对未知/空角色一律降为 `KNOWLEDGE_VIEWER`（最小权限），兼容旧 `EDITOR/VIEWER`。
2. **对象层（知识库可见性）**：`KnowledgeBaseAccessService.canRead()`：
   `PUBLIC` 全员 / `PRIVATE` 仅 owner / `GROUP` 需群组成员 / `ADMIN` 直通。
   浏览员即使对公开库也 `canWrite == false`。
3. **数据层（行级标签）**：`AclContextResolver.resolve()` 的**三态语义**（注释 `:34-46` 写明这是修过的坑）：

   | resolve 结果 | 语义 |
   |---|---|
   | `null` | 无约束（无身份上下文或管理员）→ 全放行 |
   | `[]` 空列表 | 有身份但无标签 → **只能看公开** |
   | 非空 | 公开 ∪ 命中任一标签 |

   标签来源是 `resolveAclPermissionTags`（subject ∪ role ∪ department）。

### 5.3 标签如何下推到三个存储

| 存储 | 写法 | 位置 |
|---|---|---|
| ES | `None` 不加任何 ACL 子句；否则 `must_not exists` OR `terms` 交集 | `elasticsearch_provider.py:58-73` |
| Milvus | `json_contains_any(permission_tags, ["__public__", ...])`，公开资源写哨兵 `__public__` | `milvus_provider.py:309-323 / :173` |
| Neo4j | 节点与关系都按 `permissionTags` 过滤，空标签=公开 | `graph_provider.py:617-677` |

> 注意：**每条被索引的数据都必须带 `permissionTags`**，写入侧漏了这一列，查询侧就必然漏数据或漏权限。
> 新增索引通道时，`permissionTags` 是强制字段而非可选字段。

### 5.4 ACL 变更如何传播（最容易被改坏的一段）

```
AclAdministrationService.changeResourceAcl()  :58-109
   ├─ 校验类型 SOURCE / CARD / CLAIM
   ├─ updateResourcePermissionTags
   ├─ 写 t_acl_change（changeCode = ACL-UUID，含 old/new tags 与 impact）
   └─ outbox.enqueue(..., "ACL_CHANGED", ...)
         ▼ IndexOutboxWorker:152-158
           requiresAuthoritativeRebuild ? REBUILD_REQUIRED : COMPLETED
```

需要重建时走 **三段式编排** `AclRebuildOrchestrator`：
`start()`（建 `ACLR-` 作业 → `schemaService.rebuild(kb, version)` 建**绿版本**，不动 active 指针 → `VALIDATING`）
→ `qualify()`（带 manifestHash / entityCount / relationCount 调 `/ai/index/schema/acl-qualify/{kb}`，`qualified=true` 才 `READY/PASSED`）
→ `activate()`（**双重闸门**：READY + PASSED 才切换指针）。

为什么必须 qualify 之后再 activate：权限重建是"全量重算"，中途失败若已切指针，全库可见性就错了。**先验收后切换**是这个编排存在的全部理由。

审计：`AclAuditService.record()` 落 `t_acl_access_audit`（带 traceId + URI），失败只记日志不阻断主链路。
判定语义有区分：`READ_DETAIL` / `DENY` / `ACL_FILTERED_OR_NOT_FOUND` —— 最后一条避免"过滤掉"被误报成"越权尝试"。

---

## 6. 场景六：索引运维 —— 蓝绿、重建、漂移、补偿

### 6.1 索引 Schema Release（蓝绿切换）

`IndexSchemaService`：`rebuild(kb, version)` → `activate(releaseId)` → `rollback(releaseId)`（rollback 就是 activate 旧 release，`:147-149`）。
表 `t_index_schema_release`（迁移脚本 `V5__index_operations_and_schema_release.sql`）。

要点：**rebuild 只建新版本，从不直接改 active**；切换是一次指针更新，因此可回滚。
ACL 重建（`AclRebuildOrchestrator`）复用同一套机制，所以在"权限大改"场景下也能回滚。

### 6.2 权威图谱重建

`AuthoritativeGraphGenerationService.rebuild()`：读权威快照 → 逐行 canonical JSON 做 SHA-256 → POST `/ai/index/schema/graph-rebuild/{kb}` → 落三张 manifest（graph / facet / evidence）。
真正写 Neo4j 的是 `graph_provider._neo4j_rebuild_authoritative_graph()`：先 `DETACH DELETE` 同 generation 的 `EntityGen`，再分类写入；`activate_generation()` 才切指针。
Neo4j 关闭时降级为 `NullGraphProvider`（`get_graph_provider()`）—— 所以 Neo4j 是**可降级组件**，不是硬依赖。

### 6.3 漂移检测与补偿

`IndexOutboxService.drift(kb, aiClient)`（`:148-160`）：

```java
long authoritative = indexMapper.countIndexableObjects(kb);          // MySQL 权威计数
Map<String,Object> external = aiClient.get(INDEX_EXTERNAL_STATS + kb); // ES / Milvus 计数
result.put("elasticsearchDelta", delta(authoritative, external.get("elasticsearch")));
result.put("milvusDelta", delta(authoritative, external.get("milvus")));
```

处置手段按严重度递进：`replay(eventId)`（重投单条）→ `skip(eventId)`（跳过不可恢复事件，让后续版本继续）→ 全量 `rebuild`。
所有 outbox 状态、尝试次数、失败原因都在 `t_index_outbox` / `t_index_delivery_attempt`，指标由 `IndexOutboxMetrics` 暴露（backlog / 成功率 / 延迟 / 死信 / 锁回收数）。

### 6.4 死信的边界：不回滚权威数据

```java
// IndexOutboxWorker.java:138-141
// An index failure must not touch the authoritative model: the document is only
// flagged DIRTY so a rebuild can pick it up, and the knowledge it produced stays
// queryable from the relational store (design §16.3).
markDocumentDirty(event, message);
```

这是最容易"好心办坏事"的地方：看到索引失败就想回滚业务数据 —— **不要**。文档标 `DIRTY` 后由重建兜底，权威数据保持可查。

---

## 7. 场景七：图谱与证据溯源 —— 答案为什么"可信"

### 7.1 两类图读路径

`KnowledgeReadService.graph()`（`:65-86`）：

```
先 GET /ai/graph/{kb}/{code}?depth&limit&permissionTags
   → edges 非空：source = "neo4j"
   → 否则回落 AuthoritativeGraphNeighborhoodService.neighborhood()，source = "authoritative"
```

**为什么要回落**：Neo4j 是可降级组件，只依赖它会"有真实关系却画出空图"。MySQL 侧始终有权威投影，所以永远有兜底答案。
改这块时不要"简化"成只查 Neo4j。

### 7.2 邻域查询的两个实现要对齐

| 实现 | 位置 | 约束 |
|---|---|---|
| Neo4j Cypher | `graph_provider.py:617-677` | `MATCH p=(n)-[*1..d]-(m)`，depth 夹到 1..3，limit 1..200，节点与关系都按 permissionTags 过滤 |
| MySQL BFS | `AuthoritativeGraphNeighborhoodService.neighborhood():36-136` | hops ≤ 3、cap ∈ [10,400]，聚合 6 类边（CARD-EVIDENCE / CLAIM-EVIDENCE / EVIDENCE-SOURCE / CLAIM-ENTITY / CARD-ENTITY / ENTITY-ENTITY），`loadNodeMetadata()` 按类型**批量**补标题/状态/置信度（避免空 `IN()`） |

`overview()`（`:146-227`）按度数取 top 节点，并用 `isSupportNode()` 排除 `EVIDENCE/SOURCE` —— 否则支撑性节点会挤爆版图。

### 7.3 证据作用域投影（本工程最有特色的设计）

证据**不以节点身份进入构图**，而是以 `facet → evidence → source` 的**绑定**投影：

```
GraphMapper.xml:14-21  listAuthoritativeGraphEvidenceBindings
  → facetCode / evidenceCode / sourceCode / sourceVersion / permissionTags / evidenceHash / locator
```

含义：图谱节点是"知识对象"，证据是它**可溯源的作用域绑定**，且自带来源版本的权限标签。
好处：证据内容不可变（hash 固定），而它的可见性可以随权限变更而调整 —— 两者解耦。

### 7.4 多跳与引用安全

`graph_provider` 提供 `_neo4j_generation_multihop`、`_neo4j_facet_multihop`、`_neo4j_citation_safe_multihop`；
路由层暴露 `/ai/graph/.../multihop`、`/facet-multihop`、`/citation-safe-multihop`。
`citation-safe` 的语义是：**每一步跳转都保留可引用证据**，不允许出现"无出处的中间结论"。

---

## 8. 场景八：对外集成 —— REST / MCP / SSE 一套规则三个通道

`ExternalAccessService` 的注释写明设计意图："一套规则、三个通道"。
改鉴权逻辑时**必须三处一致**，否则 MCP 能读到 REST 读不到的东西。

### 8.1 API Key 与范围

```java
// ExternalAccessService
extractSecret()  :80-93   支持 X-API-Key 头 / Authorization: Bearer / query apiKey
authenticate()   :39-50   校验 key → 立即 rateLimiter.check(key)
assertScope()    :52-56   LIST / QUERY / SEARCH / READ_OBJECT / INGEST（READ 兼容旧 Key，默认为 READ）
resolveKnowledgeBase() :65-78  绑定 kb 的 key 传别的 id 直接抛 SecurityException
                               未绑定的 key 再走 assertExternalAccess（仅公开库）
```

限流 `ExternalRateLimiter.check()`：按 key 维度的 60 秒滑动窗口（`ConcurrentHashMap<Long, Deque<Long>>`），默认 60/分钟，超 2000 个 key 触发 `evictIdleWindows()`。
注意注释明确写了：**内存计数，横向扩容需换 Redis** —— 多副本部署时这是已知限制，不要假装它是全局限流。

### 8.2 MCP（Model Context Protocol）

`McpServerService`：协议版本 `2025-06-18`，使用无会话 JSON 响应的 Streamable HTTP；serverInfo `knowledge-os/1.2.0`。服务端只实现 POST JSON-RPC 请求/响应；GET 返回 405，不提供 SSE 与订阅。`notifications/*` 通知返回 HTTP 202 空响应。`McpController` 审计同时记录 `transportStatus` 与显式 `rpcSuccess`/`rpcErrorCode`。
`handle()`（`:44-84`）是 JSON-RPC 2.0 分派器：`initialize / ping / tools/list / tools/call / resources/list / resources/read`。无 `id` 的 `notifications/*` 由服务返回 `null`，HTTP 入口据此返回 202 空响应。

异常映射值得记住：`SecurityException → -32001`、`IllegalArgumentException → -32602`、其它 `-32603`。
10 个工具（`toolsList()` `:106-132`）：`knowledge_query / knowledge_search / list_knowledge_bases / list_knowledge_cards / get_knowledge_card / list_claims / get_claim / get_evidence / graph_neighbors / ingest_text`，每个都有 JSON Schema `inputSchema`。

**Facade 原则**：`ExternalKnowledgeFacade` 全部委托控制台同一批 Application Service。
即外部调用方**拿不到 UI 之外的能力**。`ingestText()` 甚至用内存 `BytesMultipartFile` 复用上传管线 —— 保证摄入链路只有一条。

### 8.3 流式

- 平台事件 SSE：`/api/internal/platform/events/stream`（Java `PlatformEventHub`，`SseEmitter(0L)` 不超时 + heartbeat）。
- 外部流式：`/v1/external/stream/query`、`/v1/external/stream/events`。
- 问答主链路**不是流式**（普通 POST），前端摄入进度是轮询（500ms/2s）—— 这是现状，做流式改造属于新需求而非 bug 修复。

---

## 9. 场景九：平台事件与任务中心 —— 横切的"神经系统"

### 9.1 事件模型

`PlatformEventPublisher` 定义来源（`UI / REST / MCP / SSE / SYSTEM`）与 actor（`USER / API_KEY / SYSTEM`），
提供 `emitUser / emitExternal / emitSystem`，以及带 `EventTaskContext` 的重载（把异步任务 id / 对象 id / 版本号 / 重试次数写进事件）。

`EventTaskContext` 存在的意义：一次摄入会发 `INGESTION_DOCUMENT_RECEIVED → CHUNK_CHANGED → EXTRACTION_* → INGESTION_COMPLETED/FAILED`，
订阅方靠 `taskId` 把它们**串成一条时间线**，而不是看一堆散落事件。

### 9.2 Hub 的实现要点

`PlatformEventHub`：`SseEmitter` + 独立 fanout 线程池（不用请求线程）；`Visibility` 接口决定每个订阅者能看到什么（**过滤是订阅者属性**，发布者总是附全量 payload）；
按 `sinceEventId` 支持断线续传；`heartbeat` 保活；`activeSubscriptions()` 便于观测。

类型清单在 `common/PlatformEventTypes.java`，可见性策略在 `PlatformEventVisibilityService`。
**新增事件类型的三步**：加常量 → 在发布点调用 emit → 确认可见性策略（否则管理员能看到全部、普通用户看到空白）。

---

## 10. 前端工程要点（想改 UI 先读这一节）

### 10.1 架构事实：没有 router，也没有 Pinia

- `main.ts` 只有 9 行：`createApp(App).use(ElementPlus).mount('#app')`。
- `App.vue`（约 51KB）是**唯一外壳 + 状态中心**：持有 `active / knowledgeBaseId / bases / q / result / batchStatus`，按 `activeSection` 切换
  `WorkbenchPage / GeneratePage / SearchPage / IntegrationPage / SystemPage` 五个一级页面。
- 导航用 `src/navigation.ts` 的信息架构表（`NAV_SECTIONS`、`NavLeaf`、`defaultLeafOf / normalizeViewKey / locate / visibleGroups / visibleLeaves`）+ localStorage 视图 key。
- 跨模块动作靠 `src/shell.ts` 的 `KOS_SHELL` InjectionKey：`App.vue` `provide` 出 `navigate / can / adminView / tabs / openEvidence / openCitation / openTrace / upload …`，子页面 `useShell()` 注入。
- 权限码在 `src/permissions.ts`（`PERM`、`hasPermission`），与后端 `Permissions` 对应。

> 因此："加一个页面"不等于加一条路由，而是：在 `navigation.ts` 注册 leaf → 在对应 Page 里挂组件 → 需要跨模块跳转时用 `shell`。

### 10.2 API 层与统一错误

- `api/knowledge-impl.ts`（真正实现）→ `knowledge.ts` → `knowledge/operations.ts`、`knowledge/base.ts`，另有 `retrieval.ts / governance.ts / connector.ts` 分类导出。
- 统一信封 `unwrap()` 解 `ApiEnvelope`；`LONG_TIMEOUT_MS = 180000`（清空/索引类接口）、连接器用 `EXTERNAL_TIMEOUT_MS / SYNC_TIMEOUT_MS / INGEST_TIMEOUT_MS`。
- `api/auth.ts` 在模块加载时注册 axios 拦截器：请求写 `Authorization: Bearer <localStorage>`；响应 401 → 清 token + `window.dispatchEvent(AUTH_EXPIRED_EVENT)`，`App.vue` 监听后回登录页。
- 错误文案统一走 `utils/feedback.ts` 的 `errorMessage()`（后端 `message` 优先 → statusText → 兜底，并拼 `traceId`）。**traceId 出现在用户提示里**是刻意设计：用户截图即可定位后端日志。
- 开发代理：`vite.config.ts` 把 `/api` 代理到 `http://127.0.0.1:8080`。

### 10.3 关键组件地图

| 领域 | 组件 |
|---|---|
| 知识库 | `KnowledgeBaseDialog.vue`（含场景 scenario 选择）、`KnowledgeListPanel.vue`、`SpaceContextBar.vue` |
| 摄入 | `DocumentIngestPanel.vue`、`IngestionLogPanel.vue`、`GeneratePage.vue`、`TaskCenterDrawer.vue` |
| 问答 | `SearchPage.vue`、`QueryPanel.vue`、`QueryTracePanel.vue`、`LatencyWaterfall.vue`、`ScoreBreakdownPanel.vue` |
| 证据 | `EvidenceViewer.vue`（先 `escapeHtml` 再 `<mark>` 高亮 `quoteText`，入口 `App.vue:134` + `shell.openEvidence`） |
| 策略 | `QueryPolicyPanel.vue`、`ChunkPolicyDialog.vue`、`RetrievalPolicyPanel.vue` |
| 评测 | `EvaluationPanel.vue`、`QualityPanel.vue`、`AnswerQualificationPanel.vue`、`RetrievalTestPanel.vue` |
| 治理 | `SystemPage.vue` 聚合 `AclAdministrationPanel / UserAdminPanel / EntityGovernancePanel / VersionGovernancePanel / OpsPanel / LifecyclePanel / ReviewPanel` |

流式仅在外部接口实验室使用：`useExternalServiceLab.ts` 用 `response.body.getReader()` + `decoder.decode(value, {stream:true})` 手撕 SSE 帧，并用 `AbortController` 取消订阅。

---

## 11. ai-service 工程要点

| 关注点 | 事实 |
|---|---|
| 入口 | `app/main.py`：`include_router` 六个 router（`routes / settings_routes / retrieval_settings_routes / query_policy_routes / derivation_routes / schema_routes`）；`lifespan` 里先 `apply_retrieval_settings()`（**控制台文件优先于 env**） |
| 中间件 | `request_context` 生成/透传 `X-Trace-Id`；统一 500 信封 `AI-500-001`；`/health`、`/ready`（回传 llm/embedding/graph/rerank/ES/Milvus 配置态） |
| 依赖注入 | 不用 `Depends`：`routes.py` 顶部模块级单例 + `functools.lru_cache` 工厂（`get_llm_provider / get_embedding_provider / get_reranker / get_graph_provider / get_elasticsearch_provider / get_milvus_provider`） |
| 可替换 Provider | `llm_provider.py`（Mock / Ollama / OpenAI 兼容）、`embedding.py`（Hash / Ollama / OpenAI + cosine）、`reranker.py`（LexicalCrossFeature / BgeCrossEncoder）、`graph_provider.py`（Null / Neo4j） |
| 配置 | `core/config.py` 的 `Settings` 全读环境变量；可被控制台覆盖并持久化的两层在 `core/runtime_settings.py`、`core/retrieval_settings.py`、`core/query_policy.py`、`core/chunk_policy.py` |
| 测试 | `tests/` 21 个文件，命名 `test_<主题>.py`（阶段号即需求阶段）；无 conftest，靠 `pytest.fixture(autouse=True)` + `tmp_path/monkeypatch` 把策略文件重定向到临时目录；`pytest.ini` 设 `asyncio_mode=auto` |

运行命令：

```bash
PYTHONPATH=ai-service python3 -m pytest -q ai-service/tests/
```

**两个约定**：① 新增 Provider 必须同时提供 Null/Mock 实现，保证"未配置也能启动"；② 任何可被控制台修改的设置都要有 `load/save/apply` 三件套，否则重启即丢失。

---

## 12. 横切机制汇总

| 机制 | 实现 | 使用时机 |
|---|---|---|
| 事务与死锁重试 | `TransactionRunner.required() / retryingOnDeadlock()` | 批量写同一批表（合并实体、批量摄入） |
| 强制事务 | `IndexOutboxService.enqueue` 的 `Propagation.MANDATORY` | 保证"业务写入 + 事件入队"原子 |
| 权限断言 | `Permissions.require()` / `KnowledgeBaseAccessService.assertRead()` / `ExternalAccessService.assertScope()` | controller 入口 |
| 统一响应 | `common/ApiResponse` + `ApiErrorCode` | 所有 `/api/**` |
| TraceId | `config/TraceIdFilter`（MDC） + ai-service `X-Trace-Id` | 跨服务串联日志 |
| 编码生成 | `IdGenerator` | 所有对外 code |
| 任务中心 | `TaskCenterService` + `tracked()` 包装 | 长耗时操作（摄入、批量、重建） |
| 指标 | `IndexOutboxMetrics`、`AlertEvaluator/AlertService` | outbox backlog/延迟/死信、告警规则 |
| 审计 | `AclAuditService`、`ExternalAuditService`、`ApiCallAudit` | 越权尝试、外部调用（含 payload 脱敏 `ExternalCallPayloadFilter`） |

---

## 13. 扩展指引：常见需求的落点

| 我想…… | 改哪里 |
|---|---|
| 新增一个接口 | `controller`（参数绑定 + 权限断言）→ `service`（编排）→ `mapper/*.xml`（SQL）→ 前端 `api/knowledge-impl.ts` + 组件；若需新权限码：`common/Permissions` + `UserRole.permissions()` + `PermissionInterceptor` 规则表 |
| 支持新的文档格式 | `ai-service/app/services/standard_document.py` 增加 `from_xxx()`，产出统一 block（`block_type/text/level/table`）；`document_parser.py` 登记；`build_units()` 不需要改（策略与格式解耦） |
| 新增检索通道 | `hybrid.py` 加 `_xxx` 召回 → `channel_weights()` 给权重 → `recall_sizes()` 给预算；**必须带 `permissionTags` 过滤** |
| 新增答案格式 | `common/AnswerFormat` + `ai-service/answer_formatting.py`（`normalize/render`），未知格式回退 `default` |
| 新增场景模板 | `KbScenarioTemplates.java` 加常量与 defaults；需要新配置段时同步改 `normalize()` 与前端 schema 展示 |
| 新增事件类型 | `PlatformEventTypes` + 发布点 + `PlatformEventVisibilityService` |
| 新增 MCP 工具 | `McpServerService.toolsList()`（含 JSON Schema） + `handle()` 分派 + `ExternalKnowledgeFacade` 委托方法 |
| 换 LLM / Embedding / Rerank | `ai-service` 对应 provider 加实现 + `get_xxx_provider()` 工厂；Java 侧零改动 |

---

## 14. 排障手册（按现象定位）

| 现象 | 第一排查点 |
|---|---|
| 上传后搜不到 | ① `t_index_outbox` 事件状态（PENDING/DEAD？）② 文档状态是否 `DIRTY/FAILED` ③ `drift()` 对比权威与 ES/Milvus 计数 |
| 上传卡在某阶段 | `IngestionLogPanel` / `t_ingestion_run` 的阶段事件；`stageHolder` 记录 `PARSE/CHUNK/EXTRACT/READY` |
| 索引事件反复失败 | `t_index_delivery_attempt` 的 message；`failOnNestedError` 会把 HTTP 200 里的 `error` 也判失败 |
| 某用户看不到某文档 | ① `AclContextResolver.resolve()` 三态结果 ② 数据是否带 `permissionTags` ③ ES/Milvus 过滤是否 `PRE_TOPK` 下推 |
| 权限改了没生效 | `t_acl_change` 的 propagation 状态；是否需要 `AclRebuildOrchestrator`（`REBUILD_REQUIRED`） |
| 答案质量下降 | trace 的 `stages` 时间线 → `ScoreBreakdownPanel` 的 `rrfContribution` → 通道权重与 `chunkBoost` |
| 答案有幻觉 | 三道门：`answer_evidence_gate` / `structured_answer_gate` / `answer_qualification`；看逐 claim 的资格判定 |
| 图谱空白 | `KnowledgeReadService.graph()` 的 `source` 字段：`neo4j` 还是 `authoritative` 回落？Neo4j 是否降级为 `NullGraphProvider` |
| 改了配置不生效 | 区分三层：`application.yml` / env / 控制台持久化文件（`runtime_settings`、`retrieval_settings`，控制台优先） |
| 慢 | trace 的 `totalLatencyMs` 与各 stage；注意读超时是 180s，AI 侧慢会直接体现为请求挂起 |

常用命令：

```bash
# 后端
cd backend && mvn -q -DskipTests spring-boot:run
# AI 服务
PYTHONPATH=ai-service python3 -m pytest -q ai-service/tests/
# 前端
cd frontend && npm run dev        # vite 将 /api 代理到 8080
# 演示数据
DEMO_SEED=true docker compose up -d   # 详见 scripts/demo/README.md
# 镜像
make release-build VERSION=1.1.0 IMAGE_PREFIX=192.168.22.6:5000/knowledge-os
make release-push  VERSION=1.1.0 IMAGE_PREFIX=192.168.22.6:5000/knowledge-os
```

---

## 15. 附录

### 15.1 关键表（按链路）

| 链路 | 表 |
|---|---|
| 知识库/场景 | `t_knowledge_base`、`t_kb_scenario`（V44）、chunk policy 表（V33） |
| 摄入 | `t_source`、`t_source_file`、`t_document`、`t_content_unit`、`t_evidence`、`t_source_version`、`t_ingestion_run` |
| 知识 | `t_knowledge_card`、`t_claim`、`t_entity`、`t_relation`、`t_entity_facet`、`t_relation_facet` |
| 索引运维 | `t_index_outbox`、`t_index_delivery_attempt`、`t_index_schema_release`（V5）、`t_index_aggregate_version` |
| 权限 | `t_acl_change`、`t_acl_access_audit`、`t_api_key`、`t_user_group` |
| 问答 | `t_query_trace`、`t_answer_qualification`、`t_answer_claim_assessment` |
| 评测 | `t_evaluation_dataset`、`t_evaluation_case`、`t_evaluation_run`、`t_evaluation_result` |
| 事件 | `t_platform_event` |

### 15.2 关键配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `ai-service-base-url` | `http://127.0.0.1:12000` | `application.yml:53` |
| 连接/读超时 | 10s / 180s | `KnowledgeProperties` |
| `indexing.outbox.enabled` | `true` | 关掉即停止索引投递 |
| `indexing.outbox.batch-size` | 20（夹到 1..200） | `IndexOutboxWorker:69` |
| `indexing.outbox.poll-interval-ms` | 1000 | 轮询间隔 |
| `indexing.outbox.lock-timeout-seconds` | 300（最小 30） | 陈旧锁回收阈值 |
| `indexing.outbox.retry-base-seconds` | 2 | 指数退避基数，上限 900s |
| `indexing.outbox.max-attempts` | 8 | 超过即死信 |
| `RETRIEVAL_MODE` | `auto` | `auto / external / dual` |
| `DEMO_SEED` | `auto` | `auto / true / false`；`index-worker` 必须 `false` |

### 15.3 必读的现有文档

`docs/development/ARCHITECTURE-CODE-WALKTHROUGH.md`（架构走查）、`docs/development/CODE-STANDARDS.md` / `docs/development/CODE-STYLE.md`（编码规范）、
`docs/development/DEBUGGING-TESTING-GUIDE.md`（调试测试）、`docs/development/DEVELOPER-GUIDE.md`（环境搭建）、
`docs/design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md`、`docs/archive/V1.1-RETRIEVAL-MIGRATION.md`（两条核心链路的设计报告）、
`docs/ACL-PHASE1..13`（权限体系逐阶段设计）、`scripts/demo/README.md`（演示数据）。

### 15.4 给新人的三条忠告

1. **不要绕过 outbox 直接写 ES/Milvus**。任何"顺手同步一下"的代码都会破坏"权威 vs 派生"的边界，且在重建后消失。
2. **任何进入索引的数据都必须带 `permissionTags`**，任何查询都必须带权限过滤。这是安全底线，不是可选项。
3. **跨服务调用只有 `AiClient` 一个出口**。加重试、熔断、埋点都改这里；在业务代码里直接发 HTTP 会绕过超时与 trace 约定。
