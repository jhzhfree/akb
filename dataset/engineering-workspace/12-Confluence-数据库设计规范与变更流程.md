# 数据库设计规范与变更流程（V2.4）

> 文档编号：DB-STD-12　|　版本：V2.4　|　状态：生效（2026-06-01）
> Owner：李娜（DBA，P7）　|　评审：陈立、吴桐、赵鹏　|　Confluence：KOS-ARCH/DB

## 一、命名规范

| 对象 | 规则 | 示例 |
|---|---|---|
| 库 | 业务域小写，最长 32 字符 | paydb、orderdb |
| 表 | `t_` + 业务名，下划线分隔 | t_pay_order |
| 字段 | 小写下划线，避免保留字 | create_time、pay_status |
| 索引 | `idx_<字段缩写>`；唯一索引 `uk_<字段缩写>` | idx_user_id、uk_out_trade_no |
| 外键 | **不使用**外键约束，由应用层保证 | — |
| 临时表 | `tmp_<业务>_<日期>`，用完即删 | tmp_settle_20260831 |

## 二、字段与类型

| 场景 | 推荐类型 | 说明 |
|---|---|---|
| 主键 | bigint unsigned 自增 或 雪花 ID | 禁用 UUID 做主键（页分裂） |
| 金额 | decimal(20,4) | 禁止 float/double（精度丢失，见 KOS-3306） |
| 状态 | tinyint 或 varchar(16) + 注释枚举 | 状态含义写注释 |
| 时间 | datetime(3) / timestamp | 统一 Asia/Shanghai |
| 大文本 | 独立表存储，主表只留 ID | 避免行溢出 |
| JSON | json（MySQL 8） | 频繁查询的字段不要放 JSON |
| 布尔 | tinyint(1) | 0/1 |

## 三、必含字段

每张业务表必须包含：

```sql
id            bigint unsigned not null auto_increment comment '主键',
create_time   datetime(3) not null default current_timestamp(3) comment '创建时间',
update_time   datetime(3) not null default current_timestamp(3) on update current_timestamp(3) comment '更新时间',
is_deleted    tinyint(1) not null default 0 comment '逻辑删除：0-未删除 1-已删除'
```

资金与核心业务表另需：版本号（乐观锁）、操作人、来源系统。

## 四、索引规范

- 单表索引不超过 6 个，联合索引字段不超过 4 个；
- 区分度低的字段（如 status）不单独建索引，放在联合索引末尾；
- 遵循最左前缀，避免冗余索引（如已有 `(a,b)` 不需 `(a)`）；
- 所有新查询 SQL 必须 `explain` 确认走索引，type 至少为 `range`；
- 禁止在索引列上使用函数或隐式类型转换。

示例（结算明细表）：

```sql
create table t_settle_detail (
  id            bigint unsigned not null auto_increment,
  batch_no      varchar(32) not null comment '结算批次号',
  channel       varchar(16) not null comment '渠道',
  pay_id        varchar(32) not null comment '支付单号',
  amount        decimal(20,4) not null comment '结算金额',
  fee           decimal(20,4) not null default 0 comment '手续费',
  settle_date   date not null comment '结算日期',
  create_time   datetime(3) not null default current_timestamp(3),
  primary key (id),
  unique key uk_batch_pay (batch_no, pay_id),
  key idx_settle_date_channel (settle_date, channel),
  key idx_pay_id (pay_id)
) engine=innodb default charset=utf8mb4 comment='结算明细表';
```

## 五、SQL 编写规范

| 禁止 | 原因 | 替代 |
|---|---|---|
| `select *` | 网络与解析开销，字段变更风险 | 明确字段列表 |
| 不带 where 的 update/delete | 全表误操作（L2 高危） | 必须带条件，先 select 验证 |
| 大事务（> 5 秒） | 锁与主从延迟 | 分批提交（每批 1,000 行 + sleep 50ms） |
| 深分页 `limit 100000,20` | 扫描代价高 | 游标或延迟关联 |
| 三张以上大表 join | 优化器易选错 | 拆分查询或冗余字段 |
| 隐式类型转换 | 索引失效 | 类型一致 |

## 六、变更流程

```text
需求提出（Jira） → SQL 评审（DBA + 开发） → 生成变更脚本（含回滚脚本）
→ 预发验证（explain + 耗时对比） → ITSM 变更单（周二/周四 01:00–05:00）
→ 执行（大表用 gh-ost / pt-osc） → 观察 30 分钟（慢查询、错误率）
→ 闭环（更新表结构文档）
```

| 变更类型 | 工具 | 审批 |
|---|---|---|
| 加索引 | gh-ost | DBA + 值班台 |
| 加字段（可空） | 直接 alter（小表） | DBA |
| 加字段（大表） | gh-ost | DBA + 运维总监 |
| 改字段类型/长度 | gh-ost + 双写 | DBA + 研发负责人 |
| 删字段/删表 | 先改名观察 2 周再删 | 运维总监 |
| 数据修复（DML） | 分批脚本 + 备份 | DBA + 业务负责人 |

## 七、容量与分库分表

| 阈值 | 动作 |
|---|---|
| 单表 > 2,000 万行 | 评估归档或分表 |
| 单表 > 5,000 万行 | 必须分库分表（见 KOS-3402） |
| 单库 > 500GB | 评估拆分 |
| 日增 > 50 万行 | 提前规划分片键 |

分片键选择：按查询主路径（如 user_id 或 merchant_id），避免跨片 join 与分布式事务。

## 八、备份与恢复

| 项目 | 策略 |
|---|---|
| 全量备份 | 每日 02:00（xtrabackup），保留 7 天 |
| binlog | 保留 7 天，用于时间点恢复 |
| 延迟从库 | 延迟 1 小时，用于误操作快速恢复 |
| 恢复演练 | 每季度一次（见《MySQL数据库运维与故障处置手册》） |

## 九、常见错误案例

| 案例 | 原因 | 教训 |
|---|---|---|
| KOS-3306 结算金额精度丢失 | decimal(18,2) 截断 4 位小数 | 金额统一 decimal(20,4) |
| KOS-3309 优惠券查询未走索引 | 新增查询未评审 | 新增 SQL 必做 explain |
| PAY-2499 结算并发死锁 | 更新顺序不一致 | 统一加锁顺序 + 重试 |
| KOS-3312 日志表未清理 | 无归档策略 | 大表必须有归档与清理方案 |
