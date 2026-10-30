# V1.1 Production Go / No-Go Checklist

- [ ] Maven package 通过（目标 JDK/Java 8 兼容检查通过）
- [ ] Testcontainers MySQL/Flyway V1-V7 通过
- [ ] Elasticsearch 真实集群健康、认证/TLS、持久化验证
- [ ] Milvus 真实集群健康、etcd/对象存储持久化验证
- [ ] 10K benchmark 通过
- [ ] 100K benchmark 通过
- [ ] Recall@10 >= 0.80
- [ ] NDCG@10 >= 0.75
- [ ] ACL leakage = 0
- [ ] Active release integrityState = VERIFIED
- [ ] ES/Milvus count drift = 0
- [ ] sourceHighWatermark = sourceEndWatermark
- [ ] Outbox RETRY/DEAD/replay/ordering 故障演练通过
- [ ] Alias activate/rollback 持续查询失败数 = 0
- [ ] 备份恢复演练满足 RPO/RTO
- [ ] Prometheus 告警验证
- [ ] 运维 RBAC / Audit 验证
- [ ] 内网明文 HTTP 下控制台冒烟（新建主体、复制凭据等路径不依赖安全上下文 API）
- [ ] 回滚负责人、窗口、runbook 已确认

**任何一项未完成：NO-GO。**
