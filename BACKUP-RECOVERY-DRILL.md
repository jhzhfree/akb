# Backup / Recovery Drill

演练必须在隔离环境执行并记录开始时间、RPO、RTO、备份版本、恢复点和负责人。

1. MySQL：验证可恢复到指定时间点；核对知识对象、Outbox、Schema Release 表。
2. Elasticsearch：验证 snapshot repository 与 restore；若不恢复，则从 MySQL authoritative snapshot 重建。
3. Milvus：验证 etcd/对象存储备份策略；若不恢复，则从 MySQL authoritative snapshot 重新 embedding/upsert。
4. 重建后执行 `release-consistency-live.py`。
5. 执行 `release-acl-live.py`，防止恢复后 ACL 语义丢失。
6. 执行检索 smoke 和质量集。
7. 记录实际 RPO/RTO。超过目标即 NO-GO。

禁止把“容器能重新启动”当成备份恢复成功。
