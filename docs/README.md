# 文档索引

本目录按用途分 7 个子目录。找文档先看这张表；不确定属于哪类时，按"我要解决什么问题"来找。

| 目录 | 回答什么问题 | 读者 |
|---|---|---|
| [`api/`](#api--对外接口与集成) | 外部系统 / 智能体怎么接进来 | 集成方、前端、Agent 平台 |
| [`development/`](#development--开发指南与规范) | 怎么读代码、怎么改、按什么规范 | 研发 |
| [`design/`](#design--架构与设计专题) | 这条链路为什么这样设计 | 研发、架构 |
| [`operations/`](#operations--部署运维) | 怎么部署、怎么排查、常用命令 | 运维、交付 |
| [`release/`](#release--版本发布) | 这个版本做了什么、交付了什么 | 项目管理、客户 |
| [`product/`](#product--产品资料) | 产品形态、PRD、竞品、原型 | 产品、售前 |
| [`archive/`](#archive--历史归档) | 这个功能当初为什么这么做 | 需要追溯决策的人 |

> 仓库**根目录**另保留 6 个 md（项目门面与发布门禁，见文末说明），不在此目录内。

---

## api/ — 对外接口与集成

| 文档 | 内容 |
|---|---|
| [`EXTERNAL-API.md`](api/EXTERNAL-API.md) | **对外接口主文档**：RESTful（`/api/v1/external/*`，含 `/query` / `/search`）、MCP Server（`/api/mcp`）、SSE 推送；鉴权、Scope、错误码、调用 Demo |
| [`EMBED-CHAT-WIDGET.md`](api/EMBED-CHAT-WIDGET.md) | 第三方网站嵌入问答组件的设计与集成方式 |
| [`AGENT-MEMORY.md`](api/AGENT-MEMORY.md) | Agent 记忆服务（独立 Memory Engine）的 REST / MCP 集成规范 + 回答反馈闭环与记忆能力扩展路线 |
| [`OCTOP-INTEGRATION.md`](api/OCTOP-INTEGRATION.md) | Octop 平台知识检索集成：MCP 地址、API Key 权限、Agent 绑定与联调检查 |

## development/ — 开发指南与规范

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE-CODE-WALKTHROUGH.md`](development/ARCHITECTURE-CODE-WALKTHROUGH.md) | **先读这篇**：按代码走一遍的架构导读（入门） |
| [`KNOWLEDGE-BASE-MAP.md`](development/KNOWLEDGE-BASE-MAP.md) | **全局知识地图**：知识对象、生命周期、检索、治理、权限、索引和代码入口 |
| [`DEVELOPER-DEEP-DIVE.md`](development/DEVELOPER-DEEP-DIVE.md) | **主培训件**：按场景递进的核心开发文档（47 KB，覆盖最全） |
| [`DEVELOPER-GUIDE.md`](development/DEVELOPER-GUIDE.md) | 环境搭建、日常操作与运维式问答 |
| [`CODE-STANDARDS.md`](development/CODE-STANDARDS.md) | 强制研发规范（行宽、事务边界、ACL、Outbox 等强约束） |
| [`CODE-STYLE.md`](development/CODE-STYLE.md) | 多语言工具化风格配置（Checkstyle / ruff / ESLint） |
| [`CHANGE-PLAYBOOK.md`](development/CHANGE-PLAYBOOK.md) | 常见改动（新增 API / 知识对象）的固定步骤清单 |
| [`DEBUGGING-TESTING-GUIDE.md`](development/DEBUGGING-TESTING-GUIDE.md) | 排障优先级与定位手册 |
| [`MAINTAINABILITY-REFACTOR-GUIDE.md`](development/MAINTAINABILITY-REFACTOR-GUIDE.md) | 可维护性重构指引（前端职责拆分等） |
| [`V1.1_CODE_STANDARDS_DEVELOPER_DOCS_REPORT.md`](development/V1.1_CODE_STANDARDS_DEVELOPER_DOCS_REPORT.md) | 代码规范 / Javadoc / 研发文档体系建设记录（含 24 项静态 Gate 清单） |
| [`BACKLOG.md`](development/BACKLOG.md) | 当前迭代待办总账（抽取稳定性 P1、答案门控拦截率 P2），含现象、建议动作、验收标准与进度 |

## design/ — 架构与设计专题

当前仍生效的架构决策与设计说明。

| 文档 | 内容 |
|---|---|
| [`ARCHITECTURE.md`](design/ARCHITECTURE.md) | **架构总览**：分层原则、Backend 服务、AI 检索层、知识治理边界、性能原则、Outbox/Index Worker |
| [`DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md`](design/DUAL-RETRIEVAL-ELASTICSEARCH-MILVUS.md) | 双检索**现行生产选型**（ES 9.5.3 + Milvus 3.0.1），含 ACL/状态过滤与蓝绿别名 |
| [`TRANSACTIONAL-OUTBOX-INDEX-WORKER.md`](design/TRANSACTIONAL-OUTBOX-INDEX-WORKER.md) | Transactional Outbox + Index Worker 设计（表结构、状态机、幂等、RBAC）+ 改造报告附录 |
| [`CODE-ARCH-PERFORMANCE-OPTIMIZATION.md`](design/CODE-ARCH-PERFORMANCE-OPTIMIZATION.md) | 可读性 / 架构 / 性能三路改造说明与后续路线 |
| [`STABILITY-OPTIMIZATION-PLAN.md`](design/STABILITY-OPTIMIZATION-PLAN.md) | 生成与检索稳定性优化方案（抽取归一/类型约束、段落块提权、忠实度判据）+ kb=9 实测数据；与 [`BACKLOG.md`](development/BACKLOG.md) 互引 |

## operations/ — 部署运维

| 文档 | 内容 |
|---|---|
| [`DEPLOYMENT-DOCKER.md`](operations/DEPLOYMENT-DOCKER.md) | Docker Compose 全栈部署（首选交付方式） |
| [`DEPLOYMENT-BAREMETAL.md`](operations/DEPLOYMENT-BAREMETAL.md) | Ubuntu 24.04 裸机 + systemd 部署（与 Docker 二选一） |
| [`ENVIRONMENT-V1.1.md`](operations/ENVIRONMENT-V1.1.md) | v1.1 容器环境、profile、端口与配置项说明 |
| [`COMMANDS.md`](operations/COMMANDS.md) | dev / release / 交付 命令速查 |
| [`PERFORMANCE-CAPACITY-PLAN-V1.1.md`](operations/PERFORMANCE-CAPACITY-PLAN-V1.1.md) | 10k / 50k / 100k 真实压测数据与容量分级规划 |

## release/ — 版本发布

| 文档 | 内容 |
|---|---|
| [`V1.1-RELEASE-NOTES.md`](release/V1.1-RELEASE-NOTES.md) | 功能发布说明：做什么、验收标准、约束、下一版方向（**客户 / 管理层口径**） |
| [`V1.1-RELEASE-MANIFEST.md`](release/V1.1-RELEASE-MANIFEST.md) | 发版清单与交付说明：实现程度、限制、部署方式、升级包类型（**交付口径**） |
| [`V1.1-FEATURE-SCOPE.md`](release/V1.1-FEATURE-SCOPE.md) | 首发版本功能规划、缺口（G1—G9）与排期（**工程内部口径**） |
| [`RC-CHECKLIST.md`](release/RC-CHECKLIST.md) | RC 阶段命令级验证清单（含原 `RC-FINAL.md` 的收口项与必须执行步骤） |
| [`RELEASE-QUALIFICATION-P2-P3.md`](release/RELEASE-QUALIFICATION-P2-P3.md) | P2 性能 / P3 一致性发布资格脚本与 Blocker 判定 |
| [`RELEASE-QUALIFICATION-ELASTICSEARCH-MILVUS.md`](release/RELEASE-QUALIFICATION-ELASTICSEARCH-MILVUS.md) | ES + Milvus 迁移后的 P0–P3 gate 与缺陷修复记录 |

## product/ — 产品资料

原始产品文档（HTML，保留原件不改写）：解决方案 V1.2、竞品拆解 V3、产品规格说明书 PRD V2.2、详细设计说明书 V2.1、原型 V2.2。

## archive/ — 历史归档

已完成迭代的交付记录，**只用于追溯决策，不作为当前实现依据**（当前实现以代码与 `api/` / `development/` 文档为准）。

| 文档 | 内容 | 合并自 |
|---|---|---|
| [`ACL-EVOLUTION.md`](archive/ACL-EVOLUTION.md) | ACL 权威化与传播治理 Phase 1–13 全过程 | 13 篇 `ACL-PHASE*` / `AUTHORITATIVE-ACL-PHASE*` |
| [`QUERY-TRACE.md`](archive/QUERY-TRACE.md) | Query Trace 检索可观测三轮演进 | 3 篇 `QUERY-TRACE-*` / `QUERY-HISTORY-*` |
| [`REFACTOR-REPORTS.md`](archive/REFACTOR-REPORTS.md) | P2.6–P2.15 架构收敛（Mapper/Controller/Service 拆分） | 2 篇 `P2.*-REPORT/CLOUSRE` |
| [`EVIDENCE-TRACEABILITY.md`](archive/EVIDENCE-TRACEABILITY.md) | 知识版本快照与证据查看器跨页导航 | 2 篇 |
| [`PROTOTYPE-INTEGRATION.md`](archive/PROTOTYPE-INTEGRATION.md) | 上传原型 `llmwiki` 融合两轮决策（含前端 TS 化） | 2 篇 |
| [`PHASE19-OKF-V02-INTEROPERABILITY.md`](archive/PHASE19-OKF-V02-INTEROPERABILITY.md) | OKF v0.2 互操作与安全接入（报告 + API/语义边界摘要合订） | 2 篇（根报告并入本文档） |
| [`V1.1-ANSWER-QUALITY-PHASES.md`](archive/V1.1-ANSWER-QUALITY-PHASES.md) | 答案资格与质量运营 Phase 17–18（写入账本 → 读取闭环 + 汇总 API） | 2 篇 `V1.1_AUTHORITATIVE_ACL_PHASE17/18_*` |
| [`V1.1-INDEX-OPERATIONS.md`](archive/V1.1-INDEX-OPERATIONS.md) | 索引运营线：蓝绿发布/故障演练 → MySQL 权威重建 + checksum + SLO + 运维 RBAC | 2 篇 `V1.1_INDEX_OPERATIONS_*` + `V1.1_AUTHORITATIVE_REBUILD_SLO_RBAC_*` |
| [`V1.1-RETRIEVAL-MIGRATION.md`](archive/V1.1-RETRIEVAL-MIGRATION.md) | 双检索引擎迁移两版（ES 3.7/Milvus 1.19 → ES 9.5.3/Milvus 3.0.1，以第 2 版为准） | 2 篇 `V1.1_*_MIGRATION_REPORT` |
| [`V1.1-DEVELOPMENT.md`](archive/V1.1-DEVELOPMENT.md) | V1.1 三轮功能开发基线计划（已被 `release/V1.1-RELEASE-NOTES.md` 覆盖） | — |
| [`ENGINEERING-CLOSURE.md`](archive/ENGINEERING-CLOSURE.md) | Flyway / Compose / Makefile 工程化收口（内容已被 `development/` 覆盖） | — |
| [`INTEGRATION-CONTRACT-CLOSURE.md`](archive/INTEGRATION-CONTRACT-CLOSURE.md) | API 契约 / DB 一致性 / Trace V2 收口（Gate 清单已被 24 项规范覆盖） | — |

> 合并前的文件均已在 git 历史中，需要看原始形态：`git show HEAD:<原路径>`。

---

## 仓库根目录保留的 6 个 md

这些**不放在 `docs/` 下**：

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

这 4 份内容存在部分重叠，属**刻意保留**：`PRODUCTION-READINESS-P3.md` 是发布原则与证据包口径（§6 容量、§8 灾备），另三份是执行层清单/步骤（`GO-NO-GO-CHECKLIST.md` 18 项勾选、`BACKUP-RECOVERY-DRILL.md` 7 步演练与脚本名、`PRODUCTION-CAPACITY-BASELINE.md` 资格阶梯与 30% headroom）。各自可独立随包交付，因此未做去重合并。
