# 开发与部署方案（改代码 → 上线）

本文是**从一次代码改动到目标机跑起来**的完整主线，回答六个问题：

1. 改了哪部分，需要重新构建什么？
2. 数据库结构变了，怎么更新？
3. 镜像怎么制作？
4. 怎么推送到镜像中心？
5. 目标机怎么下载镜像？
6. 怎么部署到一台 Linux 设备上？

日常部署的细项（系统参数、`.env` 全量说明、验收清单、备份恢复）见
[Docker 部署手册](DEPLOYMENT-DOCKER.md) 与 [裸金属部署手册](DEPLOYMENT-BAREMETAL.md)；
本文只讲"改动怎么变成运行中的服务"。

---

## 一、改了哪部分，就要重新做什么

三个应用各自独立成镜像，**改一个不必全量重建**——这是最省时间的一条规则：

| 改动位置 | 需要重建 | 典型耗时 | 重建命令 |
|---|---|---|---|
| `ai-service/**`（提示词、检索编排、模型参数） | `ai-service` | 1~3 分钟 | `./push_22.6.sh ai-service` |
| `backend/**`（接口、鉴权、检索、审计） | `backend` | 3~8 分钟（含 Maven 编译） | `./push_22.6.sh backend` |
| `frontend/**`（页面、嵌入挂件） | `frontend` | 1~3 分钟 | `./push_22.6.sh frontend` |
| `backend/src/main/resources/db/migration/V*.sql`（新增） | `backend` + **目标机数据库** | 见第三节 | 先迁移，再 `./push_22.6.sh backend` |
| `deploy/**`、`docker-compose*.yml`、`monitoring/**` | 无需重建镜像 | — | 目标机更新文件后 `docker compose up -d` |
| `docs/**` | 无需重建 | — | 提交即可 |
| 中间件版本（MySQL / ES / Milvus …） | 无需重建应用镜像 | — | 改 compose 里的镜像 tag，目标机 `pull && up -d` |

多个服务一起改就一起给：`./push_22.6.sh backend frontend`。不写服务名默认三个全量重建。

> **判断小抄**：不确定改了什么，就 `git status --short`，看路径前缀落在上表哪一行。

---

## 二、改完先在本地过闸门

```bash
# 后端：编译 + 全量单测（107 个用例，约 1 分钟）
cd backend && mvn -o test

# 前端：类型检查 + 构建
cd frontend && npm run typecheck && npm run build

# ai-service / Python 与 Java / 前端的统一门禁
make lint
```

**没过闸门不要制作镜像**。镜像构建里后端是 `-DskipTests`、前端只跑 `npm run build`，
测试只在本地这一道；推上去的镜像不会再有人替你跑测试。

---

## 三、数据库更新（Flyway 迁移）

数据库结构**不需要手工执行 SQL**，也不需要进容器改表：

- 迁移脚本放在 `backend/src/main/resources/db/migration/`，命名 `V{版本号}__{英文描述}.sql`；
- backend **每次启动**自动执行未应用过的迁移（`spring.flyway.enabled` 默认 `true`）；
- 已执行过的记录在 MySQL 的 `flyway_schema_history` 表里，同一版本**不会重复执行**。

### 3.1 新增一个迁移

```bash
# 1) 先查当前最大版本号——版本号不是"下一个整数"，而是"当前最大 +1"
ls backend/src/main/resources/db/migration | sort -V | tail -3
```

```sql
-- 例：backend/src/main/resources/db/migration/V60__xxx.sql
ALTER TABLE t_example ADD COLUMN example_col VARCHAR(64) NULL;
```

**版本号写错会直接让 backend 起不来**：Flyway 在 `validate-on-migrate=true` 下发现两个文件版本号相同，
报 `Found more than one migration with version N`，启动即失败（不是警告）。所以这一步只看目录，
不要凭印象。

### 3.2 本地验证迁移真的生效

```bash
# 起后端（会自动跑迁移）
cd backend && mvn -o spring-boot:run

# 看迁移是否被应用
mysql -h127.0.0.1 -u<用户> -p<密码> knowledge_os \
  -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5"

# 看列真的加上了
mysql -h127.0.0.1 -u<用户> -p<密码> knowledge_os -e "SHOW COLUMNS FROM t_example LIKE 'example_col'"
```

最近两个迁移可以照抄：`V58__agent_memory_allow_user_owned.sql`（把列改为可空）、
`V59__api_call_audit_caller_ip.sql`（加 `caller_ip` 列）。

### 3.3 生产/目标机注意事项

- **先备份 MySQL 再发版**，尤其是 `ALTER TABLE` 类迁移：
  ```bash
  docker compose exec mysql mysqldump -u<用户> -p<密码> knowledge_os > backup-$(date +%F).sql
  ```
- 迁移失败 = backend 启动失败（这是故意的，避免带着半个结构跑）。用 `docker compose logs backend`
  能看到 `Migration ... failed`，修好 SQL 重新发镜像即可，**不要手工去改表绕过**——那样
  `flyway_schema_history` 与真实结构会不一致，下次校验照样过不去。
- 迁移一旦执行过就**不要修改该文件内容**（校验和不匹配会启动失败）。要改就新加一个更高版本的迁移。
- 需要临时关掉自动迁移（例如由 DBA 手工执行）：`.env` 里设 `FLYWAY_ENABLED=false`，但这是例外情况，
  用后要改回来。

### 3.4 一个容易踩的坑

改过迁移文件名（比如把 `V50__a.sql` 改成 `V58__a.sql`）之后，`backend/target/classes/db/migration/`
会残留旧文件名，启动时报"版本号重复"。处理：

```bash
rm -f backend/target/classes/db/migration/V50__a.sql   # 或整个 mvn clean
```

---

## 四、制作镜像

镜像由各自目录下的 `Dockerfile` 构建（backend：Maven 多阶段；frontend：Node 构建 + nginx；
ai-service：Python slim），统一由根 `docker-compose.yml` 编排构建：

```bash
# 单个服务
docker compose -f docker-compose.yml build backend

# 三个应用一起（等价于 make release-build）
docker compose -f docker-compose.yml build ai-service backend frontend
```

**版本号规则**：镜像 tag 取 `VERSION`（默认 `1.1.0`），产物为
`knowledge-os/{ai-service,backend,frontend}:{VERSION}`。

- 日常修复**不换版本号**——同名 tag 被新镜像覆盖，目标机不用改 compose 文件（覆盖式发布）。
- 只有对外正式发版、需要保留可回滚的独立版本时，才显式指定：`VERSION=1.2.0 ./push_22.6.sh`。

先本地验证、不想推远端：`./push_22.6.sh --no-push`（只构建 + 打本地 tag）。

---

## 五、推送到镜像中心（192.168.22.6:5000）

```bash
# 三个服务全量构建 + 推送
./push_22.6.sh

# 只推改动的服务（推荐，省几分钟）
./push_22.6.sh frontend

# 先看看会执行什么，不真跑
./push_22.6.sh --dry-run

# 只构建打 tag，不推送（本地验证用）
./push_22.6.sh --no-push
```

等价的 Make 目标：`make push-images s=frontend`。

脚本（`scripts/release-push.sh`）做了四件值得知道的事：

1. **覆盖前备份旧镜像**：把 registry 上现存的 `{VERSION}` 另存为 `{VERSION}-prev`。
   因为 tag 被覆盖后上一版在仓库里就找不到了——回滚靠这个备份。
   （首次发布没有旧镜像时会跳过；`--no-backup` 可关，但不建议。）
2. **digest 校验**：push 回执里的 `sha256` 与 registry 上该 tag 的 digest 比对，不一致即判失败，
   避免"看起来推完了其实没传完"。
3. **失败即停**：任一服务构建失败不再推后面的，避免只更新了一半。
4. **推送完会提示目标机还要做什么**（见第六节）——这是覆盖式发布最容易漏的一步。

> 推送前需要 `docker login 192.168.22.6:5000`（若仓库要求认证）。仓库是 HTTP，
> 目标机与推送机都要把 `192.168.22.6:5000` 配进 `/etc/docker/daemon.json` 的 `insecure-registries`。

---

## 六、目标机：下载镜像与部署

下面按**一台全新的 Linux（Ubuntu 24.04）**写完整流程；已有环境的升级见 6.3。

### 6.1 准备主机（全新机做一次）

```bash
# Docker Engine + compose 插件（详细见 DEPLOYMENT-DOCKER.md 第二节）
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"   # 重新登录后生效，免 sudo

# 私有仓库是 HTTP，必须声明为不安全仓库，否则 pull 报 "server gave HTTP response to HTTPS client"
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{ "insecure-registries": ["192.168.22.6:5000"] }
EOF
sudo systemctl restart docker
```

### 6.2 方式 A：从镜像中心拉取（有内网，最常用）

```bash
# 1) 取编排与配置（只取文件，不含代码）
mkdir -p /opt/kos && cd /opt/kos
#   从仓库/制品库拿 docker-compose.yml、.env.production.example、monitoring/

# 2) 生成并修改 .env
cp .env.production.example .env
vi .env          # 必须改掉所有 CHANGE_ME，至少确认 LLM_BASE_URL、各中间件密码

# 3) 拉镜像（tag 与推送时一致，默认 1.1.0）
VERSION=1.1.0 REG=192.168.22.6:5000
docker pull $REG/knowledge-os/ai-service:$VERSION
docker pull $REG/knowledge-os/backend:$VERSION
docker pull $REG/knowledge-os/frontend:$VERSION

# 4) 启动（首次含 MySQL 初始化 + Flyway 迁移 + ES/Milvus 起服，约 3~8 分钟）
docker compose --env-file .env up -d

# 5) 等到全部 healthy 再验收
docker compose ps
curl -fsS http://127.0.0.1:12000/health     # ai-service
curl -fsS http://127.0.0.1:8080/actuator/health   # backend
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8088/   # frontend

# 6) 取初始管理员密码
docker compose logs backend 2>&1 | grep -A 2 "初始密码"
```

浏览器打开 `http://<主机IP>:8088`。

### 6.3 方式 B：已有环境升级（tag 没变也要显式 pull）

```bash
cd /opt/kos
docker compose pull backend frontend      # 只拉变更的服务
docker compose up -d backend frontend     # 只替换变更容器，数据卷保持
docker compose ps                         # 确认 healthy
```

> **这一步不能省**：覆盖式发布的 tag 没变，Docker **不会**自动去拉新镜像，
> 直接 `up -d` 会继续用本地旧镜像——表现为"明明推了，页面还是老样子"。

### 6.4 方式 C：离线产品包（目标机无内网）

在**构建机**上：

```bash
make package                              # 产物 dist/ai-knowledge-<VERSION>.tar.gz
# 只打应用镜像、中间件在线拉：INCLUDE_MIDDLEWARE=0 make package
```

拷贝到目标机后：

```bash
tar -xzf ai-knowledge-1.1.0.tar.gz && cd ai-knowledge-1.1.0
./install.sh      # 预检环境 -> docker load 加载离线镜像 -> 生成 .env
vi .env           # 改掉 CHANGE_ME
./start.sh        # 启动
./status.sh       # 看状态；./logs.sh 看日志；./stop.sh 停止；./uninstall.sh 卸载
```

### 6.5 回滚

```bash
# 镜像回滚：把备份的 -prev 重新打成当前 tag 推回，目标机 pull + up -d
REG=192.168.22.6:5000 VERSION=1.1.0
docker pull $REG/knowledge-os/backend:$VERSION-prev
docker tag  $REG/knowledge-os/backend:$VERSION-prev $REG/knowledge-os/backend:$VERSION
docker push $REG/knowledge-os/backend:$VERSION

# 数据库回滚：Flyway 不自动回滚 DDL，只能从发版前那份 mysqldump 恢复
#             → 所以第三节强调"先备份"
```

---

## 七、发布检查清单

- [ ] 本地 `mvn -o test`、`npm run typecheck && npm run build` 通过
- [ ] 若含数据库改动：迁移文件版本号是"当前最大 +1"（`ls ... | sort -V | tail -1` 确认过）
- [ ] 本机起服验证过迁移生效（查 `flyway_schema_history` 与目标列）
- [ ] 发版前备份了 MySQL
- [ ] `./push_22.6.sh <改动的服务>` 推送完成，输出里 digest 一致
- [ ] 目标机执行了 `docker compose pull` 再 `up -d`（tag 没变也必须 pull）
- [ ] `docker compose ps` 全部 healthy，三个健康端点通过
- [ ] 关键业务链路手动走一遍（登录 → 问答 → 看引用）

---

## 八、常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 推了新镜像，目标机还是旧的 | 覆盖式发布 tag 没变，本地有旧镜像不会自动拉 | `docker compose pull <服务>` 后再 `up -d` |
| `docker pull` 报 `server gave HTTP response to HTTPS client` | 私有仓库是 HTTP | `daemon.json` 加 `insecure-registries`，重启 docker |
| backend 起来就退出，日志 `Found more than one migration with version N` | 两个迁移文件版本号相同（常因改过文件名，`target/classes` 有残留） | 删 `backend/target/classes/db/migration/` 下的旧副本或 `mvn clean` |
| backend 起不来，日志 `Migration ... failed` | 迁移 SQL 有错 | 看日志里的具体 SQL 错误，改迁移文件重新发镜像；不要手工改表绕过 |
| 页面 404 但容器 healthy | 前端是静态资源，浏览器缓存 | 强制刷新（Ctrl+F5）；确认 `8088` 端口映射 |
| 想只更新配置不重建镜像 | 配置走 `.env` | 改 `.env` 后 `docker compose up -d` 即可，不必重新构建 |
