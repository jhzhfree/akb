# Kubernetes 集群运维操作手册

> 文件编号：OPS-MAN-06　|　版本：V2.1　|　适用版本：Kubernetes 1.28.3（自建，3 master + 12 worker）
> 最近修订：2026-08-20　|　编写人：张伟（SRE，E10231）

## 一、集群与命名空间

| 命名空间 | 用途 | 关键服务 | 资源配额 |
|---|---|---|---|
| payment | 支付核心 | pay-api、pay-worker、pay-gateway | CPU 64 / Mem 128Gi |
| order | 订单 | order-api、order-consumer | CPU 48 / Mem 96Gi |
| infra | 基础设施 | nginx-ingress、prometheus、efk | CPU 32 / Mem 64Gi |
| default | 默认（禁止部署生产服务） | — | — |

常用节点：`k8s-node-01`~`k8s-node-12`，其中 `k8s-node-04`(10.20.30.44) 为支付服务专属节点池。

## 二、日常运维命令

```bash
# 集群与节点状态
kubectl get nodes -o wide
kubectl describe node k8s-node-04

# 命名空间下工作负载
kubectl -n payment get pod -o wide
kubectl -n payment get deploy,sts,svc,ing

# 查看事件（排障首选）
kubectl -n payment get events --sort-by=.lastTimestamp | tail -30

# 资源使用
kubectl top node
kubectl -n payment top pod
```

## 三、发布与回滚

### 3.1 滚动发布

```bash
# 更新镜像（走 CI，禁止手工 kubectl set image 到生产）
kubectl -n payment set image deploy/pay-api pay-api=registry.kos.local/pay/api:v2.8.3
# 观察滚动状态
kubectl -n payment rollout status deploy/pay-api --timeout=300s
# 查看历史版本
kubectl -n payment rollout history deploy/pay-api
```

### 3.2 回滚

```bash
# 回滚到上一版本
kubectl -n payment rollout undo deploy/pay-api
# 回滚到指定版本（revision=7）
kubectl -n payment rollout undo deploy/pay-api --to-revision=7
# 验证
kubectl -n payment rollout status deploy/pay-api
kubectl -n payment get pod -l app=pay-api -o wide
```

> `kubectl scale --replicas=0` 属 L2 高危命令：服务缩容至零会直接造成业务中断，如需暂停流量应使用灰度权重调整或 ingress 摘流。

## 四、典型故障与处置

### 4.1 Pod 处于 CrashLoopBackOff

**现象**：`kubectl get pod` 显示 `CrashLoopBackOff`，重启次数持续增长。

**排查步骤**：

```bash
kubectl -n payment describe pod pay-api-7d9c8f6b5-2xk9p
kubectl -n payment logs pay-api-7d9c8f6b5-2xk9p --previous
# 常见原因：配置缺失、依赖不可达、健康检查阈值过严、OOMKilled
kubectl -n payment get pod pay-api-7d9c8f6b5-2xk9p -o jsonpath='{.status.containerStatuses[0].lastState}'
```

### 4.2 Pod 处于 Pending

**现象**：Pod 长时间 Pending，事件显示 `FailedScheduling`。

| 事件信息 | 原因 | 处置 |
|---|---|---|
| `Insufficient cpu/memory` | 资源不足 | 扩容节点或调整 requests |
| `node(s) had taint` | 节点污点 | 补 toleration 或改用其它节点池 |
| `didn't match PodAntiAffinity` | 反亲和过严 | 调整副本数或亲和策略 |
| `persistentvolumeclaim not found` | PVC 缺失 | 创建 PVC 或检查 StorageClass |

### 4.3 服务滚动发布后 P99 升高

**现象**：v2.8.3 发布后支付接口 P99 从 320ms 升至 1.8s，错误率 0.4% → 3.2%。

**处置步骤**：

1. 立即摘流：`kubectl -n payment patch ingress pay-ing -p '{"metadata":{"annotations":{"nginx.ingress.kubernetes.io/canary":"false"}}}'`；
2. 回滚：`kubectl -n payment rollout undo deploy/pay-api`；
3. 定位：对比发布前后 `kubectl top pod`、应用日志与 APM 链路，确认为新版本引入的慢 SQL；
4. 修复后按灰度 5% → 30% → 100% 重新发布。

### 4.4 节点 NotReady

```bash
kubectl describe node k8s-node-04
# 常见：kubelet 停止、磁盘满（imagefs）、网络插件异常
systemctl status kubelet
journalctl -u kubelet -n 100 --no-pager
df -h /var/lib/containerd
# 节点恢复后确认 Pod 重新调度
kubectl get pod -A -o wide | grep k8s-node-04
```

## 五、弹性伸缩

| 类型 | 配置要点 | 适用场景 |
|---|---|---|
| HPA | 目标 CPU 60%，最小 3 最大 20，冷却 300s | 日常流量波动 |
| 节点池扩容 | 支付节点池峰值水位 70% 触发 | 大促与压测 |
| 定时伸缩 | 大促前 1 小时预扩容 | 已知峰值 |

```bash
kubectl -n payment get hpa
kubectl -n payment describe hpa pay-api-hpa
```

## 六、配置与密钥

- 配置走 ConfigMap，密钥走 Secret，**禁止**把数据库口令写入镜像或环境变量明文；
- ConfigMap 变更不会自动触发 Pod 重启，需滚动重启：`kubectl -n payment rollout restart deploy/pay-api`；
- 生产命名空间启用 `ResourceQuota` 与 `LimitRange`，防止单服务吃满节点。
