# 中间件运维手册：Redis 与 Kafka

> 文件编号：OPS-MAN-07　|　版本：V1.8　|　适用版本：Redis 6.2（哨兵）、Kafka 3.5.1
> 最近修订：2026-08-10　|　编写人：周涛（中间件组，E10733）

## 一、实例清单

| 组件 | 实例 | 地址 | 用途 | 容量/分区 |
|---|---|---|---|---|
| Redis | cache-redis-01 | 10.20.21.5 | 会话与热点缓存（主） | 16Gi，maxmemory-policy=allkeys-lru |
| Redis | cache-redis-02/03 | 10.20.21.6/7 | 从节点 | 16Gi |
| Kafka | kafka-broker-01~03 | 10.20.22.11~13 | 订单与支付事件总线 | 主题 42 个，分区 216 |

## 二、Redis 运维

### 2.1 常用命令

```bash
# 连接与基本信息
redis-cli -h 10.20.21.5 -p 6379 info server
# 内存与命中率
redis-cli info memory | egrep 'used_memory_human|maxmemory_human|maxmemory_policy'
redis-cli info stats | egrep 'keyspace_hits|keyspace_misses'
# 大 key 扫描（抽样，避免阻塞）
redis-cli --bigkeys
# 慢日志（默认 10ms）
redis-cli slowlog get 20
```

### 2.2 命中率与容量

| 指标 | 健康阈值 | 超标动作 |
|---|---|---|
| 命中率 | > 95% | 检查 key 过期策略与缓存穿透 |
| 内存使用率 | < 75% | 清理无效 key 或扩容 |
| 连接数 | < 5000 | 检查客户端连接泄漏 |
| 慢日志条数/分钟 | < 10 | 定位大 key 与复杂命令 |

### 2.3 典型故障

**缓存雪崩**：大量 key 同时过期，请求直击数据库。

- 处置：为 key 增加随机过期抖动（基础 TTL + 随机 0~300s）；开启本地缓存兜底；必要时限流；
- 禁止使用 `FLUSHALL` 清缓存（L2 命令），应按键前缀清理：

```bash
# 按前缀删除（避免 KEYS 阻塞）
redis-cli --scan --pattern 'pay:sess:*' | xargs -L 1000 redis-cli del
```

**热 key**：单 key QPS 过高导致单分片打满。

- 定位：`redis-cli --hotkeys`（需 LFU 策略）或监控面板 TOP key；
- 处置：拆分 key、加本地缓存、读写分离。

### 2.4 Redis 主从切换

哨兵模式自动切换，运维需确认切换后应用连接正常：

```bash
redis-cli -h 10.21.21.5 -p 26379 sentinel masters
redis-cli -h 10.21.21.5 -p 26379 sentinel get-master-addr-by-name kosmaster
```

## 三、Kafka 运维

### 3.1 常用命令

```bash
# 主题与分区
kafka-topics.sh --bootstrap-server 10.20.22.11:9092 --list
kafka-topics.sh --bootstrap-server 10.20.22.11:9092 --describe --topic order.created

# 消费组积压
kafka-consumer-groups.sh --bootstrap-server 10.20.22.11:9092 \
  --describe --group order-consumer-group

# 重置消费位点（谨慎：会造成重复消费或丢消息）
kafka-consumer-groups.sh --bootstrap-server 10.20.22.11:9092 \
  --group order-consumer-group --topic order.created --reset-offsets --to-earliest --execute
```

### 3.2 积压处置

| 积压量 | 等级 | 处置 |
|---|---|---|
| < 1 万 | 观察 | 检查消费者耗时即可 |
| 1 万–50 万 | P2 | 扩容消费者实例（不超过分区数） |
| > 50 万 或持续增长 | P1 | 扩容分区 + 消费者，必要时降级非核心消费逻辑 |

### 3.3 常见故障

**消费阻塞**：单个分区消息处理异常导致消费卡住（如依赖接口超时）。

- 定位：消费组 `LAG` 集中在某几个分区，消费者日志出现重复异常；
- 处置：修复消费者逻辑 → 重试或进入死信队列 `order.created.DLT` → 观察 LAG 回落。

**分区不均衡**：扩容 broker 后分区未迁移。

```bash
# 生成重分配方案
kafka-reassign-partitions.sh --bootstrap-server 10.20.22.11:9092 \
  --topics-to-move-json-file topics.json --broker-list "1,2,3" --generate
# 执行（限流避免打满网络）
kafka-reassign-partitions.sh --bootstrap-server 10.20.22.11:9092 \
  --reassignment-json-file reassignment.json --execute --throttle 10485760
```

## 四、变更规范

- Redis/Kafka 参数变更走 ITSM 变更单，窗口为**周二/周四 01:00–05:00**；
- 涉及数据清理的变更必须先在预发验证，并保留回滚脚本；
- 变更完成后 30 分钟内观察命中率、LAG、错误率三项指标。

## 五、监控告警对应关系

| 组件 | 告警项 | 阈值 | 对应等级 |
|---|---|---|---|
| Redis | 内存使用率 | > 85% 持续 5 分钟 | P2 |
| Redis | 命中率 | < 90% 持续 10 分钟 | P2 |
| Redis | 主从断开 | 立即 | P1 |
| Kafka | 消费 LAG | > 50 万 | P1 |
| Kafka | Broker 下线 | 立即 | P1 |
| Kafka | ISR 收缩 | < 2 | P2 |
