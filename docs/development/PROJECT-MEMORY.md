# 工程记忆

本文记录会影响后续开发、构建和验证的工程约定。新增约定时同步更新本文，避免只留在聊天记录中。

## 数据库迁移验证必须使用 Docker

- 后端数据库为 MySQL 8；Flyway 迁移由应用启动时执行。
- `DatabaseMigrationIT` 使用 Testcontainers 启动 `mysql:8.0.36`，所以本地或 CI 必须安装并启动 Docker Engine（或兼容的 Docker API），且当前用户可访问 Docker daemon。
- 只运行 `mvn test` 不会执行 `*IT` 集成测试，不能据此宣称数据库迁移已通过。
- 修改 `backend/src/main/resources/db/migration/` 后，需在 Docker 可用的环境执行：

  ```bash
  docker info
  cd backend
  mvn -Pintegration-test -Dit.test=DatabaseMigrationIT verify
  ```

- 新迁移涉及的表、列和索引应在 `DatabaseMigrationIT` 中增加断言。集成测试失败时先保留失败信息，不得跳过迁移验证并标记为已通过。
- 当前开发容器若没有 Docker daemon，允许先运行 `mvn test`、前端检查及静态迁移审查；交付记录必须明确说明 Testcontainers 未执行，并将迁移验证留给 Docker 可用的 CI/开发机。
- 运行本地迁移集成测试前可先用 `docker pull mysql:8.0.36` 预取镜像；测试需能访问 Docker daemon，并允许 Testcontainers 启动临时 MySQL。

## Java 与提交约定

- 后端代码维持 Java 8 语言兼容；开发环境可使用更高版本 JDK。
- GitHub 工程提交使用已连接的 GitHub 账号/连接方式，目标仓库 `jhzhfree/akb`、分支 `feature/octop-mcp-integration`。
- `backend/demo-seed/mysql/.knowledge_os.sql.4dryTx` 与 `deploy/seed/enterprise-kb-demo/.knowledge_data.sql.IEyso8` 是本地未跟踪演示数据，保留但不得纳入提交。
