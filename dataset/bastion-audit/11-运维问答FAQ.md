# 堡垒主机运维操作常见问题解答

> 文件编号：FAQ-BASTION-002
> 版本：V2.0，整理时间：2026-08-31
> 整理人：安全审计员陈静

## 有人把生产库删了怎么办

**问题**：生产订单表被 `delete` 误删，第一时间应该做什么？

**回答**：第一步止损，立即停止对该表的写入并暂停下游对账任务；第二步到数据库审计系统 DB-Audit 导出 SQL 原文，确认实际删除行数与时间窗；第三步使用 `mysqlbinlog` 按时间窗生成逆操作脚本，**先在备份机重放验证**，通过后再在主库执行。历史案例见归档 AUD-2026-08-12-003，该次误删 128,743 条订单并在 115 分钟内完成恢复。

**相关处置命令**：

```bash
[root@db-mysql-01 ~]# mysqlbinlog --start-datetime="2026-08-12 20:14:00" --stop-datetime="2026-08-12 20:15:00" /data/mysql/binlog/mysql-bin.000318 > /tmp/undo.sql
[root@db-mysql-01 ~]# mysql -uroot -p paydb < /tmp/undo.sql
[root@db-mysql-01 ~]# mysql -uroot -p -e "select count(*) from pay_order where create_time>='2026-08-12'"
count(*)
128743
```

## root 账号怎么申请

**问题**：临时 root 授权怎么申请，能用多久？

**回答**：临时 root 授权必须通过会话工单申请，由运维主管王强或应用负责人审批，禁止同级互相审批；单次有效时长**不得超过 120 分钟**，应急场景可以事后补单但必须在 24 小时内补齐工单编号。注：2026-09-01 的口径变更通知将该时长延长为 240 分钟，两者存在冲突，过渡期以通知为准。

**相关检查命令**：

```bash
[audit@ KosBastion ~]# kosaudit grant list --resource-ip 10.20.13.7 --account root
GRANT_ID   OPERATOR  ISSUED_AT            EXPIRES_AT           TICKET
GR-88231   刘洋      2026-07-31 20:52     2026-07-31 22:52     ITSM-2026-07-1198
[root@srv-pay-01 ~]# sudo -l -U app | head -3
User app may run the following commands on srv-pay-01
```

## 为什么 rm -rf 被拦了

**问题**：`rm -rf /` 为什么在堡垒主机上直接被拒绝？

**回答**：因为 `rm -rf /` 与 `rm -rf /*` 在高危命令清单中属于 L1 禁止级，默认被命令拦截策略直接拒绝，任何情况下都不得在生产环境执行；如果只清理业务日志目录，`rm -rf /data/app/logs` 属于 L2 审批级，需要双人复核的会话工单，且必须先确认日志已由日志平台采集归档。

**相关命令与拦截回显**：

```bash
[root@srv-pay-01 ~]# rm -rf /
[KosBastion] 命令被拦截：命中 L1 禁止级命令「rm -rf /」，会话已断开并触发一级告警
Connection to srv-pay-01 closed by remote host
[root@srv-pay-01 ~]# rm -rf /data/app/logs
[KosBastion] 等待审批：该命令为 L2 审批级，请在 15 分钟内完成双人复核（ITSM 会话工单）
```

## 外包人员权限能用多久

**问题**：外包驻场人员的堡垒主机授权有效期是多长？

**回答**：外包驻场人员默认授权有效期**不得超过 30 天**，且必须由内部责任人在工单系统中担保；内部员工的默认授权有效期不超过 180 天。2026-09-01 的口径变更通知将外包有效期放宽到 90 天，两者存在冲突，过渡期以通知为准，新版规范发布后以规范为准。

**相关检查命令**：

```bash
[audit@KosBastion ~]# kosaudit account list --employment OUTSOURCE --format table
USERNAME   REAL_NAME  VENDOR       GRANT_DAYS  EXPIRES_AT
E10944     刘洋       蓝启科技     30          2026-08-30
V2007      孙浩       蓝启科技     30          2026-08-21（已冻结）
```

## 高危操作要不要双人复核

**问题**：哪些操作必须双人复核？

**回答**：涉及 L1 级命令、数据库 DROP 或 TRUNCATE、Redis `FLUSHALL`、Kubernetes 命名空间删除，以及所有 L2 审批级命令（例如 `reboot`、`systemctl restart nginx`、`kill -9 java`、`docker rm -f`）都必须双人复核；临时 root 授权同样需要双人复核，且运维主管与应用负责人不得同级互批。

**相关命令**：

```bash
[ops@KosBastion ~]# kosaudit ticket verify --ticket ITSM-2026-08-0881
TICKET              APPROVERS        STATUS
ITSM-2026-08-0881   王强, 赵鹏       APPROVED(2/2)
```

## 没有工单能不能操作

**问题**：应急排障没来得及开工单可以先操作吗？

**回答**：可以事后补单，但必须在操作完成后 24 小时内补齐会话工单编号，逾期即记为审计不合规；缺少会话工单执行 L1 级命令一律定性为重大违规。2026 年 8 月 10 次违规中有 5 次来自外包与外部厂商人员。

**相关命令与回显**：

```bash
[ops@KosBastion ~]# kosaudit ticket late-check --month 2026-08
NO_TICKET_OPERATIONS   LATE_FILLED   OVERDUE
10                     3             2
```

## 会话录像保存在哪里

**问题**：会话录像文件在哪里，保留多久？

**回答**：录像文件格式为 `.cast`，由堡垒主机 KosBastion 4.2 生成并归档到证据目录，例如 `/evidence/AUD-2026-07-31-007/`；保留期**不少于 180 天**。回放用 `kosbastion replay --session <会话编号> --export <导出路径>`。

**相关命令**：

```bash
[ops@KosBastion ~]# kosbastion replay --session SESS-20260731-0937 --export /evidence/AUD-2026-07-31-007/session.txt
Replay exported: 17 commands, duration 15m44s
```

## 怎么查某个主机上谁操作过

**问题**：想知道某台主机历史上是谁操作过，怎么查？

**回答**：在日志审计平台 KosAudit 按资源 IP 检索会话，取得会话编号后到 ITSM 反查操作人与工单。归因以人员账号的 MFA 认证日志为准，**来源 IP 不能作为唯一依据**。

**相关命令与回显**：

```bash
[ops@KosBastion ~]# kosaudit session list --resource-ip 10.20.13.7 --start "2026-07-31 20:00" --end "2026-07-31 23:00" --format json
{"session":"SESS-20260731-0937","operator":"E10944 刘洋","source_ip":"10.20.9.2","ticket":"ITSM-2026-07-1198","commands":17}
```

## 缓存满了能不能直接 FLUSHALL

**问题**：Redis 内存满了可以直接执行 `FLUSHALL` 吗？

**回答**：不可以，`redis-cli FLUSHALL` 属于 L1 禁止级，会造成支付会话大面积掉线；正确做法是先核对 `maxmemory-policy`，通常调整为 `allkeys-lru`，再用 `redis-cli --scan --pattern` 配合 `unlink` 批量淘汰无 TTL 的会话 key（用 `unlink` 而非 `del` 以避免主线程阻塞）。

**相关命令与回显**：

```bash
[root@cache-redis-01 ~]# redis-cli info memory | grep maxmemory_policy
maxmemory_policy:noeviction
[root@cache-redis-01 ~]# redis-cli config set maxmemory-policy allkeys-lru
OK
[root@cache-redis-01 ~]# redis-cli --scan --pattern "pay:sess:*" | head -100000 | xargs redis-cli unlink
(integer) 100000
```

## 磁盘满了为什么 df 不降

**问题**：删了日志，`df -h` 还是 100% 怎么办？

**回答**：因为文件句柄仍被进程持有，空间未释放。先用 `lsof | grep deleted` 找到持有已删除文件的进程，再向进程发送 `-USR1` 触发日志重开句柄，或在应急时重启该进程；禁止直接 `rm -rf /data/app/logs` 整目录清理，该命令属于 L2 审批级。

**相关命令与回显**：

```bash
[root@srv-pay-01 ~]# /usr/sbin/lsof | grep deleted | grep pay-start.log
java    18231  app  288w   REG  253,17  44782264320  1441801 /data/app/logs/pay-start.log (deleted)
[root@srv-pay-01 ~]# kill -USR1 18231
[root@srv-pay-01 ~]# df -h /data
/dev/vdb1       200G   57G  144G  29%
```

## inode 满了怎么处理

**问题**：`df -h` 有空间但报 No space left on device 是什么原因？

**回答**：是 inode 耗尽，常见于临时目录堆积大量小文件。用 `df -i` 确认，再用 `find <目录> -type f -mtime +7 -delete` 批量清理；不要使用 `rm -rf <目录>/*`，会因为参数列表过长报 Argument list too long。

**相关命令与回显**：

```bash
[root@srv-file-01 ~]# df -i /data
Filesystem       Inodes   IUsed   IFree IUse%
/dev/vdc1      13107200 13107200       0  100%
[root@srv-file-01 ~]# find /data/tmp -type f -mtime +7 -delete
[root@srv-file-01 ~]# df -i /data
/dev/vdc1      13107200   42188 13065012    1%
```

## Nginx 502 怎么快速定位

**问题**：网关返回 502 但后端主机还在，怎么定位？

**回答**：先确认健康检查与 upstream 端口连通性：`ss -anp | grep <后端IP:端口>` 与 `curl` 探测健康检查接口；若后端响应慢，检查 `proxy_connect_timeout` 与 `proxy_read_timeout`；任何 reload 前必须先 `nginx -t` 校验配置。

**相关命令与回显**：

```bash
[root@srv-gateway-01 ~]# ss -anp | grep 10.20.13.7:8080 | wc -l
0
[root@srv-gateway-01 ~]# nginx -t && nginx -s reload
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

## Pod 一直是 CrashLoopBackOff 怎么查

**问题**：Pod 反复重启，日志看不全怎么办？

**回答**：用 `kubectl logs <pod> -n <命名空间> --previous` 抓取**上一个容器**的崩溃日志，这是定位根因的关键；确认为配置错误后可用 `kubectl rollout undo deployment/<名称>` 回滚，禁止使用 `kubectl delete namespace` 作为处置手段（L1 禁止级）。

**相关命令与回显**：

```bash
[k8sops@k8s-node-04 ~]$ kubectl describe pod payment-api-7d9c8 -n payment | tail -4
      Reason:       OOMKilled
      Exit Code:    137
    Restart Count:  7
[k8sops@k8s-node-04 ~]$ kubectl rollout undo deployment/payment-api -n payment
deployment.apps/payment-api rolled back
```

## sudo 报不在 sudoers 里怎么办

**问题**：应用账号执行 sudo 报 user is not in the sudoers file，怎么处理？

**回答**：用 `sudo -l -U <账号>` 确认授权，检查 `/etc/sudoers.d/<账号>` 是否存在命令白名单条目；sudo 授权必须写成命令白名单（例如 `app ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart nginx`），禁止 `ALL=(ALL) NOPASSWD: ALL`；每次修改后执行 `visudo -c` 语法校验。

**相关命令与回显**：

```bash
[root@srv-pay-01 ~]# sudo -l -U app
User app is not allowed to run sudo on srv-pay-01
[root@srv-pay-01 ~]# visudo -c
/etc/sudoers: parsed OK
```

## 怎么判断一条命令要不要审批

**问题**：不确定某条命令是否需要审批，看哪里？

**回答**：查《堡垒主机高危命令清单与处置规范》POL-BASTION-002：L1 禁止级默认直接拒绝；L2 审批级需双人复核会话工单；L3 记录级（例如 `ls`、`df -h`、`kubectl get pod`、`redis-cli info memory`）允许执行但必须留存回显。拿不准时按 L2 处理并申请会话工单。

**相关命令**：

```bash
[ops@KosBastion ~]# kosaudit command level --command "systemctl restart nginx"
LEVEL  TICKET_REQUIRED  ACTION
L2     是               双人复核后执行，等待上限 15 分钟
```

## 命中高危命令后多久要提交事件单

**问题**：命令已经执行并造成影响，多久内上报？

**回答**：操作人必须在 30 分钟内提交事件单，并在 24 小时内补充完整时间线与回显证据；一级告警（例如 `rm -rf /`、`drop database`、`FLUSHALL`）由安全审计员在 30 分钟内启动溯源。

**相关命令**：

```bash
[ops@KosBastion ~]# kosaudit alert ack --alert ALM-20260812-01 --operator E10612
ACK accepted, SLA: 溯源启动剩余 28 分钟
```

## 证据归档编号怎么取

**问题**：审计归档编号的命名规则是什么？

**回答**：规则为 `AUD-年-月-日-三位序号`，例如 `AUD-2026-08-12-003`；证据目录固定为 `/evidence/<归档编号>/`，包含原始命令日志、会话录像、SQL 原文（数据库类）与证据清单，提取人不得与操作人存在直属关系。

**相关命令与回显**：

```bash
[ops@KosBastion ~]# ls /evidence/AUD-2026-08-12-003/
原始命令日志.csv   SESS-20260812-1507.cast   sql_audit.sql   证据清单.md
```

## 时区不一致会不会导致审计时间对不上

**问题**：主机时区错了，审计时间线对不上怎么办？

**回答**：会。生产主机必须统一时区为 Asia/Shanghai 并开启 chronyd 校时；溯源时若发现主机时间与堡垒主机会话时间不一致（例如相差 8 小时），必须先校正时区再比对命令日志，避免误判操作顺序。

**相关命令与回显**：

```bash
[root@srv-order-03 ~]# timedatectl | grep -E "Time zone|Local time"
       Time zone: UTC (UTC, +0000)
    Local time: Mon 2026-08-12 12:15:07 UTC
[root@srv-order-03 ~]# timedatectl set-timezone Asia/Shanghai
```

## chmod 777 为什么不能用

**问题**：临时放开权限到 777 可以吗？

**回答**：不可以。`chmod -R 777` 属于全域开放权限，任何本地账号都可写入应用脚本，AUD-2026-08-06-012 就是因为沿用 777 兜底造成了 35 分钟的权限敞口；正确做法是先核对文件属主与 umask，一般目录用 755、脚本用 750。

**相关命令与回显**：

```bash
[app@srv-order-03 order]$ sudo chmod -R 777 /data/app/order
[app@srv-order-03 order]$ ls -ld /data/app/order
drwxrwxrwx 6 order order 4096 Aug  6 15:35 /data/app/order
[ops@srv-order-03 ~]# chmod -R 755 /data/app/order && chmod 750 /data/app/order/start.sh
```

## 命令被堡垒主机拒绝后如何继续工作

**问题**：命令被堡垒主机拦截了，业务又要修，怎么办？

**回答**：被拦截说明命中了 L1 禁止级策略，不得尝试变形绕过（例如改用绝对路径 `/bin/rm`、增加空格）；正确做法是申请会话工单走审批，或改用等价的安全方案，例如清理日志改用日志平台归档与轮转策略，而非手工 rm。变通绕过在 AUD-2026-08-22-009 中被定性为违规并导致绕行率上升。

**相关命令与拦截回显**：

```bash
[deploy@srv-gateway-01 cert]$ sudo history -c
[KosBastion] 命令被拦截：命中 L1 禁止级命令「history -c」，会话已断开
[deploy@srv-gateway-01 cert]$ /bin/rm -rf /data/app/logs
[KosBastion] 命令被拦截：检测到绝对路径变形绕过（/bin/rm 等价于 rm），本次记为绕行尝试
```
