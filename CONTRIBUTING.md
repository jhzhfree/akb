# CONTRIBUTING

## 分支与提交

- 功能分支：`feature/<topic>`
- 修复分支：`fix/<topic>`
- 提交信息建议遵循：`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`
- 一个提交尽量只解决一个可说明的问题。

## 开发前

先阅读：

- `docs/development/DEVELOPER-GUIDE.md`
- `docs/development/CODE-STANDARDS.md`
- `docs/development/ARCHITECTURE-CODE-WALKTHROUGH.md`

## 强制边界

1. Java 代码保持 Java 8 兼容。
2. MySQL 是权威状态，检索索引是可重建视图。
3. 外部 AI/搜索/图调用不得长期占用数据库事务。
4. 正式知识状态变化通过 Outbox 同步索引。
5. ACL/status 在检索 Top-K 之前过滤。
6. 修改既有 Flyway migration 是禁止的；新增 migration。
7. 新公开 Java 类型写类级 Javadoc；Python 公共模块/符号写 docstring。
8. 代码注释优先解释设计原因、失败策略和一致性约束。
9. 前端不得直接取用仅「安全上下文」可用的 API（`crypto.randomUUID`、`crypto.subtle`、
   `navigator.clipboard`、WebAuthn、File System Access 等）。内网部署常以明文 HTTP 打开
   控制台，那里这些 API 根本不存在，直接取用是运行时才炸的坑。用封装
   （`frontend/src/utils/uid.ts`、`utils/clipboard.ts`）或先探测再降级；由
   `scripts/check-frontend-secure-context.py` 把关。

## 提交前

```bash
./scripts/check-v11.sh
```

有 Maven/Docker/Node 依赖的开发机额外执行：

```bash
cd backend && mvn test && mvn -Pintegration-test verify
cd ../frontend && npm ci && npm run build
```

如果修改了 Elasticsearch/Milvus/Outbox/Schema Release，必须补对应 contract/regression test。
