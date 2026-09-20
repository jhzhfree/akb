# Docker 部署手册（Ubuntu 24.04.1 LTS）

> 面向一台**全新安装的 Ubuntu 24.04.1 LTS（noble）裸机**，用 Docker Compose 拉起 AI Knowledge OS 全栈。
> 文档中所有命令均以 root 或有 sudo 权限的账号执行，若用普通账号请自行加 `sudo`。
>
> 组件清单、端口、镜像版本均取自本仓库现有编排文件（`docker-compose.yml`、`docker-compose.dev.yml`、`deploy/docker-compose.release.yml`），保持一致。

---

## 一、部署目标与组件总览

### 1.1 组件清单

| 角色 | 容器名/服务名 | 镜像 | 宿主端口 | 必需 |
| --- | --- | --- | --- | --- |
| 数据库（权威状态） | `mysql` | `mysql:8.0.36` | 3306 | 是 |
| 缓存/会话 | `redis` | `redis:7-alpine` | 6379 | 是 |
| 全文检索 | `elasticsearch` | `docker.elastic.co/elasticsearch/elasticsearch:9.5.3` | 9200 / 9600 | 否（可降级） |
| 向量检索 | `milvus` | `milvusdb/milvus:v3.0.1` | 19530 / 9091 | 否（可降级） |
| Milvus 元信息 | `milvus-etcd` | `quay.io/coreos/etcd:v3.5.18` | — | 随 Milvus |
| Milvus 对象存储 | `milvus-minio` | `minio/minio:RELEASE.2025-07-23T15-54-02Z` | — | 随 Milvus |
| 知识图谱 | `neo4j` | `neo4j:5.26` | 7474 / 7687 | 否（自动降级 Null） |
| AI 服务 | `ai-service` | 本地构建 `knowledge-os/ai-service` | 12000 | 是 |
| Java 后端 | `backend` | 本地构建 `knowledge-os/backend` | 8080 | 是 |
| 索引工作节点 | `index-worker` | 同 `backend`（仅发布编排） | 8081 | 否（生产建议） |
| Web 前端 | `frontend` | 本地构建 `knowledge-os/frontend` | 8088 | 是 |
| 监控采集 | `prometheus` | `prom/prometheus:v3.5.0` | 9090（dev 编排 9092） | 否 |
| 监控面板 | `grafana` | `grafana/grafana:12.1.1` | 3000（dev 编排 3001） | 否 |

**三条编排路径，按需三选一：**

| 场景 | 编排文件 | 特点 |
| --- | --- | --- |
| 快速体验 / 功能演示 | `docker-compose.yml` | 一条命令全栈拉起（含监控），资源占用最高 |
| 开发 / 联调 | `docker-compose.dev.yml` | 基础中间件默认起，应用与监控按 `--profile` 开 |
| 生产 / 离线交付 | `make package` → 目标机 `install.sh` | 二进制交付包（无源码），含 `restart: unless-stopped`、健康检查、外网 optional |
| 内网镜像仓库 | 任意编排 + `--no-build` | 从 `192.168.22.6:5000` 私有仓库拉取全部镜像，免本地构建、不依赖外网（见第四节方式 C、6.4） |

### 1.2 主机规格参考

| 规模 | CPU | 内存 | 磁盘 | 说明 |
| --- | --- | --- | --- | --- |
| 最小可用 | 4 核 | 16 GB | 100 GB SSD | 需把 ES 堆降到 `-Xms1g -Xmx1g`，建议关闭 Milvus |
| 推荐 | 8 核 | 32 GB | 200 GB SSD | 全组件默认堆（ES `-Xms2g -Xmx2g`） |
| 生产 | 16 核 | 64 GB | 500 GB SSD | 数据卷独立挂载盘 |

> ES 9.x 单节点 + Milvus standalone + Neo4j 三者常驻内存约 10~12 GB，再加两个 JVM 应用与前端，16 GB 是底线。

### 1.3 外部依赖：大模型服务

本工程不内置 LLM，需要外部 OpenAI 兼容 / Ollama 服务（可部署在同一台主机，用 `host.docker.internal:11434` 访问，编排里已加 `extra_hosts`）。

若不接模型，`LLM_PROVIDER=mock` + `EMBEDDING_PROVIDER=hash` 也能把系统跑起来，可用于链路验收，但答案质量不可用。

---

## 二、安装 Docker Engine 与 Compose 插件

### 2.1 卸载冲突包（新机可跳过，多装几次也不吃亏）

```bash
sudo apt-get remove -y docker docker-engine docker.io containerd runc docker-compose 2>/dev/null
sudo apt-get purge -y docker-ce docker-ce-cli containerd.io docker-compose-plugin 2>/dev/null
sudo rm -rf /var/lib/docker /var/lib/containerd
```

### 2.2 安装依赖与官方源

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg lsb-release

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu noble stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

校验：

```bash
docker version
docker compose version      # 需要 v2.x，出现 "Docker Compose version v2..." 即为插件形态
```

### 2.3 配置开机自启与免 sudo

```bash
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
newgrp docker               # 或退出重登录
```

### 2.4 （可选）镜像加速

国内环境下拉不到官方镜像时配置 Registry Mirror，注意**镜像源可用性随时变化**，请确认后使用：

```bash
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "100m", "max-file": "5" },
  "registry-mirrors": ["https://<你的镜像加速地址>"]
}
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
```

> 仓库里的中间件镜像来自 Docker Hub 与 `docker.elastic.co`、`quay.io`；离线交付场景请用第三节的打包方式，不要依赖现场拉取。
> 若目标机可访问内网镜像仓库 `192.168.22.6:5000`，直接走第四节的方式 C 更省事（无需打包、无需外网）。

---

## 三、操作系统参数

Elasticsearch 与 Milvus 要求较高的内存映射上限，不设置会直接启动失败（ES 报 `max virtual memory areas vm.max_map_count [65530] is too low`）：

```bash
sudo tee /etc/sysctl.d/99-knowledge-os.conf > /dev/null <<'EOF'
vm.max_map_count=262144
vm.swappiness=1
fs.file-max=200000
net.core.somaxconn=65535
EOF
sudo sysctl --system
sysctl vm.max_map_count        # 应输出 262144
```

进程句柄（当前会话即时生效 + 持久化）：

```bash
sudo tee /etc/security/limits.d/99-knowledge-os.conf > /dev/null <<'EOF'
* soft nofile 65536
* hard nofile 65536
* soft nproc  65536
* hard nproc  65536
root soft nofile 65536
root hard nofile 65536
EOF
ulimit -n 65536
```

防火墙：仅放通需要对外暴露的端口。

```bash
sudo ufw allow 22/tcp
sudo ufw allow 8088/tcp        # 前端
sudo ufw allow 8080/tcp        # 后端 Swagger / API（生产建议只放前端）
sudo ufw allow 3000/tcp        # Grafana（可选）
sudo ufw enable
```

> MySQL 3306、Redis 6379、ES 9200、Neo4j 7474/7687、Milvus 19530 属于内网端口，**不要对外放通**。

---

## 四、获取部署资产

### 方式 A：在线获取源码（有构建能力）

```bash
sudo mkdir -p /opt && cd /opt
sudo git clone -b v1.4 <你的仓库地址> ai-knowledge-os
sudo chown -R "$USER":"$USER" /opt/ai-knowledge-os
cd /opt/ai-knowledge-os
```

### 方式 B：离线产品包（推荐给客户现场）

在**有网络、有 Docker 的打包机**上生成安装包：

```bash
cd /opt/ai-knowledge-os
make package VERSION=1.1.0 IMAGE_PREFIX=knowledge-os INCLUDE_MIDDLEWARE=1
# 产物：dist/ai-knowledge-1.1.0.tar.gz
```

拷贝到目标机后解压：

```bash
tar -xzf ai-knowledge-1.1.0.tar.gz
cd ai-knowledge-1.1.0
./install.sh          # 预检 Docker → docker load 离线镜像 → 生成 .env
```

`install.sh` 结束后会提示 `.env` 中残留的 `CHANGE_ME` 占位值，**必须先改掉再启动**。

> 镜像标签由 `Makefile` / `release.env` 的 `VERSION` 控制（默认 `1.1.0`）。若希望与当前代码分支对齐，打包时显式指定：
> `VERSION=1.4.0 IMAGE_PREFIX=registry.example.com/knowledge-os make package`

### 方式 C：从内网镜像仓库拉取（192.168.22.6:5000）

内网已同步一份完整的 `knowledge-os/*` 镜像（应用 + 中间件），适合**目标机无外网**、又不想在本地编译 Maven / npm / pip 的场景：只取编排与配置文件，镜像全部从仓库拉取。

#### C.1 确认仓库连通与镜像清单

```bash
REG=192.168.22.6:5000
curl -s http://$REG/v2/_catalog | python3 -m json.tool
```

```json
{
    "repositories": [
        "atlassian/confluence-server",
        "confluence",
        "gitlab/gitlab-ce",
        "jira",
        "jira_ini",
        "knowledge-os/ai-service",
        "knowledge-os/backend",
        "knowledge-os/elasticsearch",
        "knowledge-os/etcd",
        "knowledge-os/frontend",
        "knowledge-os/grafana",
        "knowledge-os/milvus",
        "knowledge-os/minio",
        "knowledge-os/mysql",
        "knowledge-os/neo4j",
        "knowledge-os/prometheus",
        "knowledge-os/redis",
        "mysql"
    ]
}
```

查看某个镜像的可用标签（应用镜像按 `VERSION` 打标，中间件沿用官方版本号）：

```bash
curl -s http://$REG/v2/knowledge-os/backend/tags/list | python3 -m json.tool
# { "name": "knowledge-os/backend", "tags": ["1.1.0"] }
```

本工程用到的 12 个镜像与标签：

| 服务 | 仓库内镜像 | 标签 | 本地目标镜像（编排里写的名字） |
| --- | --- | --- | --- |
| ai-service | `knowledge-os/ai-service` | `1.1.0` | `knowledge-os/ai-service:1.1.0` |
| backend / index-worker | `knowledge-os/backend` | `1.1.0` | `knowledge-os/backend:1.1.0` |
| frontend | `knowledge-os/frontend` | `1.1.0` | `knowledge-os/frontend:1.1.0` |
| mysql | `knowledge-os/mysql` | `8.0.36` | `mysql:8.0.36` |
| redis | `knowledge-os/redis` | `7-alpine` | `redis:7-alpine` |
| elasticsearch | `knowledge-os/elasticsearch` | `9.5.3` | `docker.elastic.co/elasticsearch/elasticsearch:9.5.3` |
| milvus | `knowledge-os/milvus` | `v3.0.1` | `milvusdb/milvus:v3.0.1` |
| milvus-etcd | `knowledge-os/etcd` | `v3.5.18` | `quay.io/coreos/etcd:v3.5.18` |
| milvus-minio | `knowledge-os/minio` | `RELEASE.2025-07-23T15-54-02Z` | `minio/minio:RELEASE.2025-07-23T15-54-02Z` |
| neo4j | `knowledge-os/neo4j` | `5.26` | `neo4j:5.26` |
| prometheus | `knowledge-os/prometheus` | `v3.5.0` | `prom/prometheus:v3.5.0` |
| grafana | `knowledge-os/grafana` | `12.1.1` | `grafana/grafana:12.1.1` |

#### C.2 允许 HTTP 私有仓库

该仓库是 HTTP（非 TLS），Docker 默认拒绝，必须登记为 `insecure-registries`。**注意与已有 `daemon.json` 合并**，不要覆盖掉日志与加速配置：

```bash
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "100m", "max-file": "5" },
  "insecure-registries": ["192.168.22.6:5000"]
}
EOF
sudo systemctl daemon-reload
sudo systemctl restart docker
docker info | grep -A3 "Insecure Registries"    # 应能看到 192.168.22.6:5000
```

#### C.3 拉取并改名

编排里写的是官方镜像名，因此拉下来后要 `docker tag` 回官方名，Compose 才能直接命中本地镜像：

```bash
cat > pull-mirror.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
REG=192.168.22.6:5000
VER=${VERSION:-1.1.0}
# 每行格式：仓库内 名称:标签 | 本地目标镜像
MAPPINGS="
ai-service:$VER|knowledge-os/ai-service:$VER
backend:$VER|knowledge-os/backend:$VER
frontend:$VER|knowledge-os/frontend:$VER
mysql:8.0.36|mysql:8.0.36
redis:7-alpine|redis:7-alpine
elasticsearch:9.5.3|docker.elastic.co/elasticsearch/elasticsearch:9.5.3
etcd:v3.5.18|quay.io/coreos/etcd:v3.5.18
minio:RELEASE.2025-07-23T15-54-02Z|minio/minio:RELEASE.2025-07-23T15-54-02Z
milvus:v3.0.1|milvusdb/milvus:v3.0.1
neo4j:5.26|neo4j:5.26
prometheus:v3.5.0|prom/prometheus:v3.5.0
grafana:12.1.1|grafana/grafana:12.1.1
"
for item in $MAPPINGS; do
  src="${item%%|*}"; dst="${item##*|}"
  docker pull "$REG/knowledge-os/$src"
  docker tag  "$REG/knowledge-os/$src" "$dst"
  echo "OK  $dst"
done
EOF
bash pull-mirror.sh
```

核对结果：

```bash
docker images --format '{{.Repository}}:{{.Tag}}' | grep -E 'knowledge-os|mysql:|redis:|neo4j:|milvus|minio|etcd|prom/prometheus|grafana'
```

> 只想拉应用镜像（中间件走外网）时，把 `MAPPINGS` 里 mysql 及以下行删掉即可。

#### C.4 取编排与配置

镜像不需要源码，但编排与配置仍要有：

```bash
sudo mkdir -p /opt && cd /opt
sudo git clone -b v1.4 <你的仓库地址> ai-knowledge-os      # 同方式 A
cd /opt/ai-knowledge-os
cp .env.example .env                                      # 按第五节改掉必改项
```

无 git 时也可只拷 `docker-compose.yml`（或 `deploy/docker-compose.release.yml`）、`.env.example`、`monitoring/prometheus.yml`、`nginx.conf` 等。

---

## 五、配置 `.env`

仓库根目录提供 `.env.example`（通用）与 `.env.production.example`（生产）。首次启用：

```bash
cd /opt/ai-knowledge-os
cp .env.example .env
```

### 5.1 必改项清单

| 变量 | 示例值 | 说明 |
| --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | `Str0ng_Root_Pwd` | 容器 root 密码，`.env.production.example` 中为 `CHANGE_ME_ROOT` |
| `MYSQL_PASSWORD` / `DB_PASSWORD` | `Str0ng_Db_Pwd` | **两处必须一致**，后端用 `DB_PASSWORD` 连库 |
| `MYSQL_USER` / `DB_USERNAME` | `knowledge` | 同上，两处一致 |
| `NEO4J_PASSWORD` | `Str0ng_Neo4j_Pwd` | 与编排里 `NEO4J_AUTH=neo4j/${NEO4J_PASSWORD}` 联动 |
| `ELASTICSEARCH_INITIAL_ADMIN_PASSWORD` | `Str0ng_Es_Pwd` | ES 9.x 初始 `elastic` 用户密码，**必须写在引用它的变量之前** |
| `ELASTICSEARCH_PASSWORD` | 同上 | 生产编排用 `${ELASTICSEARCH_INITIAL_ADMIN_PASSWORD}` 引用 |
| `AUTH_BOOTSTRAP_ADMIN_PASSWORD` | 强密码 | 控制台初始管理员密码；留空则由后端随机生成并打印在日志 |
| `OPS_VIEWER_TOKEN` / `OPS_OPERATOR_TOKEN` / `OPS_ADMIN_TOKEN` | 32 位随机串 | 索引运维 RBAC，`OPS_RBAC_ENABLED=true` 时必须改 |
| `LLM_BASE_URL` / `LLM_MODEL` | `http://host.docker.internal:11434` / `qwen2.5:7b` | 容器内访问宿主模型服务 |
| `EMBEDDING_PROVIDER` / `EMBEDDING_MODEL` / `EMBEDDING_DIM` | `ollama` / `nomic-embed-text` / `768` | **向量维度必须与模型一致**，否则 Milvus 建集合失败 |

生成随机值：

```bash
openssl rand -hex 16        # 用作 OPS_*_TOKEN
openssl rand -base64 18     # 用作各类密码
```

### 5.2 关键配置项说明

```ini
# --- 认证：生产务必保持开启 ---
AUTH_ENABLED=true
AUTH_ALLOW_ANONYMOUS=false
AUTH_SESSION_HOURS=12

# --- 检索编排 ---
RETRIEVAL_MODE=auto                 # auto：外部不可用自动降级到本地 BM25
RETRIEVAL_EXTERNAL_REQUIRED=false   # true 表示 ES/Milvus 不可用时直接报错，不降级
ELASTICSEARCH_ENABLED=true
MILVUS_ENABLED=true

# --- Outbox 索引：两种模式二选一 ---
# 单进程模式（根 docker-compose.yml）：backend 内自行消费队列
INDEX_OUTBOX_ENABLED=true
# 分离模式（发布编排）：backend=false + index-worker=true，避免两进程抢同一队列
```

注意：`INDEX_OUTBOX_ENABLED` 在同一时间**只能有一个进程为 true**，`deploy/docker-compose.release.yml` 已经按 `backend:false / index-worker:true` 配好，不要随意改。

### 5.3 数据目录

后端保存摄入原件（供资料页预览源文件）与 AI 服务写入索引数据必须指向同一份目录：

```ini
KNOWLEDGE_DATA_DIR=/app/data                    # 容器内 AI 服务数据目录
SOURCE_STORAGE_DIR=/app/data/source-files       # 后端读取原件目录
```

两者在容器里都落盘到命名卷 `ai_data`，升级容器不会丢。

---

## 六、启动系统

### 6.1 路径 A：全栈一键（`docker-compose.yml`）

```bash
cd /opt/ai-knowledge-os
make up            # 等价：docker compose up -d --build
make ps            # docker compose ps
```

`ai-service / backend / frontend` 三个镜像都在本机由源码构建（Maven、npm、pip 依赖较多），首次构建通常需要 10~20 分钟，视 CPU 与网络而定。

### 6.2 路径 B：开发 / 联调（`docker-compose.dev.yml`）

```bash
cd /opt/ai-knowledge-os
cp .env.dev.example .env.dev
make dev-up                                    # mysql + redis + neo4j
docker compose -f docker-compose.dev.yml --profile dual-retrieval up -d   # 追加 ES + Milvus
docker compose -f docker-compose.dev.yml --profile app up -d --build          # 追加 ai-service + backend + frontend
docker compose -f docker-compose.dev.yml --profile monitoring up -d          # Prometheus(9092) + Grafana(3001)

# 其他 Make 目标：dev-ps / dev-logs / dev-down / dev-clean（-v 清卷，危险）
```

> 上面 Make 目标与 profile、端口的对应关系见 `docs/ENVIRONMENT-V1.1.md`。

### 6.3 路径 C：离线产品包

```bash
cd ai-knowledge-1.1.0
vim .env                 # 改掉全部 CHANGE_ME
./start.sh
./status.sh
./logs.sh backend
```

产品包内已包含 `install.sh / start.sh / stop.sh / status.sh / logs.sh / uninstall.sh`，项目名固定为 `knowledge-os`，保证数据卷名稳定、升级不丢数据。

### 6.4 路径 D：内网镜像仓库（192.168.22.6:5000）

先按第四节方式 C 完成 insecure-registries 配置与镜像拉取，随后启动。**关键是不要带 `--build`**，否则 Compose 会用本地源码重新编译（Maven / npm 耗时且需要外网依赖）：

```bash
cd /opt/ai-knowledge-os

# 根编排：镜像已在本地改回官方名，--no-build 直接起
docker compose --env-file .env up -d --no-build

# 或发布编排（含 index-worker）：应用镜像支持 IMAGE_PREFIX，可不经改名直接拉
IMAGE_PREFIX=192.168.22.6:5000/knowledge-os VERSION=1.1.0 \
  docker compose -f deploy/docker-compose.release.yml --env-file .env up -d --no-build
```

> `IMAGE_PREFIX` 只作用于 `ai-service / backend / frontend` 三个应用镜像；发布编排里的中间件仍写官方名，需按方式 C 的 `docker tag` 改回本地标签（或由外网拉取）。

若某次必须重新构建（改了源码），回到 6.1 的 `make up`；内网仓库方式只负责"拿来即用"。

### 6.5 等待就绪

根编排端口与宿主一致，可直接用仓库自带脚本：

```bash
./scripts/wait-ready.sh
# ready: http://127.0.0.1:12000/health
# ready: http://127.0.0.1:8080/actuator/health
# ready: http://127.0.0.1:8088/
```

首次 MySQL 初始化 + Flyway 迁移 + ES/Milvus 启动通常需要 3~8 分钟，请耐心等待 `docker compose ps` 全部 `(healthy)` 再验收。

### 6.6 获取初始管理员密码

后端首次启动会通过 `AuthBootstrap` 创建管理员（`t_user` 为空时）：

- 若设置了 `AUTH_BOOTSTRAP_ADMIN_PASSWORD`：使用该密码登录。
- 若留空：随机生成 12 字节密码，**打印在 backend 启动日志的警告块里**。

```bash
docker compose logs backend 2>&1 | grep -A3 "已创建初始管理员账号"
# 已创建初始管理员账号：admin
# 初始密码：xxxxxxxxxxxx（随机生成，请首次登录后立即修改）
```

同时该管理员会被设为默认知识库（`t_knowledge_base.id=1`，`KB_DEFAULT`）的 Owner，保证有人能治理它。

> 只有首次（`countUsers()==0`）会执行。忘记密码只能改库或重置 `t_user`。

---

## 七、验收

### 7.1 容器健康

```bash
docker compose ps
# NAME                STATUS
# kos-backend         Up (healthy)
# kos-ai-service      Up (healthy)
# kos-mysql           Up (healthy)
# kos-elasticsearch   Up (healthy)
# kos-milvus          Up (healthy)
```

### 7.2 端点冒烟

```bash
curl -fsS http://127.0.0.1:12000/health && echo " ai-service OK"
curl -fsS http://127.0.0.1:8080/actuator/health && echo " backend OK"
curl -s -o /dev/null -w "frontend HTTP %{http_code}\n" http://127.0.0.1:8088/
curl -s -o /dev/null -w "es HTTP %{http_code}\n" http://127.0.0.1:9200/_cluster/health
```

### 7.3 业务链路（认证 + 知识库读取）

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<初始密码>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

curl -s http://127.0.0.1:8080/api/knowledge/bases -H "Authorization: Bearer $TOKEN" | head -c 300
```

### 7.4 自动化端到端

```bash
E2E_BACKEND_URL=http://127.0.0.1:8080 \
E2E_AUTH_USERNAME=admin E2E_AUTH_PASSWORD='<初始密码>' \
python3 scripts/e2e-test.py
```

该脚本会走完整链路：登录 → 建源 → 上传文档 → 摄入 → 检索问答 → 治理。

### 7.5 前端访问

浏览器打开 `http://<主机IP>:8088`，用上一步的管理员账号登录。

后端 Swagger：`http://<主机IP>:8080/swagger-ui.html`

---

## 八、运维

### 8.1 常用命令

```bash
make ps / docker compose ps
make logs / docker compose logs -f --tail=200 backend
docker compose restart backend
docker compose down              # 停止，保留数据卷
make clean                       # docker compose down -v --remove-orphans，仓库内的 -v 会删除数据卷
```

### 8.2 备份

数据卷真实路径：

```bash
docker volume ls
docker volume inspect knowledge-os_mysql_data
```

| 数据 | 备份方式 |
| --- | --- |
| MySQL（权威状态，最重要） | `docker compose exec mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --routines --events --single-transaction knowledge_os \| gzip > backup/knowledge_os_$(date +%F).sql.gz` |
| Neo4j（可重建） | `docker compose exec neo4j neo4j-admin database dump neo4j --to-path=/data/backups` |
| AI 索引数据（可重建） | `tar -czf ai_data_$(date +%F).tar.gz -C $(docker volume inspect -f '{{.Mountpoint}}' knowledge-os_ai_data) .` |
| 摄入原件 | 同上 `ai_data` 卷中的 `source-files/` |

建议写入定时任务：

```bash
sudo tee /etc/cron.d/knowledge-os-backup > /dev/null <<'EOF'
0 2 * * * root cd /opt/ai-knowledge-os && docker compose exec -T mysql mysqldump -uroot -p'Str0ng_Root_Pwd' --single-transaction --routines --events knowledge_os | gzip > /backup/kos_$(date +\%F).sql.gz
EOF
```

> MySQL 是唯一权威状态源，其余（ES/Milvus/Neo4j/AI 索引）都设计为可重建视图，见仓库 `README.md` 的"设计边界"。

### 8.3 恢复

```bash
docker compose exec -T mysql mysql -uroot -p'Str0ng_Root_Pwd' knowledge_os < backup.sql
docker compose restart backend index-worker
```

### 8.4 升级

```bash
# 源码部署方式
git pull
docker compose build ai-service backend frontend
docker compose up -d            # 只替换变更容器，数据卷保持

# 内网仓库方式（192.168.22.6:5000）：重新拉新版本并改名后重建容器
VERSION=1.2.0 bash pull-mirror.sh
docker compose --env-file .env up -d --no-build

# 离线包方式
docker load -i images/<新>(镜像包)
# 修改 release.env 中的 VERSION 后
./stop.sh && ./start.sh
```

Flyway 会自动执行 `backend/src/main/resources/db/migration` 下的 V1~V32 迁移。升级前先备份 MySQL。

### 8.5 监控（可选但推荐）

- Prometheus：`http://<主机IP>:9090`（dev 编排 9092）
- Grafana：`http://<主机IP>:3000`（dev 编排 3001），默认 `admin/admin123`，**上线必须改**
- 抓取目标在 `monitoring/prometheus.yml`：`backend:8080` 与 `index-worker:8081` 的 `/actuator/prometheus`

---

## 九、故障排查 FAQ

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `elasticsearch` 反复重启，日志出现 `vm.max_map_count is too low` | 未调优内核参数 | 执行第三节 `sysctl --system`，再 `docker compose restart elasticsearch` |
| `milvus` 起不来 | 依赖 etcd/minio 未健康，或磁盘不足 | `docker compose logs milvus-etcd milvus-minio`，清理磁盘后重试 |
| `backend` 启动即退出 | MySQL 未就绪 / Flyway 校验失败 | `docker compose logs backend`；确认未手工改过库结构（`validate-on-migrate: true` 会因手工改表而中止） |
| 答案是占位内容 | LLM/Embedding 未接通 | 检查 `LLM_BASE_URL`；容器内到宿主模型需 `host.docker.internal`（编排已加 `extra_hosts`） |
| Milvus 报维度不匹配 | `EMBEDDING_DIM` 与模型不一致 | `nomic-embed-text` → 768；`hash` provider → 384；改后需重建集合 |
| 前端能开但接口 502 | backend 未 healthy 或 nginx 代理目标错误 | 容器内 nginx 代理到 `backend:8080`，只有容器名为 `backend` 的服务可用 |
| 端口被占用 | 宿主已有服务 | 生产编排可用 `PUBLIC_HTTP_PORT` / `BACKEND_HTTP_PORT` / `PROMETHEUS_HTTP_PORT` / `GRAFANA_HTTP_PORT` 调整 |
| 上传大文件失败 | nginx/后端体积限制 | 后端 `MAX_FILE_SIZE=100MB`；nginx 默认 `client_max_body_size 1m` 需调大（见本文档附录镜像改造说明） |
| 中文乱码 | MySQL 字符集 | 生产编排已固定 `utf8mb4` + `utf8mb4_0900_ai_ci`，不要改动 `command` |
| `docker pull` 报 `server gave HTTP response to HTTPS client` | 私有仓库是 HTTP | 按第四节方式 C 把 `192.168.22.6:5000` 写进 `daemon.json` 的 `insecure-registries` 并重启 docker |
| 内网仓库拉镜像 `manifest unknown` | 标签不存在 | `curl -s http://192.168.22.6:5000/v2/knowledge-os/<镜像>/tags/list` 核对标签，应用镜像为 `1.1.0`，中间件沿用官方版本号 |
| 明明已拉到镜像却仍触发本地构建 | 用了 `make up`（等于 `up -d --build`） | 内网仓库方式统一用 `docker compose up -d --no-build` |

### 附录：放大 nginx 上传限制

仓库自带 `frontend/nginx.conf` 未设置 `client_max_body_size`（默认 1 MB），需要上传超过 1 MB 的原件时按以下方式改造：

```bash
cd /opt/ai-knowledge-os/frontend
python3 - <<'EOF'
p='nginx.conf'; s=open(p).read()
if 'client_max_body_size' not in s:
    s=s.replace('  listen 80;', '  client_max_body_size 200m;\n  listen 80;')
    open(p,'w').write(s)
EOF
docker compose build frontend && docker compose up -d frontend
```
