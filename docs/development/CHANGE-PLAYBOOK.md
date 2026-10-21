# 常见改动 Playbook

> 下面每一类改动**落地之后**还要走发布流程（改了什么就重建哪个镜像、数据库迁移怎么生效、
> 镜像怎么推到 192.168.22.6:5000、目标机怎么拉取与回滚），统一见
> [开发与部署方案](../operations/BUILD-AND-DEPLOY.md)。本文只讲代码怎么改。

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

## G. 修改数据库结构

结构变更走 Flyway，**不要手工去目标机执行 SQL**，也不要直接改已有的迁移文件。

1. 查当前最大版本号，新文件取"最大 +1"（重复版本号会让 backend 启动失败）：

   ```bash
   ls backend/src/main/resources/db/migration | sort -V | tail -3
   ```

2. 新增 `backend/src/main/resources/db/migration/V{版本}__{英文描述}.sql`，只写增量：

   ```sql
   ALTER TABLE t_example ADD COLUMN example_col VARCHAR(64) NULL;
   ```

3. 本地起服验证（backend 启动时自动应用）：

   ```bash
   cd backend && mvn -o spring-boot:run
   mysql -h127.0.0.1 -u<用户> -p<密码> knowledge_os -e "SHOW COLUMNS FROM t_example LIKE 'example_col'"
   ```

4. 实体（`domain/`）、Mapper XML、DTO、前端类型一起改——只加列不改代码等于白加。

5. 发版前备份 MySQL；迁移失败会直接让 backend 起不来，看 `docker compose logs backend` 修 SQL
   后重新发镜像，不要手工改表绕过。

> 改过迁移文件名后记得清 `backend/target/classes/db/migration/` 下的残留，否则报"版本号重复"。
> 详见 [开发与部署方案 · 第三节](../operations/BUILD-AND-DEPLOY.md)。
