# 常见改动 Playbook

## A. 新增一个 Backend API

1. 定义 DTO；
2. Controller 只做 HTTP 参数；
3. 逻辑进入 focused service；
4. 数据落 Mapper；
5. 加 error code；
6. 更新 OpenAPI/`examples.http`；
7. 加 contract test；
8. 更新研发文档。

## B. 新增一种知识对象

同时检查：数据库、domain、extractor、governance、evidence、indexer、Elasticsearch、Milvus、graph、router、trace、frontend、tests。

## C. 改索引 Mapping/Embedding 维度

禁止原地覆盖生产 index。步骤：

```text
new schemaVersion
→ rebuild green
→ checksum/count/high-watermark validation
→ READY
→ activate alias
→ observe SLO
→ keep old release for rollback
```

## D. 修改 Outbox

任何 Outbox 变更都必须回答：
- 会不会重复？
- 会不会乱序？
- 会不会永久阻塞？
- replay 是否安全？
- skip 的业务后果是什么？
- payload 是否过大？

## E. 修改 Retrieval

必须保证：
- ACL 仍在 Top-K 前；
- status 过滤未丢；
- Candidate shape 不变；
- provider 写入 trace；
- fallback 行为有测试；
- 对 Recall@K/NDCG 的影响可评估。

## F. 修改前端

当前 V1.1 不引入额外状态管理库。若页面继续增长，优先拆 views/components，而不是继续增加 App.vue 复杂度。
