# 场景数据集 3 · 工程协同知识库

> 场景定位：研发团队日常生产的数据沉淀，覆盖 **Jira 问题清单 + Confluence 设计文档 + OA/ITSM 工单 + 日报周报 + 岗位职责 + 迭代与季度计划 + 绩效评估 + 项目复盘**，模拟一个 8 人研发小组两个月的真实协同数据量。
> 虚构主体：蓝湖科技研发中心 · 支付研发二组，与另两个场景共用工号与系统（OA、ITSM、Confluence、Jira）。

## 一、数据集构成

| 编号 | 文件 | 知识类型（Schema） | 主要内容 | 典型问题 |
|---|---|---|---|---|
| 01 | 研发团队协作流程与角色职责 | CONFLUENCE_DOC | 双周节奏、分支规范、缺陷管理、跨团队协作 | 提测流程是什么？ |
| 02 | 岗位职责 · 后端工程师 | JOB_DESCRIPTION | 职责、任职要求（P5–P7）、KPI、权限 | 后端工程师考核指标有哪些？ |
| 03 | 岗位职责 · 前端工程师 | JOB_DESCRIPTION | 前端规范要点（主题 token、按钮三档、状态胶囊） | 前端样式有哪些硬约定？ |
| 04 | 岗位职责 · 测试工程师 | JOB_DESCRIPTION | 质量门禁（一票否决）、KPI | 什么情况下测试可以拒绝发布？ |
| 05 | 2026 Q3 研发计划与 OKR | PROJECT_PLAN | 4 个 O、16 条 KR、Epic 排期、风险 | Q3 支付重构的目标是什么？ |
| 06 | Sprint 24 迭代计划 | PROJECT_PLAN | Story 清单、容量、站会阻塞、门禁结果、回顾 | Sprint 24 完成了多少点？ |
| 07 | Jira 问题清单 · 支付重构史诗 | JIRA_ISSUE_LIST | Epic + 24 个 issue（Story/Bug/Task）+ 详情 | PAY-2499 是什么问题？ |
| 08 | Jira 问题清单 · 生产缺陷与线上问题 | JIRA_ISSUE_LIST | 18 个缺陷（含 KOS-3301 等），统计与共性问题 | 8 月有几个 P1 缺陷？ |
| 09 | Jira 问题清单 · 技术债与优化 Backlog | JIRA_ISSUE_LIST | 10 条技术债 + 6 条性能优化 + 偿还计划 | t_order 分库分表计划是什么时候？ |
| 10 | 支付系统架构设计（V3.2） | CONFLUENCE_DOC | 模块、状态机、幂等、渠道路由、清结算、风险 | 幂等键怎么设计？ |
| 11 | 订单服务接口规范（V2.6） | CONFLUENCE_DOC | 通用约定、下单/查询/退款/回调、限流 | 下单接口的错误码有哪些？ |
| 12 | 数据库设计规范与变更流程 | CONFLUENCE_DOC | 命名、字段、索引、SQL 规范、变更流程、案例 | 金额字段用什么类型？ |
| 13 | 发布与上线检查清单 | CONFLUENCE_DOC | 发布前/中/后 23 项检查、回滚标准、发布记录 | 发布前要检查哪十项？ |
| 14 | OA 工单 · 权限与环境申请 | OA_TICKET | 20 张工单（含 1 张驳回）、统计与改进 | 外包人员能申请 root 提权吗？ |
| 15 | OA 工单 · 线上故障与变更 | OA_TICKET | 12 张故障工单 + 12 张变更工单（含回滚） | CHG-2026-08-0087 为什么回滚？ |
| 16 | 工作日报 · 8 月第 3 周 | WORK_REPORT | 5 天 × 6 人进展、阻塞、周小结 | 8 月 19 日团队在做什么？ |
| 17 | 工作周报 · 8 月第 4 周 | WORK_REPORT | 进展、迭代数据、风险、下周计划 | 本周有哪些风险？ |
| 18 | 月度绩效评估 · 8 月 | PERFORMANCE_REVIEW | 团队指标 + 7 人评估 + 改进计划 | 郑凯 8 月绩效如何？ |
| 19 | 项目复盘 · 支付重构一期 | RETRO | 目标达成、根因、6 项改进、里程碑 | 重构一期的经验教训是什么？ |

**体量**：19 篇 / 约 3.8 万字，含 40+ 张表格、130+ 个 issue/工单编号、60+ 个可抽取实体。

## 二、刻意设计的「真实场景特征」

| 特征 | 设计 | 用途 |
|---|---|---|
| 编号密集型 | PAY-2481、KOS-3301、ITSM-2026-08-0801、CHG-2026-08-0087、OA-2026-08-0031 | 验证 BM25 精确召回与编号路由 |
| 同一事实多源 | 8·12 故障同时出现在 07/08/13/15/17/19 六处 | 验证证据聚合与去重 |
| 时间敏感 | 日报/周报/月报按周期更新，16 与 17 内容递进 | 验证时效权重（0.25）与「最近周期优先」 |
| 责任关系网 | 人员—模块—issue—工单—迭代形成多跳关系 | 验证图谱通道与关系类问题 |
| 状态不一致 | 07 中 PAY-2499 为 In Progress，17 中仍是风险，19 中列为改进项 | 验证「以最新文档为准」的冲突处理 |

## 三、Schema（已随导入应用）

- **知识类型**：JIRA_ISSUE_LIST、CONFLUENCE_DOC、OA_TICKET、WORK_REPORT、PROJECT_PLAN、JOB_DESCRIPTION、PERFORMANCE_REVIEW、RETRO
- **实体类型**：PROJECT、MODULE、EMPLOYEE、SPRINT、ISSUE、ENVIRONMENT
- **关系类型**：EMPLOYEE_ASSIGNED_ISSUE、ISSUE_BELONGS_MODULE、MODULE_DEPENDS_ON、SPRINT_CONTAINS_ISSUE、MODULE_DEPLOYED_IN、PROJECT_OWNS_MODULE
- 定义文件：`../_scenarios/engineering-workspace.schema.json`

## 四、检索策略（已创建）

「工程协同」策略要点：BM25 1.25（编号精确）、图谱 1.15（责任与依赖）、卡片 1.20；时效权重 0.25（日报周报优先）；`minRecallCandidates=32`；根因类走 graph + claim 多跳。定义文件：`../_scenarios/engineering-workspace.profile.json`。

## 五、导入与验证

```bash
python3 scripts/build-scenario-kbs.py --scenario eng --token <token>
```

验证建议：

1. `PAY-2499 是什么问题，解决了吗` → 应命中 07/08，说明结算并发死锁与处置方案；
2. `8 月 12 日支付故障的原因和改进项` → 应聚合 08/15/19，给出慢 SQL 与 5 项改进；
3. `发布前需要检查哪些项` → 应命中 13，给出十项检查与回滚标准；
4. `Sprint 24 完成了多少点，为什么没完成` → 应命中 06/17，给出 42/47 与顺延原因。

## 六、评测用例

`retrieval-eval/检索质量评测用例集.md`（L1 精确编号 / L2 语义改写 / L3 多跳与关系 / L4 拒答四类）。
