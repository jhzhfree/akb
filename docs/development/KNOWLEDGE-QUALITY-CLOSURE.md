# 知识生产、检索与衍生闭环修复说明

基线：`76af23c`；目标分支：`feature/octop-mcp-integration`。本次在独立工作树实施，不包含原工作目录中的历史草稿及演示种子文件。

## 1. 修复范围与方法

| 问题 | 实现方法 | 主要位置 |
|---|---|---|
| 模型返回量被当成入库量、死锁重试累加 | 每次事务重试独立创建统计对象；响应只返回新建 cards/claims，另记录候选、复用、拒绝原因和证据绑定数量；失败保留已提交检查点 | KnowledgeExtractionService、IngestionObservabilityService |
| 重抽取绕过资源权限 | 以数据库中的文档归属、知识库写权限及来源标签为准，调用模型前拒绝越权；直接抽取和文档重试共用检查 | DocumentExtractionAccess、KnowledgeGovernanceController |
| 标题去重、规范化丢失语义 | E3 指纹保留比较符、标点及词边界；JSON 结构规范化，标签按独立值排序；幂等键包含来源、证据和权限边界，同名不同正文保留 | ExtractionCanonical、KnowledgeExtractionService |
| 无效证据被替换为整篇文档 | 只接受 ACTIVE 内容单元与证据；显式 evidence_ids 必须全部有效；没有 ID 时仅允许在当前单元中找到正文或主宾语锚点 | DocumentMapper.xml、KnowledgeExtractionService |
| 自动批准只依赖抽取器标记 | Java 入库前独立核对候选与当前文档证据：卡片正文/摘要及标题必须能在同一证据单元定位；断言主体及“谓语:宾语”必须能在同一证据单元匹配；带条件、限定或有效期的断言继续待审 | DeterministicExtractionVerifier、KnowledgeExtractionService |
| 并发重复插入、统计不准确 | 模型调用在事务外；入库事务按文档加锁，手动无文档抽取按知识库加锁；锁内查重并持久化 | DocumentMapper、KnowledgeExtractionService |
| fallback 生成恒真句、否定误读 | 删除标题加首句造断言路径；仅识别明确谓语，优先处理否定；条件、疑问和双重否定保守跳过；规则回退不自动 APPROVED | extractor.py |
| 冲突判断忽略适用范围 | 输入携带极性、条件、限定信息及有效期；不同范围或不重叠时间不直接冲突；多值谓语不套单值规则；候选冲突写 OPEN，交由裁决 | conflict_detector.py、KnowledgeExtractionService |
| 入库后外部服务失败导致台账归零 | 图索引、冲突检查等后处理分别捕获错误；保留入库数，结果标 PARTIAL 并返回可重试阶段提示 | KnowledgeExtractionService |
| 索引状态长期 PENDING | Outbox 成功、重试、死亡后重新汇总相关文档状态；存在 DEAD 为 FAILED，待处理为 PENDING，否则 INDEXED | IndexOutboxWorker、DocumentMapper.xml |
| 扫描数与产出数混淆 | 按规则分别记录实际合格输入、产出、创建及复用数量；保存输入单位、截断提示和跳过诊断 | KnowledgeDerivationService、DerivationMapper |
| 策略快照丢失、重排与引用问题 | 同一查询策略传递至路由和上下文；topN=0 使用有上限的自适应值；引用按来源版本、证据和完整内容合并，保留对象引用与通道 | query_policy.py、query_engine.py |

跨文档相同正文不直接合并资产，避免来源版本或权限不同导致泄漏；本次优先保证来源和证据边界。索引状态汇总包含相关未解决的历史 DEAD 事件，需运维处理后才能恢复。

## 2. 状态和计数契约

文档 READY 表示解析就绪，与知识抽取及索引状态分开。抽取结果为 EXTRACTED、EMPTY、QUALITY_REJECTED、PARTIAL 或 FAILED；零产出不假装已经形成知识。重抽取入口可用于解析已完成但无知识的文档。

`derivation=DETERMINISTIC` 只是候选来源提示，不能单独触发自动批准。服务端必须确认所有证据 ID 属于当前文档，再检查卡片正文、摘要和标题，或断言的主体、谓语和值与同一证据单元吻合。无法通过窄范围校验、包含额外条件/限定或有效期的候选进入待审状态。正式检索默认使用 `standard`（仅 `APPROVED`、`EFFECTIVE`）；只有受控预览才显式配置 `permissive`。

`cards/claims` 代表本次新建对象，不代表模型候选数；复用不重复计入新增。`extractionStats` 保存候选、复用和拒绝等诊断，`extraction_summary_json` 保存摘要。衍生扫描数只代表实际取到的合格输入，不代表全库总行数；必须结合 inputUnit 和扫描上限理解。图诊断为图上下文诊断，不能当作某条规则精确匹配失败数量。

模型槽只包住抽取请求，不覆盖解析、数据库写入和索引。配置 `knowledge.extraction.concurrency` 默认 1、`knowledge.extraction.queue-timeout-seconds` 默认 300。信号量作用于单个后端实例，多副本部署仍需共享队列或模型网关统一限流。`RERANKER_AUTO_TOP_N` 默认 50，配置范围限制为 1–200，显式正数 topN 优先。

## 3. 升级与历史数据

部署前备份 MySQL，正常启动时由 Flyway 执行新增的 `V67__knowledge_quality_outcomes.sql`，增加文档抽取摘要及衍生执行摘要 JSON 字段。未修改已经发布的 V65、V66，不能手动替换历史迁移。回退应用时可保留这两个可空字段，避免破坏 Flyway 历史。

E3 不自动删除旧卡片、重写旧证据或恢复历史 CONFLICTED 状态。上线后先抽样审计旧资产，再按文档重抽取、比对和人工裁决；不要批量清空知识或直接把所有冲突改成 APPROVED。E2 到 E3 的首次抽取可能产生新对象，应核对历史资产后再归档。幂等键或规范化规则变更必须说明版本策略并增加回归用例。

确定性校验只约束新建对象，不会自动降级历史上由 `DETERMINISTIC_PARSE` 创建的 APPROVED 对象。切换正式环境到 `standard` 前，应先盘点这些历史对象并抽样核验；不要自动降级已有人工作出裁决的知识。可先用以下只读查询定位抽取器自动批准的历史快照：

```sql
SELECT knowledge_base_id, object_type, object_code, version_no, title, content, created_time
FROM t_knowledge_version
WHERE change_type = 'CREATED'
  AND changed_by = 'DETERMINISTIC_PARSE'
  AND status = 'APPROVED'
ORDER BY created_time DESC;
```

## 4. 验证步骤

1. 导入两篇章节标题相同但正文不同的文档，确认都保留；比较 `>=` 与 `<=` 的正文，不应判重；相同来源重复抽取不应重复新增。
2. 对同一文档并发重抽取，确认新增数不重复，检查入库数量和 run 台账一致。标签 `["hr","pay"]` 与 `["hrpay"]` 不可等价。
3. 使用没有知识库写权限或缺少来源标签的账号重抽取，确认调用模型前拒绝；传入外库文档 ID 同样拒绝。
4. 模型返回失效 evidence_id，确认候选被拒绝，不能绑定整篇文档。仅 ACTIVE 单元参与重抽取。
5. 测试“员工不应共享账号”、普通说明文字、条件句和双重否定，确认不再生成错误正向、恒真或自动通过的断言。
6. 模拟入库后冲突服务失败，确认返回 PARTIAL，已入库卡片和断言计数保留；重抽取可再次尝试处理复用断言。
7. Outbox 从 PENDING 到成功、重试及 DEAD，核对文档索引状态；存在相关 DEAD 时先修复/重试事件。
8. TEMPLATE 规则无合格输入时不得调用模型；核对扫描单位及摘要。问答检查重排实际 topN、引用编号连续及不同来源版本未被合并。

可从数据库核对（按现场文档 ID 增加 WHERE 条件）：

```sql
SELECT id, extraction_status, index_status, extraction_summary_json FROM t_document ORDER BY id DESC LIMIT 20;
SELECT * FROM t_ingestion_run ORDER BY id DESC LIMIT 20;
SELECT id, execution_summary_json FROM t_derivation_run ORDER BY id DESC LIMIT 20;
SELECT aggregate_key, status, COUNT(*) FROM t_index_outbox GROUP BY aggregate_key, status;
```

## 5. 本地验证记录与边界

- AI 服务 pytest：97 项通过（GRAPH_PROVIDER=local，清除环境代理后运行）。
- Java 主代码及全部测试源码：使用 JDK 17 编译器 `--release 8` 编译通过；新增权限、规范化和抽取回归 8 项通过。
- 全量后端测试使用标准 Maven 执行（`cd backend && mvn -B verify -P integration-test`）：单元测试 144 项、集成测试 3 项**全部通过**。此前记录的 4 项失败已修，两者性质不同：`ExternalAccessServiceTest` 是源码缺陷——`grantedKnowledgeBases` 就地 `retainAll` 上游返回的集合，不可变列表会抛异常，且会污染可能被缓存或共享的返回值，已改为在副本上取交集；`ApiKeyServiceTest` 3 项是用例没跟上——授权前会校验主体存在且 ACTIVE，用例缺 `getPrincipal` mock，已补。
- 前端 vue-tsc 类型检查及 Vite 生产构建通过，构建仍有大包体积提示。
- `check-v11.sh` 在第 25 项被现有 App.vue 超过 250 行的结构门禁阻断；前 24 项通过。该组件基线已超过限制，本次仅格式调整，未放宽门禁。
- 集成测试已执行：`DatabaseMigrationIT` 3 项通过，Flyway 在 Testcontainers 起的独立 MySQL 容器上应用 67 个迁移至 v67，覆盖 V67 字段、ACTIVE 证据契约与索引状态转换。有两个前置条件容易踩：一是 failsafe 仅在 `integration-test` profile 下绑定目标，直接 `mvn verify` **不会跑任何 IT**；二是 **Testcontainers 需 ≥1.21**——1.20.x 的 docker-java 把 Docker API 版本固定为 1.32，而 Docker 29 要求 ≥1.44，握手即被拒且 `DOCKER_API_VERSION` 环境变量无效。已将 `testcontainers.version` 从 1.19.8 提到 1.21.4。
- 未连接真实 Ollama、Neo4j、Milvus 或 Elasticsearch，不能把单元测试结果当作生产环境端到端验收。
