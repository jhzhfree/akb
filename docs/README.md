# 文档索引

本目录按用途分 7 个子目录。**不确定属于哪类时，按"我要解决什么问题"来找。**

> **总原则：代码是唯一事实来源。**
> 文档与代码冲突时，以代码为准，并请修正文档。2026-10-28 做了一次全量核对，修掉了若干处"文档还在描述旧实现"的问题（清单见文末「已知文档偏差」）。
> 本索引后续会随代码变动维护；发现某篇描述与代码不符，请直接改动该文档并更新本页。

| 目录 | 回答什么问题 | 读者 |
|---|---|---|
| [`api/`](#api--对外接口与集成) | 外部系统 / 智能体怎么接进来 | 集成方、前端、Agent 平台 |
| [`development/`](#development--开发指南与规范) | 怎么读代码、怎么改、按什么规范 | 研发 |
| [`design/`](#design--架构与设计专题) | 这条链路为什么这样设计 | 研发、架构 |
| [`operations/`](#operations--部署运维) | 怎么部署、怎么排查、常用命令 | 运维、交付 |
| [`release/`](#release--版本发布) | 这个版本做了什么、交付了什么 | 项目管理、客户 |
| [`product/`](#product--产品资料) | 产品形态、PRD、竞品、原型 | 产品、售前 |
| [`archive/`](#archive--历史归档) | 这个功能当初为什么这么做 | 需要追溯决策的人 |

**状态标记含义**

| 标记 | 含义 |
|---|---|
| 现行 | 描述当前实现，已核对过；按它做不会错 |
| 设计稿 | 描述**目标方案**，部分尚未落地；动手前必须先看文档内的"代码进展 / 尚未实现"说明 |
| 归档 | 历史决策记录，**不作为当前实现依据** |
| 外部协议 | 对接方契约，实现细节见它引用的 api 文档 |

> 仓库**根目录**另保留 6 个 md（项目门面与发布门禁，见文末），不在此目录内。

---

## api/ — 对外接口与集成

| 文档 | 状态 | 内容 |
|---|---|---|
| [`EXTERNAL-API.md`](api/EXTERNAL-API.md) | 现行 | **对外接口主文档**：RESTful（`/api/v1/external/*`）、MCP Server（`/api/mcp`）、SSE；鉴权、Scope、错误码、调用 Demo |
| [`AGENT-MEMORY.md`](api/AGENT-MEMORY.md) | 现行 | Agent 记忆服务的 REST / MCP 集成规范 + 反馈闭环。**注意**：Memory 身份已在 V72 迁到 Principal，见下节[已落地的主体与凭证迁移](#已落地的主体与凭证迁移v61v73) |
| [`EMBED-CHAT-WIDGET.md`](api/EMBED-CHAT-WIDGET.md) | 现行 | 第三方网站嵌入问答组件的设计与集成方式 |
| [`OCTOP-INTEGRATION.md`](api/OCTOP-INTEGRATION.md) | 现行 | Octop 接入**速查**：MCP 地址、建议 Scope、Agent 绑定、联调 5 步。上手看这篇 |
| [`AKB-OCTOP-IDENTITY-AUTH.md`](api/AKB-OCTOP-IDENTITY-AUTH.md) | 现行 | AKB 侧身份 / PAT / Service Key 的**实施说明**（P0–P5）。要改代码看这篇 |
| [`external_kb_search_protocol.md`](api/external_kb_search_protocol.md) | 外部协议 | NovaWork 第三方检索协议；我方的满足方式见 `EXTERNAL-API.md` §11 |

### Octop 三篇怎么选

同一主题有三篇，职责不同，**不要混读**：

```text
想快速接一个 Agent        → api/OCTOP-INTEGRATION.md
要改 AKB 侧认证/凭据代码   → api/AKB-OCTOP-IDENTITY-AUTH.md
想了解目标模型与评审背景   → design/AKB_Octop_统一身份认证与权限模型详细设计_V1.1_决策确认版.md
                            （设计稿，动手前先读它的 §0「文档定稿后的代码进展」）
```

### 已落地的主体与凭证迁移（V61–V73）

以下能力已上线，`api/` 文档均已体现，核对实现时请对照：

| 迁移 | 内容 |
|---|---|
| `V61` | `t_principal` / `t_principal_kb_grant` / `t_principal_kb_grant_audit`；`t_api_key.principal_id` |
| `V62` | `t_api_key_principal_audit`（共享主体审计） |
| `V63` | grant 级 `permission_tags` |
| `V64` | `principal_type`、`t_principal_identity_link` / `_audit`（OIDC 委托身份） |
| `V72` | `credential_type` / `owner_user_id`、凭证轮换、主体级 Memory 授权、Octop 身份绑定、`t_octop_assertion_jti`、`t_personal_access_token_scope_audit` |
| `V73` | `t_api_key.deleted_at` 软删除 + `GET /keys` 服务端分页 |

---

## development/ — 开发指南与规范

| 文档 | 状态 | 内容 |
|---|---|---|
| [`ARCHITECTURE-CODE-WALKTHROUGH.md`](development/ARCHITECTURE-CODE-WALKTHROUGH.md) | 现行 | 按代码走一遍的架构导读（**入门先读这篇**） |
| [`KNOWLEDGE-BASE-MAP.md`](development/KNOWLEDGE-BASE-MAP.md) | 现行 | **全局知识地图**：知识对象、生命周期、检索、治理、权限、索引和代码入口 |
| [`DEVELOPER-DEEP-DIVE.md`](development/DEVELOPER-DEEP-DIVE.md) | 现行 | **主培训件**：按场景递进的核心开发文档（47 KB，覆盖最全） |
| [`DEVELOPER-GUIDE.md`](development/DEVELOPER-GUIDE.md) | 现行 | 环境搭建、日常操作与运维式问答 |
| [`CODE-STANDARDS.md`](development/CODE-STANDARDS.md) | 现行 | 强制研发规范（行宽、事务边界、ACL、Outbox 等强约束） |
| [`CODE-STYLE.md`](development/CODE-STYLE.md) | 现行 | 多语言工具化风格配置（Checkstyle / ruff / ESLint） |
| [`CHANGE-PLAYBOOK.md`](development/CHANGE-PLAYBOOK.md) | 现行 | 常见改动（新增 API / 知识对象）的固定步骤清单 |
| [`DEBUGGING-TESTING-GUIDE.md`](development/DEBUGGING-TESTING-GUIDE.md) | 现行 | 排障优先级与定位手册 |
| [`MAINTAINABILITY-REFACTOR-GUIDE.md`](development/MAINTAINABILITY-REFACTOR-GUIDE.md) | 现行 | 可维护性重构指引（前端职责拆分等） |
| [`PROJECT-MEMORY.md`](development/PROJECT-MEMORY.md) | 现行 | **工程约定**：会持续影响开发/构建/验证的事项（DB 迁移必须 Docker、Java 8 兼容、委托身份约定等）。新增约定请同步这里 |
| [`KNOWLEDGE-QUALITY-CLOSURE.md`](development/KNOWLEDGE-QUALITY-CLOSURE.md) | 现行 | 基线 `76af23c` 的知识生产/检索/衍生闭环修复说明：E3 幂等键、重抽取授权、入库计数、阶段摘要 |
| [`V1.1_CODE_STANDARDS_DEVELOPER_DOCS_REPORT.md`](development/V1.1_CODE_STANDARDS_DEVELOPER_DOCS_REPORT.md) | 归档 | 代码规范 / Javadoc / 研发文档体系建设记录（含当时的 24 项静态 Gate 清单，项数已过期） |
| [`BACKLOG.md`](development/BACKLOG.md) | 现行 | 当前迭代待办总账（抽取稳定性 P1、答案门控拦截率 P2） |

> `DEVELOPER-GUIDE`、`ARCHITECTURE-CODE-WALKTHROUGH`、`CODE-STANDARDS`、`DEBUGGING-TESTING-GUIDE`、`CHANGE-PLAYBOOK` 这 5 篇被 `scripts/check-developer-docs.py` 断言，且根 `README.md` 必须链接前两者与 `CODE-STANDARDS`。**改名或移动会打破门禁。**

---

## design/ — 架构与设计专题

| 文档 | 状态 | 内容 |
|---|---|---|
| [`ARCHITECTURE.md`](design/ARCHITECTURE.md) | 现行 | **架构总览**：分层原则、Backend 服务、AI 检索层、知识治理边界、性能原则、Outbox |
| [`DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md`](design/DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md) | 现行 | 双检索**现行生产选型**（ES 9.5.3 + Milvus 3.0.1），含启用开关、ACL/状态过滤、标签哨兵与分库别名 |
| [`TRANSACTIONAL-OUTBOX-INDEX-WORKER.md`](design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md) | 现行 | Transactional Outbox + Index Worker：表结构、状态机、幂等、RBAC、运维 API |
| [`CODE-ARCH-PERFORMANCE-OPTIMIZATION.md`](design/CODE-ARCH-PERFORMANCE-OPTIMIZATION.md) | 现行 | 可读性 / 架构 / 性能三路改造说明与后续路线 |
| [`STABILITY-OPTIMIZATION-PLAN.md`](design/STABILITY-OPTIMIZATION-PLAN.md) | 现行 | 生成与检索稳定性优化方案 + kb=9 实测数据；与 `BACKLOG.md` 互引 |
| [`AKB-检索权限落地方案.md`](design/AKB-检索权限落地方案.md) | 现行 | 检索权限分阶段落地。**§4.1–4.3 是设计稿**且与实现有差异，**实际表结构一律看 §4.4** |
| [`AKB_Octop_统一身份认证与权限模型详细设计_V1.1_决策确认版.md`](design/AKB_Octop_统一身份认证与权限模型详细设计_V1.1_决策确认版.md) | 设计稿 | AKB 与 Octop 统一身份的目标模型。**动手前必须先读新增的 §0「文档定稿后的代码进展」** |

> `DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md` 被 `scripts/check-v11.sh:87` 按路径断言存在，不要改名或移动。

---

## operations/ — 部署运维

| 文档 | 状态 | 内容 |
|---|---|---|
| [`BUILD-AND-DEPLOY.md`](operations/BUILD-AND-DEPLOY.md) | 现行 | **改代码到上线的主线**：改哪部分重建什么、DB 迁移、制镜像、推镜像、目标机拉取、部署、回滚 |
| [`DEPLOYMENT-DOCKER.md`](operations/DEPLOYMENT-DOCKER.md) | 现行 | Docker Compose 全栈部署（首选交付方式）：系统参数、`.env` 全量说明、验收清单、备份恢复 |
| [`DEPLOYMENT-BAREMETAL.md`](operations/DEPLOYMENT-BAREMETAL.md) | 现行 | Ubuntu 24.04 裸机 + systemd 部署（与 Docker 二选一） |
| [`ENVIRONMENT-V1.1.md`](operations/ENVIRONMENT-V1.1.md) | 现行 | v1.1 容器环境、profile、端口与配置项说明 |
| [`COMMANDS.md`](operations/COMMANDS.md) | 现行 | dev / release / 交付 命令速查 |
| [`PERFORMANCE-CAPACITY-PLAN-V1.1.md`](operations/PERFORMANCE-CAPACITY-PLAN-V1.1.md) | 现行 | 10k / 50k / 100k 压测数据与容量分级规划 |

> **环境变量名必须以代码为准**：变量名取自 `ai-service/app/core/config.py` 与 `backend/src/main/resources/application*.yml`，不是取自文档。历史上出现过文档写 `OLLAMA_BASE_URL` 而代码实际读 `LLM_BASE_URL` 的错误，改 Variable 名之前先 grep 代码。

**两份部署文档的分工**：日常操作与细项参数查 `DEPLOYMENT-*`；"我这次改动怎么变成运行中的服务"查 `BUILD-AND-DEPLOY.md`。两者不重复。

---

## release/ — 版本发布

| 文档 | 状态 | 内容 |
|---|---|---|
| [`V1.1-RELEASE-NOTES.md`](release/V1.1-RELEASE-NOTES.md) | 现行 | 功能发布说明：做什么、验收标准、约束、下一版方向（**客户 / 管理层口径**） |
| [`V1.1-RELEASE-MANIFEST.md`](release/V1.1-RELEASE-MANIFEST.md) | 现行 | 发版清单与交付说明：实现程度、限制、部署方式、升级包类型（**交付口径**） |
| [`V1.1-FEATURE-SCOPE.md`](release/V1.1-FEATURE-SCOPE.md) | 现行 | 首发版本功能规划、缺口（G1—G9）与排期（**工程内部口径**） |
| [`RC-CHECKLIST.md`](release/RC-CHECKLIST.md) | 现行 | RC 阶段命令级验证清单（含原 `RC-FINAL.md` 的收口项） |
| [`RELEASE-QUALIFICATION-P2-P3.md`](release/RELEASE-QUALIFICATION-P2-P3.md) | 现行 | P2 性能 / P3 一致性发布资格脚本与 Blocker 判定 |
| [`RELEASE-QUALIFICATION-ELASTICSEARCH-MILVUS.md`](release/RELEASE-QUALIFICATION-ELASTICSEARCH-MILVUS.md) | 现行 | ES + Milvus 迁移后的 P0–P3 gate 与缺陷修复记录 |

> `RC-CHECKLIST.md` 被 `scripts/check-v11.sh:82` 按路径断言存在，不要改名或移动。

---

## product/ — 产品资料

产品文档（HTML，单文件自包含，可直接用浏览器打开）：解决方案 V1.2、竞品拆解 V3、产品规格说明书 PRD V2.2、详细设计说明书 V2.1、原型 V2.2。

| 文档 | 定位 |
|---|---|
| `企业级智能知识库_产品技术解决方案_V1.2.html` | 技术方案：架构分层、知识全生命周期、双检索选型、四层权限、一致性 |
| `企业级智能知识库平台_产品规格说明书_PRD_V2.2.html` | 产品口径：五大一级菜单、逐模块功能与验收 |
| `企业级智能知识库平台_产品详细设计说明书_V2.1.html` | 交互与布局口径：顶栏、导航、Tab、状态与文案 |
| `企业级智能知识库平台_原型_V2.2.html` | 可点击的交互原型（含登录页、五大菜单、抽屉/弹窗） |
| `企业级智能知识库平台__竞品拆解V3.html` | 竞品研究与我方 MVP 借鉴清单（竞品部分为外部资料，不随代码演进） |

> 这些是**产品意图**而非技术事实。实现细节请回到 `api/` / `design/` / `development/`。
>
> **本组文档已按代码校正**（2026-10-28）：原本围绕「用户视图 / 管理视图」构建的整套表述已全部改为「角色菜单驱动」，并补齐了缺失的「主体」与「系统初始化」入口。**原型是唯一可运行时验证的一组**——改完请用浏览器打开，或 `chrome --headless --screenshot` 截图确认，不要只做文本校验。

---

## archive/ — 历史归档

**只用于追溯决策，不作为当前实现依据**（当前实现以代码与 `api/` / `development/` / `design/` 文档为准）。

| 文档 | 内容 | 合并自 |
|---|---|---|
| [`ACL-EVOLUTION.md`](archive/ACL-EVOLUTION.md) | ACL 权威化与传播治理 Phase 1–13 全过程 | 13 篇 `ACL-PHASE*` / `AUTHORITATIVE-ACL-PHASE*` |
| [`QUERY-TRACE.md`](archive/QUERY-TRACE.md) | Query Trace 检索可观测三轮演进 | 3 篇 `QUERY-TRACE-*` / `QUERY-HISTORY-*` |
| [`REFACTOR-REPORTS.md`](archive/REFACTOR-REPORTS.md) | P2.6–P2.15 架构收敛（Mapper/Controller/Service 拆分） | 2 篇 `P2.*-REPORT/CLOUSRE` |
| [`EVIDENCE-TRACEABILITY.md`](archive/EVIDENCE-TRACEABILITY.md) | 知识版本快照与证据查看器跨页导航 | 2 篇 |
| [`PROTOTYPE-INTEGRATION.md`](archive/PROTOTYPE-INTEGRATION.md) | 上传原型 `llmwiki` 融合两轮决策（含前端 TS 化） | 2 篇 |
| [`PHASE19-OKF-V02-INTEROPERABILITY.md`](archive/PHASE19-OKF-V02-INTEROPERABILITY.md) | OKF v0.2 互操作与安全接入 | 2 篇（根报告并入本文档） |
| [`V1.1-ANSWER-QUALITY-PHASES.md`](archive/V1.1-ANSWER-QUALITY-PHASES.md) | 答案资格与质量运营 Phase 17–18 | 2 篇 |
| [`V1.1-INDEX-OPERATIONS.md`](archive/V1.1-INDEX-OPERATIONS.md) | 索引运营线：蓝绿发布/故障演练 → MySQL 权威重建 + checksum + SLO + 运维 RBAC | 2 组 `V1.1_INDEX_OPERATIONS_*` / `V1.1_AUTHORITATIVE_REBUILD_SLO_RBAC_*` |
| [`V1.1-RETRIEVAL-MIGRATION.md`](archive/V1.1-RETRIEVAL-MIGRATION.md) | 双检索引擎迁移两版（→ ES 9.5.3 / Milvus 3.0.1，以第 2 版为准） | 2 篇 `V1.1_*_MIGRATION_REPORT` |
| [`V1.1-DEVELOPMENT.md`](archive/V1.1-DEVELOPMENT.md) | V1.1 三轮功能开发基线计划（已被 `release/V1.1-RELEASE-NOTES.md` 覆盖） | — |
| [`ENGINEERING-CLOSURE.md`](archive/ENGINEERING-CLOSURE.md) | Flyway / Compose / Makefile 工程化收口（已被 `development/` 覆盖） | — |
| [`INTEGRATION-CONTRACT-CLOSURE.md`](archive/INTEGRATION-CONTRACT-CLOSURE.md) | API 契约 / DB 一致性 / Trace V2 收口 | — |
| [`AKB_feature_octop_mcp_integration_代码审查与改进实施方案.md`](archive/AKB_feature_octop_mcp_integration_代码审查与改进实施方案.md) | 针对基线 `3328153` 的一次性静态审查与整改建议（2026-10-24，未CI/构建验证） | — |

> 合并前的文件均已在 git 历史中，需要看原始形态：`git show HEAD:<原路径>`。

---

## 仓库根目录保留的 6 个 md

| 文档 | 为什么留在根 |
|---|---|
| `README.md` | 项目门面；`scripts/check-developer-docs.py` 断言其内容，`operations/DEPLOYMENT-BAREMETAL.md` 的 systemd `Documentation=` 直接引用 |
| `CONTRIBUTING.md` | 社区约定根目录位置 |
| `GO-NO-GO-CHECKLIST.md` | **门禁硬依赖**（下方说明） |
| `BACKUP-RECOVERY-DRILL.md` | 同上（灾备演练，随包交付清单引用） |
| `PRODUCTION-READINESS-P3.md` | 同上 |
| `PRODUCTION-CAPACITY-BASELINE.md` | 同上 |

> **为什么这 4 个不能进 `docs/`**：`scripts/check-production-readiness-p3.py:6-17` 按**仓库根相对路径**断言它们存在，该脚本由 `scripts/check-v11.sh:103` 调用。移动或改名会让 `make check` 直接判 NO-GO。
> 若要归目录，需先改脚本里的 `required` 列表（并同步 `check-v11.sh` 中的 `test -f` 断言）。

这 4 份内容存在部分重叠，属**刻意保留**：`PRODUCTION-READINESS-P3.md` 是发布原则与证据包口径（§6 容量、§8 灾备），另三份是执行层清单/步骤。各自可独立随包交付，因此未做去重合并。

---

## 已知文档偏差（2026-10-28 全量核对后已修正）

本次对照代码逐篇核对，修掉了下面这些"文档还在描述旧实现"的地方。**看到类似写法请照此处理**：

| 位置 | 原文说法 | 实际情况 | 处理 |
|---|---|---|---|
| `design/ARCHITECTURE.md` §2 | `KnowledgeController` 是入口 | P2.13 后已拆为 6 个领域 Controller，该文件只剩 `@Deprecated` 空壳 | 改为领域 Controller 清单 + 迁移提示 |
| `design/ARCHITECTURE.md` §3 | 检索 Provider "将来替换"，候选"Milvus / Milvus / pgvector / ES / ES" | ES + Milvus 已由 V7 落地；候选名重复是批量替换留下的残留 | 改为已落地 + 去重 |
| `design/CODE-ARCH-PERFORMANCE-OPTIMIZATION.md` §2.3 | `frontend/src/api/knowledge.js` | 前端已 TS 化，实际是 `knowledge.ts` + `knowledge-impl.ts` | 改为真实路径 |
| `design/CODE-ARCH-PERFORMANCE-OPTIMIZATION.md` §3.3 | `get_embedding_provider` 为 `maxsize=1` | 实际 `maxsize=4` | 改为 4 |
| `design/CODE-ARCH-PERFORMANCE-OPTIMIZATION.md` §6 | `check-v11.sh` 15 项 | 当前 52 项 | 保留快照 + 加"以脚本为准"警告 |
| `design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md` §12 | 归档 / 独立 Worker / 蓝绿列为"下一阶段" | 三者均已实现（V5、`deploy/*` 的 `index-worker`、`IndexSchemaService`） | 移入已完成并补证据 |
| `design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md` §9 | 只列 5 个运维 API | 另有 index schema 与 ACL rebuild 共 7 个端点 | 补齐端点 |
| `design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md` 附录 | Gate 18 项 | 当前 52 项 | 同上加警告 |
| `design/DUAL-RETRIEVAL-*.md` | 未提启用开关、`__public__` 哨兵、分库别名 | 三者都是真实实现，漏了会配错 target | 全部补齐 |
| `design/AKB-检索权限落地方案.md` §4.1/§4.2 | `tenant_id`、`actions`、`valid_from/valid_to`、`old_*/new_*` 快照 | 迁移里全部没有；操作权限在 `t_api_key.scopes` 不在 grant 表 | 标注为设计稿并新增 §4.4 真实结构 |
| `design/AKB-检索权限落地方案.md` §3 | 未提轮换 / Memory 主体化 / 软删除 / 分页 | V72 / V73 均已上线 | 新增 P1 第 8–12 条 |
| `design/AKB_Octop_*_决策确认版.md` | §2.2 称上述能力为"待补差距" | 已被 V72/V73 实现 | 新增 §0「文档定稿后的代码进展」 |
| `design/STABILITY-OPTIMIZATION-PLAN.md` B2 | 只讲出厂默认值 | 运行时会被 `query_policy.defaults.json` 的 4 个场景 profile 覆盖 | 加醒目提示 |
| `product/` 全部 4 篇 | 顶栏有「视图切换」，用户视图 / 管理视图双层模型 | 该模型已被 `96b80f0` 移除，改为角色 + `t_role_menu` 驱动 | 全部改写为角色菜单驱动 |
| `product/` PRD + 详细设计 + 原型 | 接口服务 7–9 个 Tab | 实际 **10** 个，缺「主体」 | 补齐并补面板内容 |
| `product/` PRD + 详细设计 + 原型 | 系统配置 10 个 Tab | 实际 **11** 个，缺「系统初始化」 | 补齐 |
| `product/` 原型 | 知识生成 Tab 序为 生成概览/文档导入/连接器/…/生成配置 | 实际 生成配置 排在第 2 | 按 `navigation.ts` 重排 |
| `product/` 原型 | 「生成配置」标了管理员专属 | 实际只要求 `generate:import` | 去掉管理员标记 |
| `product/` 详细设计 + 原型 | MCP 工具 `search_knowledge` / `retrieve_context` / `get_assertion` / `explore_entity` | **代码中不存在**，实际是 `knowledge_query` / `knowledge_search` / `list_*` / `get_*` / `graph_neighbors` | 按 `McpServerService` 重写 |
| `product/` 详细设计 + 原型 | SSE 事件 `start` / `retrieval` / `complete` | 实际 `open` / `stage`（9 个 stage 取值）/ `done`，另有 `knowledge` / `ingestion` | 重写并列出 stage 取值 |
| `product/` 解决方案 | §10.1、§12.2、§12.10、§12.12 用 OpenSearch / pgvector | 已落地 ES 9.5.3 + Milvus 3.0.1，与本文 §2.2、§12.6 自相矛盾 | 统一为 ES + Milvus |
| `product/` 解决方案 | 知识卡片字段含 `tenantId`、控制面含"租户" | 迁移中 `tenant_id` 零出现，仍为单租户 | 标注为预留 |
| `product/` 竞品拆解 | 知识检索树里「检索评测」重复两行 | 合并残留 | 去重 |
| `product/` 原型 | 登录页主标题被全局 `h1,h2,h3{color:var(--text)}` 盖成深色 | 真实 `LoginView.vue` 用 scoped 样式，标题为白色 | 显式声明 `color:#fff` |

**仍在核对、尚未确认的领域**（后续接力请优先处理）：`release/` 全部 6 篇的验收数据、`operations/PERFORMANCE-CAPACITY-PLAN-V1.1.md` 的压测数字、`api/EXTERNAL-API.md` 96 KB 全文逐字段核对。
