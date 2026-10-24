# AKB 企业知识库代码审查与改进实施方案

> **审查对象**：`https://github.com/jhzhfree/akb.git`  
> **目标分支**：`feature/octop-mcp-integration`  
> **审查基线**：`3328153d2294a85beb2d27ff3ec34c6be84853ff`  
> **报告日期**：2026-10-24  
> **审查方式**：GitHub 仓库目录、架构文档和关键 Java/Python 源码静态阅读；**未执行** Maven、pytest、前端构建、集成环境或生产压测。  
> **范围说明**：本报告详细展开上一轮识别的优化点，不声称已对全部 1,087 个 Git 对象逐文件验证。文中的风险需通过所列测试复现或排除。

## 0. 执行摘要

AKB 已形成 **MySQL 权威数据 + Java 治理与事务 + Python 算法 + Elasticsearch/Milvus/Neo4j 可重建索引 + MCP 外部接入** 的基本架构。当前优先任务不是简单新增功能，而是落实知识可信性、事务边界、MCP 兼容性、任务可恢复性及可观测性的一致约束。

### 问题总览与处理顺序

| ID | 优先级 | 问题/改进项 | 当前证据级别 | 预期结果 |
|---|---|---|---|---|
| AKB-01 | P0（风险） | `DETERMINISTIC` 自动批准的可信性校验 | 已确认使用标记判定；尚未证明上游可伪造 | 自动批准必须服务端验证证据 |
| AKB-02 | P1 | 查询接口 `@Transactional` 包含远程 AI 调用 | 源码已确认 | HTTP/LLM 调用不被数据库事务包围 |
| AKB-03 | P1（兼容风险） | MCP 声称 Streamable HTTP 与当前 Controller 的 POST JSON 实现需要对齐 | 源码已确认 POST JSON 入口；协议兼容待测 | 实际支持范围明确且有兼容测试 |
| AKB-04 | P1 | MCP JSON-RPC 错误与 HTTP 200 审计成功口径混淆 | 源码已确认 | 区分传输、协议及业务结果 |
| AKB-05 | P1（恢复风险） | 抽取持久化后阶段失败仅设置 PARTIAL，须验证补偿闭环 | 源码已确认 PARTIAL 机制；恢复能力待核验 | 失败可追踪、幂等补偿、重新验收 |
| AKB-06 | P1 | Outbox 多工作线程、版本顺序、重试及死信的故障验证 | 机制已存在，边界待测 | 故障注入通过且索引不倒退 |
| AKB-07 | P1 | 检索状态策略与设计文档不一致 | 两份文档矛盾已确认 | 默认策略与 UI、配置、测试一致 |
| AKB-08 | P2 | `KnowledgeExtractionService` 职责过多 | 文件约 62 KB、方法覆盖多领域 | 业务逻辑可分层、可单测 |
| AKB-09 | P2 | `KnowledgeGenerationOrchestrator` / `IngestionOrchestrator` / `QueryPipeline` 过薄 | 代码已确认 | 明确编排职责或消除无效抽象 |
| AKB-10 | P2 | Map 驱动协议和业务结果缺少强类型合同 | 关键调用链已确认 | DTO/Schema、字段版本与边界校验 |
| AKB-11 | P2 | 性能容量及阻塞 I/O 的实测准入 | 有性能设计与测试文件，未实际执行 | 基准、压测及长期回归门禁 |
| AKB-12 | P2 | 文档、代码、测试与发布准入同步 | 文档存在历史描述与当前代码不完全一致 | 以代码事实为准持续维护文档 |

**证据分级**：①“已确认”表示在该提交代码/文档中直接观察到对应实现或差异；②“风险”表示存在合理失效路径，但未经端到端测试证明已发生；③“建议”表示工程治理措施，不代表当前代码一定有 bug。

## 1. AKB-01：确定性知识自动批准的可信边界（P0）

### 1.1 源码证据

- `backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java`
- `isDeterministic(Map<String,Object>)` 判定 `source.get("derivation")` 是否等于 `DETERMINISTIC`。
- 在 `persistClaims(...)` 中，`documentId != null && isDeterministic(source)` 时，新 Claim 状态为 `APPROVED`；否则为 `UNCERTAIN`。Card 使用相似的逻辑。
- 对文档来源执行 `resolveEvidence(...)`，无证据则拒绝；但“存在证据”并不自动等同“知识语义被证据准确支持”。

### 1.2 问题与风险

如果 AI/规则模块可以输出 `derivation=DETERMINISTIC`，而 Java 未独立核验该标记来源，则模型可能把推断、错配或越界的结论直接批准。**这是一项待验证的信任边界风险，不代表已经证实模型能够绕过审批。**

例如：原文写“员工试用期结束后可申请调休”，模型错误输出“所有员工可直接申请调休”，即使附上同一段证据，也不应自动获得批准。

### 1.3 推荐改造

1. 在 AI 输出与治理写库之间建立 `DeterministicExtractionVerifier`（服务端可信模块）；模型输出仅作为“申请确定性”的候选。
2. 校验项：可信解析来源、证据 ID 属于当前知识库/文档/有效 Revision、证据文本锚点可定位、抽取值与原文内容及规范化规则匹配、数字/时间/单位/条件完整性。
3. 在持久化前产出服务端验证结果：`verifierVersion`、`verified`、`reasonCodes`、`evidenceAnchors`；禁止直接信任模型提供的 `verified` 字段。
4. 建议自动批准白名单：明确的字段抽取、原文直接陈述，且无跨句推理、主体歧义、冲突及权限异常。模型总结、推断、跨文档派生默认 `UNCERTAIN`。
5. 复核状态变更的原子性：Knowledge Card/Claim 状态、审核流水、Outbox 在同一业务事务内记录。
6. 将 `DETERMINISTIC` 判定原因持久化，便于管理员审计。

### 1.4 测试及验收

- 伪造 `derivation=DETERMINISTIC` 但内容与原文不一致 → 不得 `APPROVED`。
- Evidence 属于其他 KB、失效 Revision、空引用 → 拒绝或待审。
- 原文“至少 3 年”被抽取为“3 年”但丢失限定词 → 不得自动批准。
- 合格的原文直接字段抽取 → 可批准且有审计轨迹。
- 同一文档重跑，批准状态及证据引用的行为明确、可重复。

**完成定义**：自动批准不受不可信输出标记单独控制；状态与证据均可复现核查。

## 2. AKB-02：知识查询的数据库事务跨越 AI 网络调用（P1）

### 2.1 源码证据

`backend/src/main/java/com/jh/knowledgeos/service/KnowledgeQueryService.java` 的 `query(...)`、`retrieve(...)`、`answer(...)` 入口标记了 `@Transactional`，内部最终调用 `runQuery(...)` → `aiClient.post(AiApiPaths.QUERY, payload)`，之后执行 Trace 落库。

### 2.2 影响

远程 AI 查询时间可能较长；事务从方法进入到返回持续存在。实际连接是否已取得取决于事务管理器、持久化访问和连接池实现，不应断言每次请求必然占满连接。但这会提高连接占用、超时、锁范围和失败回滚风险。

### 2.3 推荐改造

将对外 AI 调用与短事务拆分：

```text
prepare request / ACL authorization / scenario settings
       ↓（无写事务）
POST Python /ai/query（含超时、取消、限流）
       ↓
QueryTracePersistenceService.save(...)（REQUIRES_NEW 或显式短事务）
       ↓
发布检索完成事件（明确与 Trace 提交的关系）
       ↓
返回结果
```

注意：`@Transactional` 放在同一类 `private` 方法或通过 `this` 自调用，通常不会触发 Spring AOP 事务代理。应使用独立 Bean 或 `TransactionRunner`。

保留业务语义：AI 成功但 Trace 写入失败时，需定义是返回回答、降低可观测性等级、还是严格失败；不得无意识改变既有 API 合约。

### 2.4 验收

- AI 服务故意延迟时，数据库事务持续时间不随 LLM 延迟增长。
- 并发下连接池占用与 P95/P99 延迟可测、优于旧版。
- Trace 写入失败或客户端取消时，状态和重试行为明确。
- `query`、`retrieve`、`answer` 和流式接口行为一致。

## 3. AKB-03：MCP 协议传输能力与真实兼容性（P1）

### 3.1 源码证据

- `backend/src/main/java/com/jh/knowledgeos/controller/McpController.java`
- `@RequestMapping("/api/mcp")` + `@PostMapping(consumes=application/json, produces=application/json)`。
- `McpServerService` 声明 `PROTOCOL_VERSION = "2024-11-05"`，实现 `initialize`、`ping`、`tools/list`、`tools/call`、`resources/list/read`、`notifications/initialized`。
- `docs/api/OCTOP-INTEGRATION.md` 标记 Transport 为 `Streamable HTTP`。

### 3.2 风险解释

目前源码观察到单 POST JSON-RPC 入口；这不等于已经覆盖新版本 Streamable HTTP 的全部行为。`null` 通知返回空 JSON `{}` 也是需要重点验证的协议行为。Octop 如果采用兼容客户端可能能正常连通，但与其它 MCP 客户端的兼容性不能由文档文字保证。

### 3.3 改造建议

1. 明确支持的 MCP 协议版本和传输模式；如果当前仅支持 JSON-RPC POST，则文档如实描述。
2. 与真实 Octop MCP Client、标准 MCP SDK 和至少一个第三方客户端执行能力探测。
3. 按目标版本要求实现必要的 Accept/Content-Type、错误、通知、初始化和会话行为；确需 GET/SSE 再增加，不要只为满足“Streamable HTTP”名称盲目扩展。
4. 对 `initialize` 的 `protocolVersion` 建立协商或明确兼容拒绝策略。
5. 为 `tools/list` 权限过滤、资源读取、参数校验、协议错误码增加自动化测试。

### 3.4 验收

- Octop 可完成连接探测、初始化、工具发现、受限工具调用。
- 非授权工具不可见，直接调用仍拒绝。
- malformed JSON-RPC、空 id、通知、未知方法均符合所声明协议版本。
- 文档中传输名称、版本、示例与测试结果一致。

## 4. AKB-04：MCP 错误与审计口径（P1）

### 4.1 证据

`McpController.handle()` 对 `mcpServerService.handle()` 返回结果后调用 `auditService.record(..., 200, ..., null, ...)`。而 `McpServerService.handle()` 捕获参数、权限及内部异常后可能返回 JSON-RPC `error` 对象。于是 HTTP 200 不能代表业务成功。

### 4.2 建议改造

新增结构化审计字段或结果对象：

```json
{
  "transportStatus": 200,
  "rpcMethod": "tools/call",
  "toolName": "knowledge_query",
  "rpcSuccess": false,
  "rpcErrorCode": -32001,
  "businessResult": "DENIED",
  "durationMs": 120,
  "callerId": "principal-or-key-id",
  "knowledgeBaseId": 12
}
```

`transportStatus` 与 `rpcSuccess` 独立。保留响应的真实 JSON-RPC 结构，避免通过改变 HTTP 状态码破坏协议；监控指标另按 RPC 成败计数。

对于敏感查询和记忆内容，延续当前记忆工具审计内容脱敏的思路，采用分类审计规则，避免在日志泄露原文、密钥、授权信息或个人数据。

### 4.3 验收

- JSON-RPC error 对象被记为业务失败，不再汇入“成功工具调用”统计。
- HTTP 鉴权失败、协议层失败、工具业务失败三类指标可分辨。
- 审计时间、principal、agent、KB、traceId 能关联。
- 原始密钥和敏感记忆不写明文审计日志。

## 5. AKB-05：抽取后处理 PARTIAL 的补偿机制（P1）

### 5.1 证据

`KnowledgeExtractionService.extract()` 在一个短事务内持久化卡片、断言和权威图数据；随后分别执行图谱 Outbox 事件、冲突检测、实体消歧与事件发布。后处理异常由 `postFailure(...)` 收集为 `postProcessingWarnings`，最终将 `extractionStatus` 设置为 `PARTIAL`，并尝试更新文档抽取摘要。

### 5.2 风险

“PARTIAL 可观测”不等于“PARTIAL 可恢复”。如果图谱事件生成成功但冲突检测失败，重跑整个抽取会重复做不必要的模型计算；如果人为直接设置 SUCCESS，则掩盖未完成的治理任务。还需要确认索引事件引用的版本、权限、证据链没有因补偿过程被覆盖。

### 5.3 推荐改造

将每一后处理阶段注册为持久化子任务：

```text
EXTRACT_PERSISTED
 ├── GRAPH_INDEX_ENQUEUED
 ├── CONFLICT_DETECTION_COMPLETED
 ├── ENTITY_RESOLUTION_COMPLETED
 └── EVENT_PUBLISHED
```

每一步记录 `runId/documentId/stage/status/attempt/idempotencyKey/lastError/updatedAt`。通过独立补偿 Worker 仅重做失败阶段，不默认重跑 AI。定义阶段依赖，避免冲突检测与实体合并互相踩写；使用稳定幂等键和数据库唯一约束保证至少一次执行不重复。

将“阶段完成”和“完整成功”分开：必须全部必需阶段完成才返回或标记 `SUCCESS`；可选阶段关闭用 `SKIPPED`，而不是静默成功。

### 5.4 验收

- 注入图谱投递登记失败 → `PARTIAL` → 自动/手动补偿 → `SUCCESS`。
- 注入实体消歧异常 → 只重做实体消歧，不重复生成 Card/Claim。
- 多次点击补偿，不产生重复图谱边、知识对象、治理记录。
- 用户页面显示“已入库但治理未完成”，而非误报全流程成功。

## 6. AKB-06：Transactional Outbox 故障一致性专项（P1）

### 6.1 已有正确设计

`IndexOutboxWorker` 在短事务中 `claimBatch()`，事务结束后通过 `AiClient.post(...)` 投递；成功记录响应并更新文档索引状态，失败执行重试/死信，定时恢复过期锁。该实现方向合理，**不应轻易替换为业务事务内同步索引**。

### 6.2 仍需验证的边界

| 场景 | 期望行为 | 验证方式 |
|---|---|---|
| 投递成功后 Worker 崩溃，未标记 SUCCEEDED | 重试幂等，不重复建立对象 | 服务端注入进程中断 |
| 同一对象 v1/v2 事件乱序 | v1 不覆盖 v2 | 制造不同延迟和重复事件 |
| 上游 AI 索引接口 200 但内部部分失败 | 不能误标 SUCCEEDED | 注入部分失败响应 |
| 死信事件阻塞后续聚合版本 | 运维可重放或明确跳过 | DEAD/replay/skip 测试 |
| 多 Worker 并发领同一个任务 | 仅一个有效 owner | 多实例并发压测 |
| ACL 收紧同时索引重建 | 不短暂暴露越权结果 | 权限变更与蓝绿发布并发测试 |

### 6.3 工程建议

- 明确 `aggregateKey + indexVersion` 的单调性及消费端检查；禁止旧事件反向更新新索引。
- 给索引端提供稳定 idempotency key，按重复投递语义设计。
- 指标：`outbox_backlog`、最老事件年龄、失败率、死信数、成功延迟、ACL 传播滞后。
- 运维接口必须做 ADMIN/OPERATOR 授权及操作审计。
- “权威 MySQL 合法但索引未完成”的状态不可误展示为检索就绪。

## 7. AKB-07：知识召回状态策略冲突（P1）

### 7.1 证据

- `README.md` 描述 `RETRIEVAL_STATUS_POLICY=permissive` 默认允许 `DRAFT/UNCERTAIN/APPROVED/EFFECTIVE`，`standard` 仅允许 `APPROVED/EFFECTIVE`。
- `docs/development/ARCHITECTURE-CODE-WALKTHROUGH.md` 写“正式问答只允许 APPROVED/EFFECTIVE”。

这属于**已确认的文档口径不一致**，但部署时实际使用哪个策略需核查 `.env`、配置加载逻辑和启动参数。

### 7.2 产品决策建议

为企业正式知识问答建议默认 `standard`；如确需草稿预览，设计单独的“预览模式”，明确显著标识并要求授权。对于 `UNCERTAIN`，可以在治理工作台查看，但不应默认作为权威答案证据。

把策略设计为：

- 全局安全底线（不可被 KB 配置放宽到越权）
- KB 检索策略（运行环境受控）
- 测试/预览模式（独立凭据和审计）
- 查询时记录生效策略、允许状态和过滤数量

### 7.3 验收

- 相同数据在 `standard` 环境不召回 `DRAFT/UNCERTAIN`。
- 管理员预览与普通用户正式问答结果具有明确区别。
- Elasticsearch、Milvus、Neo4j、Local fallback 状态过滤一致。
- README、部署示例、架构文档、测试契约统一。

## 8. AKB-08：大型服务类拆分（P2）

### 8.1 现状

`KnowledgeExtractionService.java` 约 62 KB，涵盖验证、证据解析、去重、持久化、索引投递、实体关系、冲突检测、统计、通知。

### 8.2 建议组件边界

```text
KnowledgeExtractionOrchestrator
 ├── ExtractionRequestBuilder
 ├── ExtractionModelGateway
 ├── ExtractionResultValidator
 ├── DeterministicExtractionVerifier
 ├── CardPersistenceService
 ├── ClaimPersistenceService
 ├── EvidenceBindingService
 ├── KnowledgeDedupService
 ├── AuthoritativeGraphPersistenceService
 ├── ExtractionPostProcessCoordinator
 └── ExtractionOutcomeAssembler
```

仅在职责确有独立复杂度时引入类；避免为追求类数量而制造层层代理。建议先抽离无副作用的验证、指纹、统计，随后逐步抽离数据库写入和后处理，最后收敛编排。

### 8.3 安全迁移步骤

1. 固定现有输入/输出、状态、Outbox 及异常行为的契约测试。
2. 将纯函数迁到独立模块，确保输出不变。
3. 按事务边界提取 Card/Claim/Evidence 写入组件。
4. 统一 PersistStats 成为可审计的结构化结果。
5. 迁移后逐个比对数据库快照、索引事件和 Query Trace。

**验收**：同一测试文档在改造前后生成对象/关系、拒绝原因、回执、版本与 Outbox 事件无意外差异。

## 9. AKB-09：薄编排层的设计清理（P2）

### 9.1 证据

- `KnowledgeGenerationOrchestrator.extract(...)` 直接代理 `KnowledgeExtractionService.extract(...)`。
- `IngestionOrchestrator.upload()/retry()` 直接代理对应服务方法。
- Python `query_pipeline.py` 的 `QueryPipeline` 目前主要保存 `retriever/router/rewriter` 引用。

### 9.2 两种可选路线

**路线 A：真正实现应用编排层（推荐，当流程需扩展时）**

统一负责阶段、调用顺序、超时、策略注入、可观测性、幂等、补偿入口，具体领域服务仅负责单一职责。

**路线 B：移除无用代理层**

当没有独立编排责任和扩展需求时，控制器直接调用聚焦的应用服务，减少不必要的类和调用跳转。

不要同时保留多套平行编排入口，避免同一个业务操作从不同接口进入时绕过权限/监控/补偿逻辑。

## 10. AKB-10：接口合同与数据模型强类型化（P2）

### 10.1 问题

Java 与 Python 多处使用 `Map<String,Object>`、字符串状态及自由形式 JSON。原型阶段灵活，但字段拼写、空值、协议升级与统计口径容易出现隐性偏差。

### 10.2 改造建议

- 定义外部入参 DTO：参数长度、取值区间、枚举、必填规则、字段兼容版本。
- 定义 AI Service 请求与响应 Pydantic 模型；Java 侧建立对应 DTO。
- MCP Tool inputSchema 用同一字段契约生成或测试一致性。
- 统一 `extractionStats`：`received/created/reused/rejected/evidenceLinked/reasons`，区别“新建数量”和“最终可检索数量”。
- 统一状态模型 `PENDING/PROCESSING/PARTIAL/SUCCESS/FAILED`，同时保留阶段级状态。
- 采用消费者驱动契约测试，防止 Java/Python 一方调整字段造成另一方静默误读。

### 10.3 验收

每个外部 API 和模型内部 API 都有请求/响应 schema、异常样例、兼容策略和测试；新增字段不会破坏旧客户端。

## 11. AKB-11：性能与资源边界专项（P2）

以下是**需要实测的关注点**，不是声称当前代码已经存在这些性能 bug。

### 11.1 AI 服务进程生命周期

- Embedding 与 Reranker 模型应按进程加载和复用，不要每请求重新加载。
- Neo4j Driver、HTTP Session / Client 和数据源连接池应复用，具有关闭流程。
- FastAPI `async def` 内不能直接执行大规模同步 CPU 或阻塞 I/O；使用线程/进程池或异步客户端。
- 对重排器、LLM、Embedding 实施并发阈值、超时、排队和过载保护。

### 11.2 检索索引

- 本地 JSON/矩阵回退只作开发或小规模恢复路径，不作为生产 ANN 的替代。
- 搜索 Top-K 使用候选约束与局部选择，不做无条件全量排序。
- 冲突检测优先按主语、类型、范围及候选集合筛选，避免全知识 O(N²) 比较。
- 大列表 API 逐步提供 keyset/cursor 分页。
- 关联写入尽量批量化，减少每个 Chunk 多次数据库 round-trip。

### 11.3 最小容量压测矩阵

| 维度 | 基准层级 | 核心观测值 |
|---|---|---|
| 文档量 | 1千 / 1万 / 10万 | 解析成功率、吞吐、堆内存、Outbox 延迟 |
| Chunk 量 | 10万 / 100万 / 1000万 | 召回 P50/P95/P99、索引体积、召回质量 |
| 并发查询 | 1 / 10 / 50 / 100 | P95/P99、错误率、CPU、连接池使用率 |
| 图谱规模 | 10万 / 100万 / 1000万节点 | 邻域检索延迟、内存、查询计划 |
| 权限变更 | 单对象 / 批量 / 全 KB | ACL 传播时延、越权检索次数 |

*这些数量是建议测试规模，不是目前代码已达到的性能指标，也不是给定硬件下的性能承诺。*

## 12. AKB-12：文档、测试与发布门禁（P2）

### 12.1 文档修正清单

1. 更新 README 的实际仓库 URL 与分支/版本指引。
2. 对齐 `RETRIEVAL_STATUS_POLICY` 默认状态及不同环境示例。
3. 说明 MCP 实际支持的 Transport、协议版本、通知行为。
4. 同步数据库最新 Flyway 迁移（当前目录 V1–V67）。
5. 明确编排服务的真实职责，避免文档中的理论链路与当前代码不一致。
6. 标明哪些功能是生产模式、预览模式或实验模式。

### 12.2 建议 CI/CD Gate

```bash
# Java 单元与集成测试
cd backend
mvn test
mvn -Pintegration-test verify

# Python 单元测试
cd ../ai-service
pytest -q

# 前端构建
cd ../frontend
npm ci
npm run build

# 仓库已有工程检查入口（根目录）
cd ..
./scripts/check-v11.sh
```

以上为建议执行命令，**本次报告未执行**。实际运行之前，应核对当前仓库对应的脚本和依赖环境是否准备充分。

重点加入：MCP 协议契约、权限矩阵、确定性知识校验、抽取 PARTIAL 恢复、Outbox 故障注入、索引状态回归和性能基线。

## 13. 推荐实施计划与依赖

| Sprint | 重点 | 前置依赖 | 交付物 |
|---|---|---|---|
| S1 | AKB-01、AKB-07、AKB-04 | 核实抽取标记来源与现网配置 | 校验器、状态策略、审计修复及回归测试 |
| S2 | AKB-02、AKB-03 | S1 核心回归通过 | 事务边界调整、MCP 兼容合同测试 |
| S3 | AKB-05、AKB-06 | Task/Outbox 当前表结构盘点 | 阶段补偿、死信测试、索引重建测试 |
| S4 | AKB-08、AKB-09、AKB-10 | 稳定契约测试 | 服务拆分、DTO、Schema 统一 |
| S5 | AKB-11、AKB-12 | 可重复环境与数据集 | 压测报告、性能门禁、交付文档 |

**实施原则**：先修知识可信和权限安全，再修事务与协议，再补可靠恢复，最后结构优化。每次重构应保持可回滚、可对比的独立提交；切勿在没有基线数据和测试时同时重构 Java、Python 与 MyBatis 全链路。

## 14. 最终验收总表

- [ ] 所有自动批准的 Card/Claim 可由服务端证据验证解释。
- [ ] 正式环境知识召回状态白名单和权限链路无歧义。
- [ ] AI 网络调用不处于数据库长事务内。
- [ ] MCP 与实际 Octop 客户端协议联调成功。
- [ ] MCP JSON-RPC 失败能够在审计/指标中准确识别。
- [ ] 抽取 PARTIAL 阶段可自动或手动幂等恢复。
- [ ] Outbox 重复、乱序、宕机、死信与蓝绿索引情况通过测试。
- [ ] 接口请求/响应及统计计数口径有明确契约。
- [ ] 多通道检索 ACL/状态过滤行为一致。
- [ ] 负载与容量测试有实测环境、指标和报告。
- [ ] 关键设计文档与源码一致。
- [ ] `mvn test`、`pytest`、前端构建及工程检查通过。

---

## 15. 主要源码导航（固定基线）

- [KnowledgeExtractionService.java](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/backend/src/main/java/com/jh/knowledgeos/service/KnowledgeExtractionService.java)
- [DocumentIngestionService.java](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/backend/src/main/java/com/jh/knowledgeos/service/DocumentIngestionService.java)
- [KnowledgeQueryService.java](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/backend/src/main/java/com/jh/knowledgeos/service/KnowledgeQueryService.java)
- [McpController.java](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/backend/src/main/java/com/jh/knowledgeos/controller/McpController.java)
- [McpServerService.java](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/backend/src/main/java/com/jh/knowledgeos/service/McpServerService.java)
- [ExternalAccessService.java](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/backend/src/main/java/com/jh/knowledgeos/service/ExternalAccessService.java)
- [IndexOutboxWorker.java](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/backend/src/main/java/com/jh/knowledgeos/service/IndexOutboxWorker.java)
- [QueryEngine（Python）](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/ai-service/app/services/query_engine.py)
- [Octop 集成说明](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/docs/api/OCTOP-INTEGRATION.md)
- [架构代码导读](https://github.com/jhzhfree/akb/blob/3328153d2294a85beb2d27ff3ec34c6be84853ff/docs/development/ARCHITECTURE-CODE-WALKTHROUGH.md)

> **声明**：本文件是基于指定提交的改进实施建议，不是缺陷复现报告，也不表示以上修改已经完成或已提交 Git。建议在开始修复时先运行基线测试，记录实际失败项，再逐条关闭本清单。
