# 支付系统架构设计（V3.2）

> 文档编号：ARCH-PAY-10　|　版本：V3.2　|　状态：已评审（2026-08-10）
> Owner：赵鹏（P8）　|　评审：陈立、吴桐、李娜、陈静　|　Confluence：KOS-ARCH/PAY

## 一、系统定位与边界

支付系统承担收款、退款、清结算与对账四类职责，对外提供支付、查询、退款、结算四类能力，不承担账务核算（由财务系统负责）。

| 边界 | 说明 |
|---|---|
| 负责 | 支付受理、渠道路由、支付状态机、退款、清结算任务、对账与差错处理 |
| 不负责 | 财务凭证、总账、税务开票、资金调拨 |

## 二、整体架构

```text
[商户/业务方] → [pay-gateway] → [pay-api] → [pay-core] → [渠道适配层] → [银联/支付宝/微信]
                    │               │
                    ├─ [风控规则引擎]  ├─ [settle-service 清结算]
                    └─ [pay-callback 回调]  └─ [settle-report 报表]
                              │
                    [Kafka] → [order-consumer] → [对账与差错处理]
                              │
                    [Prometheus / APM / KosAudit]
```

## 三、核心模块

| 模块 | 职责 | 技术栈 | 部署 |
|---|---|---|---|
| pay-gateway | 渠道接入、协议转换、限流 | Spring Cloud Gateway | K8s（4 副本） |
| pay-api | 支付下单、查询、退款接口 | Spring Boot 3 + Java 17 | K8s（8 副本） |
| pay-core | 支付状态机、幂等、路由 | Spring Boot 3 | K8s（6 副本） |
| pay-callback | 渠道回调处理与通知 | Spring Boot 3 | K8s（4 副本） |
| settle-service | 清结算任务、对账、差错 | Spring Boot 3 + XXL-JOB | K8s（4 副本） |
| settle-report | 对账报表与导出 | Spring Boot 3 | K8s（2 副本） |
| 风控引擎 | 规则匹配、限额与黑名单 | Drools + Redis | K8s（3 副本） |

## 四、关键设计

### 4.1 支付状态机

```text
INIT（初始化） → PAYING（支付中） → SUCCESS（成功）
                     ↓                    ↓
                  FAILED（失败）      REFUNDING（退款中） → REFUNDED（已退款）
                     ↓                    ↓
                  CLOSED（关闭）      REFUND_FAILED（退款失败）
```

- 状态流转必须走状态机，禁止直接 update status；
- 终态（SUCCESS/FAILED/CLOSED/REFUNDED）不可再流转；
- 异常流转记录告警（KOS-3313 曾出现「已取消 → 已完成」）。

### 4.2 幂等设计

| 场景 | 幂等键 | 存储 |
|---|---|---|
| 下单 | `merchant_id + out_trade_no` | Redis（TTL 24h）+ 唯一索引 |
| 回调 | `trade_no + channel` | 唯一索引（曾因 order_id 导致重复入账，见 KOS-3305） |
| 退款 | `refund_no` | 唯一索引 |
| 结算 | `channel + settle_date + batch_no` | 唯一索引 |

> 教训：幂等键必须包含**渠道侧唯一标识**，仅用业务单号无法抵御渠道重试。

### 4.3 渠道路由

| 维度 | 策略 |
|---|---|
| 渠道可用性 | 健康检查失败自动摘除（30s 探测） |
| 成本 | 优先低成本渠道（配置权重） |
| 成功率 | 近 5 分钟成功率低于 95% 降权 |
| 灰度 | 按商户比例或渠道维度路由新旧链路（PAY-2483） |

### 4.4 清结算（T+0 改造）

- 现状：T+1 批处理，每日 02:00 结算前一日交易；
- 目标：T+0，按渠道维度并行结算，支持日内多批次（PAY-2481/2485）；
- 结算数据模型：批次（t_settle_batch）→ 明细（t_settle_detail，decimal(20,4)）→ 汇总（t_settle_summary）；
- 差错处理：对账差异进入差错池，支持人工补单（PAY-2482）与自动补偿（KOS-3407）。

## 五、数据模型（核心表）

| 表 | 用途 | 关键字段 | 数据量 |
|---|---|---|---|
| t_pay_order | 支付主单 | pay_id, merchant_id, out_trade_no, amount, status | 2.1 亿 |
| t_pay_channel_order | 渠道单 | trade_no, channel, channel_status | 2.3 亿 |
| t_refund_order | 退款单 | refund_no, pay_id, refund_amount, status | 860 万 |
| t_settle_batch | 结算批次 | batch_no, channel, settle_date, status | 12 万 |
| t_settle_detail | 结算明细 | batch_no, pay_id, amount(20,4), fee | 3.4 亿 |

## 六、非功能性设计

| 维度 | 指标 | 措施 |
|---|---|---|
| 可用率 | ≥ 99.95% | 多副本、渠道路由、限流降级 |
| 性能 | 下单 P99 ≤ 200ms | 缓存、异步化、连接池治理 |
| 一致性 | 资金零差错 | 幂等、对账、差错池、每日巡检 |
| 容量 | 峰值 8,000 TPS | 水平扩容 + 分库分表（Q4） |
| 安全 | 无敏感信息泄露 | 字段加密、脱敏展示、权限最小化 |
| 可观测 | 全链路 traceId | APM、Prometheus、结构化日志 |

## 七、已知风险与演进

| 风险 | 影响 | 计划 |
|---|---|---|
| t_pay_order 单表过大 | DDL 与查询风险 | Q4 分库分表 |
| 定时任务分散 | 冲突与漏跑 | Q4 XXL-JOB 统一调度 |
| 计费逻辑重复（pay-api / settle-service） | 口径不一致 | 抽取公共计费库（KOS-3401） |
| 渠道路由配置分散 | 配置漂移 | 统一配置中心（KOS-3410） |
