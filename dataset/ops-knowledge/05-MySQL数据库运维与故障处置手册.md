# MySQL 数据库运维与故障处置手册

> 文件编号：OPS-MAN-05　|　版本：V3.0　|　适用版本：MySQL 8.0.32（主从 + 半同步）
> 最近修订：2026-08-18　|　编写人：李娜（DBA，E10455）

## 一、实例清单与职责

| 实例 | 地址 | 角色 | 承载库 | 资源账号 |
|---|---|---|---|---|
| db-mysql-01 | 10.20.20.11 | 主库 | paydb、orderdb | mysql |
| db-mysql-02 | 10.20.20.12 | 从库（半同步） | paydb、orderdb | mysql |
| db-mysql-03 | 10.20.20.13 | 从库（延迟 1h，用于误操作恢复） | paydb、orderdb | mysql |
| db-report-01 | 10.20.20.21 | 报表只读库 | 全库只读 | mysql |

## 二、日常巡检项

| 巡检项 | 命令 | 健康阈值 |
|---|---|---|
| 主从延迟 | `show slave status\G` | < 1s，超过 5s 告警 |
| 连接数 | `show status like 'Threads_connected'` | < max_connections × 0.7 |
| 慢查询数量 | 慢日志按小时聚合 | 单条 > 1s 的需分析 |
| 锁等待 | `select * from information_schema.innodb_lock_waits` | 无长时间等待 |
| 磁盘与 binlog | `df -h` + binlog 保留策略 | binlog 保留 7 天 |

```sql
-- 主从状态关键字段
show slave status\G
-- 关注：Slave_IO_Running / Slave_SQL_Running / Seconds_Behind_Master / Last_Error

-- 当前活跃会话（按耗时排序）
select id,user,host,db,command,time,state,left(info,80) as sql_text
from information_schema.processlist
where command != 'Sleep'
order by time desc limit 20;
```

## 三、典型故障与处置

### 3.1 主从延迟突增

**现象**：监控显示 `Seconds_Behind_Master` 从 0.2s 涨到 40s+，报表数据滞后，读写分离读到旧数据。

**常见原因**：

1. 主库大批量写入（批量更新、数据修复、DDL）；
2. 从库执行大查询占用资源；
3. 从库 IO 打满（磁盘或网络）；
4. 主库 binlog 格式与从库回放冲突（`Last_Error` 有明确报错）。

**处置步骤**：

```sql
-- 1) 确认延迟与错误信息
show slave status\G
-- 2) 定位从库上正在执行的大事务/大查询
select id,user,time,state,left(info,120) from information_schema.processlist order by time desc limit 10;
-- 3) 如是报表查询拖慢从库，临时 kill（需确认是可中断的只读查询）
kill <id>;
-- 4) 若是主库批量写入导致，联系业务方降速或改为分批提交（每批 1000 行 + sleep 50ms）
```

**恢复验证**：`Seconds_Behind_Master` 回落至 1s 以内并持续 10 分钟。

### 3.2 数据误删与恢复（binlog 回滚）

**现象**：误执行 `delete from t_order where create_time < '2026-08-01'`，影响 128,743 行，业务报数据缺失。

**处置步骤**（必须使用延迟从库 db-mysql-03，避免影响生产主从）：

```bash
# 1) 立即停止延迟从库的复制，保住误删前的数据
mysql -h 10.20.20.13 -e "stop slave;"
# 2) 确认误删时间点与 GTID
mysql -h 10.20.20.13 -e "show slave status\G" | egrep 'Seconds_Behind|Executed_Gtid_Set'
# 3) 从延迟从库导出误删数据（按条件导出）
mysqldump -h 10.20.20.13 paydb t_order --where="create_time < '2026-08-01'" \
  --single-transaction --set-gtid-purged=OFF > /data/backup/t_order_recover.sql
# 4) 在主库回放（先在预发验证 SQL 正确性）
mysql -h 10.20.20.11 paydb < /data/backup/t_order_recover.sql
# 5) 恢复延迟从库复制
mysql -h 10.20.20.13 -e "start slave;"
```

**恢复验证**：`select count(*) from t_order where create_time < '2026-08-01'` 与备份报表一致。

> 严禁直接执行 `drop database` / `truncate table`（L1/L2 命令）。误删后**第一时间**停止相关从库复制，保住现场。

### 3.3 死锁与锁等待超时

**现象**：应用报 `Lock wait timeout exceeded; try restarting transaction`，下单接口超时。

```sql
-- 查看最近的死锁信息
show engine innodb status\G
-- 查看当前锁等待关系
select waiting_pid, waiting_query, blocking_pid, blocking_query
from sys.innodb_lock_waits;
-- 终止阻塞会话（需业务确认）
kill <blocking_pid>;
```

**根治措施**：统一更新顺序、缩小事务范围、为高频条件补索引，避免大事务。

### 3.4 连接数打满

**现象**：`Too many connections`，新连接全部失败。

```sql
-- 查看连接来源分布
select user, host, count(*) from information_schema.processlist group by user, host order by 3 desc;
-- 临时提升连接数（重启失效，需同步改配置文件）
set global max_connections = 1000;
-- 定位连接泄漏的应用（观察 host 分布 + 应用连接池配置）
```

## 四、备份与恢复策略

| 备份类型 | 周期 | 保留 | 存放位置 |
|---|---|---|---|
| 全量（xtrabackup） | 每日 02:00 | 7 天 | /data/backup + 对象存储 |
| binlog | 实时 | 7 天 | 主库本地 + 对象存储 |
| 月度归档 | 每月 1 日 | 12 个月 | 对象存储 |

恢复演练：每季度执行一次「全量 + binlog 时间点恢复」演练，演练结果记入《数据库恢复演练记录》。

## 五、变更与 DDL 规范

- 所有 DDL 走 ITSM 变更单，执行窗口为**周二/周四 01:00–05:00**；
- 大表 DDL 必须使用 `gh-ost` 或 `pt-online-schema-change`，禁止直接 `alter table`；
- 变更后 30 分钟内观察慢查询与业务错误率，异常立即回滚。
