# Jira 问题清单 · 支付重构史诗（PAY-EPIC-2026Q3）

> 导出时间：2026-08-31 18:00　|　数据源：Jira Software（项目 KOS）　|　导出人：赵鹏
> 筛选条件：project = KOS AND "Epic Link" = PAY-EPIC-2026Q3
> 共 24 个 issue（Epic 1 / Story 14 / Bug 6 / Task 3）

## 一、Epic 概览

| 字段 | 值 |
|---|---|
| Key | PAY-EPIC-2026Q3 |
| 标题 | 支付重构一期：清结算 T+0 与链路稳定性 |
| 负责人 | 吴桐（P8） |
| 报告人 | 陈立（P9） |
| 优先级 | Highest |
| 状态 | In Progress |
| 周期 | 2026-07-01 – 2026-09-30 |
| 进度 | 已完成 14 / 24（58%） |
| 关联 OKR | O2（支付重构一期上线）、O1（稳定性） |

## 二、Story 清单

| Key | 摘要 | 类型 | 优先级 | 经办人 | 状态 | 点数 | Sprint |
|---|---|---|---|---|---|---|---|
| PAY-2481 | 结算任务改造：按渠道维度并行结算 | Story | High | 吴桐 | Done | 8 | Sprint 24 |
| PAY-2482 | 对账补录接口（支持人工补单） | Story | High | 郑凯 | Done | 5 | Sprint 24 |
| PAY-2483 | 灰度开关：按商户比例路由新旧链路 | Story | High | 郑凯 | Done | 5 | Sprint 24 |
| PAY-2484 | 结算结果对账校验（资金差错检测） | Story | Highest | 吴桐 | In Test | 8 | Sprint 24 |
| PAY-2485 | 清结算 T+0 定时任务改造 | Story | High | 何蕊 | In Progress | 5 | Sprint 24 |
| PAY-2486 | 结算失败补偿与重试 | Story | High | 何蕊 | In Progress | 5 | Sprint 24 |
| PAY-2487 | 慢 SQL 治理：t_settle_detail 索引优化 | Task | Medium | 李娜 | Done | 3 | Sprint 24 |
| PAY-2488 | 结算监控告警补齐（延迟、差错） | Task | Medium | 张伟 | Done | 3 | Sprint 24 |
| PAY-2493 | 结算报表导出接口改造 | Story | Medium | 何蕊 | Selected | 5 | Sprint 25 |
| PAY-2494 | 渠道对账文件解析（支持多格式） | Story | Medium | 郑凯 | Selected | 8 | Sprint 25 |
| PAY-2495 | 结算幂等键设计（渠道+日期+批次） | Story | High | 吴桐 | Selected | 5 | Sprint 25 |
| PAY-2496 | 灰度开关支持按渠道维度 | Story | Medium | 郑凯 | Backlog | 3 | 待排 |
| PAY-2497 | 结算历史数据迁移（T+1 → T+0） | Story | Highest | 吴桐 | Backlog | 13 | 待排 |
| PAY-2498 | 老结算链路下线开关 | Story | High | 吴桐 | Backlog | 5 | 待排 |

## 三、Bug 清单

| Key | 摘要 | 优先级 | 经办人 | 状态 | 来源 | 关联 Story |
|---|---|---|---|---|---|---|
| PAY-2490 | 支付回调重复入账（幂等失效） | P1 | 郑凯 | Done | 生产 | PAY-2483 |
| PAY-2491 | 结算金额精度丢失（两位小数截断） | P1 | 吴桐 | Done | 测试 | PAY-2481 |
| PAY-2492 | 对账报表导出超时（> 60s） | P2 | 何蕊 | Rejected | 测试 | PAY-2493 |
| PAY-2499 | 结算任务并发时死锁 | P1 | 吴桐 | In Progress | 压测 | PAY-2481 |
| PAY-2500 | 灰度开关配置刷新延迟 30s | P2 | 郑凯 | In Test | 预发 | PAY-2483 |
| PAY-2501 | 渠道对账文件编码异常（GBK） | P3 | 郑凯 | Backlog | 生产 | PAY-2494 |

## 四、典型 Issue 详情

### PAY-2499 · 结算任务并发时死锁

| 字段 | 值 |
|---|---|
| 类型 | Bug |
| 优先级 | P1（Critical） |
| 经办人 | 吴桐 |
| 报告人 | 孙悦（测试） |
| 环境 | 压测环境（8 台 16C32G） |
| 状态 | In Progress |
| 创建时间 | 2026-08-26 15:42 |
| 组件 | settle-service |
| 标签 | 并发 / 死锁 / 压测 |

**描述**：并发执行 8 个渠道结算任务时，MySQL 报 `Deadlock found when trying to get lock`，任务失败率 12%。

**复现步骤**：

1. 压测环境配置 8 个渠道，结算批次日期相同；
2. 同时触发 8 个结算任务；
3. 观察 settle-service 日志与 MySQL 死锁日志。

**期望结果**：8 个任务全部成功，无死锁。
**实际结果**：平均 1 个任务失败，报错 `Deadlock found when trying to get lock; try restarting transaction`。

**根因（初判）**：`update t_settle_batch set status=? where channel=? and settle_date=?` 与 `insert into t_settle_detail` 的顺序在不同渠道间不一致，形成循环等待。

**处置方案**：

- 统一更新顺序（按 channel 编码排序加锁）；
- 结算明细批量插入改为分批（每批 1,000 行）；
- 增加死锁重试（最多 3 次，间隔 100ms）。

**关联**：PAY-2481（结算任务改造）、PAY-2487（索引优化）

### PAY-2491 · 结算金额精度丢失

| 字段 | 值 |
|---|---|
| 类型 | Bug |
| 优先级 | P1 |
| 经办人 | 吴桐 |
| 环境 | 测试环境 |
| 状态 | Done（修复于 2026-08-22） |

**描述**：结算金额 12,345.6789 在处理后变为 12,345.67，小数位被截断为两位，导致商户结算金额偏差。

**根因**：`t_settle_detail.amount` 字段定义为 `decimal(18,2)`，而渠道返回为 4 位小数。

**修复**：字段改为 `decimal(20,4)`，同时在对账校验中增加精度校验规则；历史数据按 4 位小数补零迁移（PAY-2497）。

**验证**：测试环境 128 条用例通过，金额比对一致。

## 五、状态流转统计（本 Epic）

| 状态 | 数量 | 占比 |
|---|---|---|
| Done | 14 | 58% |
| In Test | 2 | 8% |
| In Progress | 3 | 13% |
| Selected | 4 | 17% |
| Backlog | 3 | 13% |
| Rejected | 1 | 4% |

## 六、风险提示

- PAY-2497（历史数据迁移）为 13 点大 Story，尚未拆分，是 O2 达成的关键路径；
- PAY-2499（死锁）未闭环，若带病上线将直接影响资金差错率（KR3）；
- 银联测试环境不稳定影响 PAY-2494（渠道对账文件解析）的联调进度。
