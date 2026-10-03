# Linux 主机巡检与故障排查操作手册

> 文件编号：OPS-MAN-04　|　版本：V2.2　|　适用场景：CentOS 7.9 / Rocky 9 生产主机
> 最近修订：2026-08-05　|　编写人：张伟（SRE，E10231）

## 一、日常巡检清单

每台生产主机每日 09:30 前完成一次巡检，巡检结果登记在《主机巡检记录表》。重点关注五项：负载、磁盘、内存、网络、关键进程。

| 巡检项 | 健康阈值 | 超标动作 |
|---|---|---|
| 15 分钟平均负载 | < CPU 核数 × 0.7 | 抓 top 进程并分析 |
| 根分区使用率 | < 80% | 清理日志/归档，>90% 立即处理 |
| 内存使用率 | < 85% | 排查内存泄漏与缓存 |
| inode 使用率 | < 80% | 清理小文件目录 |
| TCP 重传率 | < 0.5% | 排查网络与网卡 |
| 关键进程存活 | 全部存活 | 按服务重启流程处理 |

## 二、快速排查命令集

### 2.1 负载与进程

```bash
# 整体负载与运行时间
uptime
# 按 CPU 占用排序，取前 10
ps -eo pid,ppid,user,%cpu,%mem,etime,cmd --sort=-%cpu | head -10
# 实时观察（1 秒刷新）
top -b -n 1 | head -20
# 某进程的线程 CPU（pid=18234）
top -H -p 18234
```

### 2.2 磁盘与 inode

```bash
# 磁盘使用率（排除 tmpfs）
df -hT -x tmpfs -x devtmpfs
# 找出占用最大的目录（限根分区，深度 3）
du -h --max-depth=3 / 2>/dev/null | sort -hr | head -20
# inode 使用率
df -i
# 查找已被删除但仍被进程占用的文件（磁盘不释放的常见原因）
lsof +L1 | head -20
```

### 2.3 内存

```bash
# 内存概览（-h 人类可读）
free -h
# 按内存排序
ps -eo pid,user,%mem,rss,cmd --sort=-rss | head -10
# 观察 slab / 内核占用
cat /proc/meminfo | egrep 'Slab|SReclaimable|Buffers|Cached'
```

### 2.4 网络与连接

```bash
# 监听端口与进程
ss -lntup
# 连接状态统计
ss -s
# 某端口的连接来源 TOP10（如 8080）
ss -nt | awk '{print $5}' | cut -d: -f1 | sort | uniq -c | sort -nr | head -10
# 抓包定位重传（10 秒）
tcpdump -i eth0 -c 200 -w /tmp/cap.pcap
```

## 三、典型故障与处置

### 3.1 磁盘写满导致服务不可用

**现象**：应用报 `No space left on device`，日志出现写入失败，支付下单接口 5xx 上升。

**原因**：应用日志未轮转，单日增长 12GB；或日志文件被删除但进程仍持有句柄，空间未释放。

**处置步骤**：

```bash
# 1) 确认占用目录
du -h --max-depth=2 /data | sort -hr | head
# 2) 归档并清理 7 天前日志（禁止直接用 rm -rf，属 L1）
find /data/applogs -name '*.log.*' -mtime +7 -exec gzip {} \;
find /data/applogs -name '*.log.*.gz' -mtime +30 -delete
# 3) 若空间未释放，重启持有已删除句柄的进程（走变更窗口）
lsof +L1 | grep deleted
# 4) 验证
df -h /data
```

**恢复验证**：`df -h` 显示使用率 < 80%，应用日志恢复正常写入，接口错误率回落。

### 3.2 负载飙高但 CPU 占用不高

**现象**：load 达到 40+，`top` 中 CPU 使用率并不高，业务响应变慢。

**原因**：大量进程处于 D 状态（不可中断睡眠），通常是磁盘 IO 打满或 NFS 挂载异常。

**处置步骤**：

```bash
# 查看 D 状态进程
ps -eo pid,stat,cmd | awk '$2 ~ /D/'
# IO 等待与设备繁忙度
iostat -x 1 3
# 定位 IO 最高的进程（需安装 iotop）
iotop -oP
# NFS 挂载异常检查
mount | grep nfs && nfsstat -c
```

### 3.3 内存不足触发 OOM

**现象**：服务进程突然消失，`dmesg` 出现 `Out of memory: Kill process`。

**处置步骤**：

```bash
# 1) 确认 OOM 记录
dmesg -T | grep -i 'out of memory' | tail -5
# 或（CentOS 7）
grep -i 'killed process' /var/log/messages | tail -5
# 2) 查看当前内存与 cgroup 限制
free -h; cat /sys/fs/cgroup/memory/memory.limit_in_bytes
# 3) 调整 JVM 堆参数（示例：4G 容器给 2.5G 堆）
#    -Xms2500m -Xmx2500m -XX:MaxMetaspaceSize=256m
# 4) 重启服务并观察（走变更窗口）
```

## 四、巡检自动化

巡检由 `ops-inspect` 脚本每日定时执行，结果推送至值班台企业微信群。脚本位置与执行方式：

```bash
# 手动执行一次全量巡检（输出巡检报告）
/opt/ops/scripts/ops-inspect.sh --all --output /tmp/inspect-$(date +%F).txt
# 仅巡检指定主机组
/opt/ops/scripts/ops-inspect.sh --group pay --output /tmp/inspect-pay.txt
```

巡检报告中的 `ALERT` 行必须当日闭环，未闭环项进入次日交接单。

## 五、注意事项

- 所有删除类操作**必须**先 `ls` 确认路径，禁止使用 `rm -rf /` 一类根目录删除（L1 命令）；
- 生产主机执行任何变更类命令前，先在预发环境验证；
- 排查过程涉及 `root` 提权的，按《主机与账号权限管理规范》申请临时提权，有效期 4 小时。
