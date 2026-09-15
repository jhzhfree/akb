# Production Capacity Baseline

容量模型必须记录：知识对象数、平均正文长度、embedding 维度、向量字节数、ES primary/replica、Milvus index 类型、并发查询、写入速率、P50/P95/P99、CPU/RAM/磁盘、JVM heap、Milvus segment/index build 时间。

推荐资格阶梯：10K smoke -> 100K release baseline -> 500K capacity planning -> 1M scale qualification。每一级只有在 P95、Recall/NDCG、错误率、资源水位同时合格后才能外推下一档。生产容量建议保留至少 30% CPU/RAM headroom，并以真实峰值并发而不是单线程结果定容。
