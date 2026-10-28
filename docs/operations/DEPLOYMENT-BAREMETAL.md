# 裸机软件部署手册（Ubuntu 24.04.1 LTS）

> 面向一台**全新安装的 Ubuntu 24.04.1 LTS（noble）**，不使用容器（除可选的 Milvus），
> 直接在操作系统上安装 MySQL / Redis / Neo4j / Elasticsearch / Milvus / Nginx / JDK / Node / Python，
> 并以 systemd 托管 `backend`、`ai-service`、`frontend`。
>
> 与 [DEPLOYMENT-DOCKER.md](./DEPLOYMENT-DOCKER.md) 二选一，不要混用。
> 所有命令以 root 或 sudo 执行；示例密码请全部替换。

---

## 一、规划

### 1.1 目录与账号

| 用途 | 路径 |
| --- | --- |
| 应用代码 | `/opt/ai-knowledge-os` |
| 数据根目录 | `/data/ai-knowledge-os` |
| 配置（systemd EnvironmentFile） | `/etc/ai-knowledge-os` |
| 日志 | `/var/log/ai-knowledge-os` |
| 备份 | `/backup/ai-knowledge-os` |
| 前端静态资源 | `/var/www/knowledge-os` |

```bash
sudo useradd -r -s /usr/sbin/nologin kos 2>/dev/null
sudo mkdir -p /opt/ai-knowledge-os /etc/ai-knowledge-os /data/ai-knowledge-os \
             /var/log/ai-knowledge-os /var/www/knowledge-os /backup/ai-knowledge-os
```

### 1.2 组件版本矩阵

| 组件 | 版本 | 安装方式 | 必需 |
| --- | --- | --- | --- |
| JDK（后端） | 8（Temurin 8 / openjdk-8） | apt 或 Adoptium 源 | 是 |
| Maven | 3.9.x | Apache 二进制包 | 是（构建期） |
| Node.js | 20.x | NodeSource deb | 是（构建期） |
| Python | 3.10 | Miniconda 环境 `llmkb`（与仓库 Makefile 一致） | 是 |
| MySQL | 8.0.x | Ubuntu apt | 是 |
| Redis | 7.x | packages.redis.io | 是 |
| Neo4j | 5.26 | debian.neo4j.com deb（依赖 JDK 17） | 否（不通则降级 Null provider） |
| Elasticsearch | 9.5.3 | artifacts.elastic.co deb | 否（可降级本地 BM25） |
| Milvus | 3.0.1 | 官方仅提供容器单机版 | 否（建议混合：Docker 跑 Milvus） |
| Nginx | 1.24 | Ubuntu apt | 是 |

> **为什么后端要 JDK 8**：`backend/pom.xml` 中 `<java.version>1.8</java.version>`，父 POM 为 Spring Boot 2.7.18。
> **为什么 Neo4j 要 JDK 17**：Neo4j 5.x 要求 JDK 17。两个 JDK 可在同一台机器共存（后端用绝对路径指向 JDK 8，Neo4j 通过 `NEO4J_JAVA_HOME` 指向 17）。

### 1.3 端口分配

| 端口 | 服务 | 是否对外 |
| --- | --- | --- |
| 3306 | MySQL | 否 |
| 6379 | Redis | 否 |
| 7474 / 7687 | Neo4j HTTP / Bolt | 否 |
| 9200 | Elasticsearch | 否 |
| 19530 | Milvus | 否 |
| 12000 | ai-service | 否 |
| 8080 | backend | 内网/调试 |
| 8081 | index-worker（可选） | 否 |
| 8088 | Nginx → 前端静态站 + `/api` 反代 | **是** |

```bash
sudo ufw allow 22/tcp
sudo ufw allow 8088/tcp
sudo ufw enable
```

---

## 二、系统准备

```bash
sudo apt-get update && sudo apt-get upgrade -y
sudo apt-get install -y curl wget gnupg ca-certificates lsb-release unzip git jq python3
sudo timedatectl set-timezone Asia/Shanghai
```

内核参数（ES/Milvus 需要）：

```bash
sudo tee /etc/sysctl.d/99-knowledge-os.conf > /dev/null <<'EOF'
vm.max_map_count=262144
vm.swappiness=1
fs.file-max=200000
net.core.somaxconn=65535
EOF
sudo sysctl --system

sudo tee /etc/security/limits.d/99-knowledge-os.conf > /dev/null <<'EOF'
kos      soft nofile 65536
kos      hard nofile 65536
mysql    soft nofile 65536
mysql    hard nofile 65536
elasticsearch soft nofile 65536
elasticsearch hard nofile 65536
EOF
```

---

## 三、安装 MySQL 8.0

```bash
sudo apt-get install -y mysql-server
sudo systemctl enable --now mysql
sudo mysql_secure_installation       # 交互：设置 root 密码，移除匿名用户，禁止 root 远程登录
```

### 3.1 建库与账号

```bash
sudo mysql -uroot -p <<'SQL'
CREATE DATABASE IF NOT EXISTS knowledge_os
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'knowledge'@'%' IDENTIFIED BY 'Str0ng_Db_Pwd';
GRANT ALL PRIVILEGES ON knowledge_os.* TO 'knowledge'@'%';
FLUSH PRIVILEGES;
SQL
```

> 后端 JDBC URL 已带 `allowPublicKeyRetrieval=true`，兼容 MySQL 8 默认的 `caching_sha2_password`。

### 3.2 参数调优

```bash
sudo tee /etc/mysql/conf.d/knowledge-os.cnf > /dev/null <<'EOF'
[mysqld]
character-set-server          = utf8mb4
collation-server              = utf8mb4_0900_ai_ci
default-time-zone             = +08:00
max_allowed_packet            = 256M
max_connections               = 500
innodb_buffer_pool_size       = 1G          # 内存的 40%~60%，按需调整
slow_query_log                = ON
long_query_time               = 2
EOF
sudo systemctl restart mysql
```

验证：

```bash
mysql -uknowledge -p'Str0ng_Db_Pwd' -h127.0.0.1 -e "SELECT @@character_set_database, @@time_zone;"
```

数据库表结构无需手工创建：后端内置 Flyway，启动时自动执行 `V1__baseline.sql` … `V73__api_key_soft_delete.sql`（当前共 **73** 个迁移，含默认知识库 `KB_DEFAULT`）。

> 迁移数量会随版本增长，**不要记住具体数字**，以 `backend/src/main/resources/db/migration/` 的实际文件数为准。

---

## 四、安装 Redis 7

```bash
curl -fsSL https://packages.redis.io/gpg | sudo gpg --dearmor -o /usr/share/keyrings/redis-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/redis.list > /dev/null
sudo apt-get update
sudo apt-get install -y redis-server
```

配置（`/etc/redis/redis.conf`）：

```conf
bind 127.0.0.1 -::1
appendonly yes
maxmemory 1gb
maxmemory-policy allkeys-lru
# requirepass Str0ng_Redis_Pwd      # 需要鉴权才打开，并同步到 backend.env 的 REDIS_PASSWORD
```

```bash
sudo systemctl enable --now redis-server
redis-cli ping          # PONG
```

---

## 五、安装 JDK 8 与 Maven

### 5.1 JDK 8

```bash
sudo apt-get install -y openjdk-8-jdk-headless
java -version    # openjdk version "1.8.0_xxx"
```

若当前镜像源没有 `openjdk-8`（部分源已裁剪），改用 Eclipse Temurin：

```bash
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list > /dev/null
sudo apt-get update
sudo apt-get install -y temurin-8-jdk
```

记录路径，后面 systemd 要用：

```bash
readlink -f "$(command -v java)"     # 例如 /usr/lib/jvm/temurin-8-jdk-amd64/bin/java
```

### 5.2 Maven 3.9.9

```bash
cd /tmp
wget -q https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz
sudo tar -xzf apache-maven-3.9.9-bin.tar.gz -C /opt
sudo ln -sfn /opt/apache-maven-3.9.9 /opt/maven
echo 'export PATH=/opt/maven/bin:$PATH' | sudo tee /etc/profile.d/maven.sh > /dev/null
source /etc/profile.d/maven.sh
mvn -v
```

若公司网络走代理，Maven 不读 `http_proxy`，需要 `~/.m2/settings.xml` 配置 `<proxies>`（仓库 Makefile 的做法是把代理转成 `-Dhttp.proxyHost=...`）。

---

## 六、安装 Node.js 20 与 Nginx

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
node -v    # v20.x
npm -v

sudo apt-get install -y nginx
sudo systemctl enable nginx
```

---

## 七、安装 Python 3.10（Miniconda 环境 llmkb）

后端 jar 之外，`ai-service` 是 Python 服务。仓库 Makefile 统一使用 conda 环境 **`llmkb`**（Python 3.10），保持一致可减少踩坑。

```bash
wget -q https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh -O /tmp/miniconda.sh
bash /tmp/miniconda.sh -b -p /opt/miniconda3
/opt/miniconda3/bin/conda create -y -n llmkb python=3.10
/opt/miniconda3/envs/llmkb/bin/python -V      # Python 3.10.x
```

若希望 `make e2e` 等脚本也能用，补充软链：

```bash
sudo ln -sfn /opt/miniconda3/envs/llmkb/bin/python /usr/local/bin/kos-python
```

---

## 八、安装 Neo4j 5.26（可选但推荐）

```bash
sudo apt-get install -y openjdk-17-jdk-headless

wget -qO - https://debian.neo4j.com/neotechnology.gpg.key | sudo gpg --dearmor -o /etc/apt/keyrings/neo4j.gpg
echo "deb [signed-by=/etc/apt/keyrings/neo4j.gpg] https://debian.neo4j.com stable 5" \
  | sudo tee /etc/apt/sources.list.d/neo4j.list > /dev/null
sudo apt-get update
sudo apt-get install -y neo4j=1:5.26.0
```

指定 JDK 17（避免与系统默认 JDK 8 冲突）：

```bash
sudo tee -a /etc/neo4j/neo4j-wrapper.conf > /dev/null <<'EOF'
NEO4J_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
EOF
```

调整监听地址（`grep -n` 后改动 `/etc/neo4j/neo4j.conf`）：

```conf
server.default_listen_address=0.0.0.0
server.bolt.enabled=true
server.bolt.listen_address=:7687
server.http.listen_address=:7474
```

设置初始密码（**服务必须处于停止状态**）：

```bash
sudo systemctl stop neo4j
sudo neo4j-admin dbms set-initial-password Str0ng_Neo4j_Pwd
sudo systemctl enable --now neo4j
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:7474
```

> 未安装 Neo4j 也能运行：`app/services/graph_provider.py` 的 `get_graph_provider()` 在连接失败时自动回退 `NullGraphProvider()`，图谱相关能力为空，其余功能不受影响。

---

## 九、安装 Elasticsearch 9.5.3（可选）

```bash
wget -qO - https://artifacts.elastic.co/GPG-KEY-elasticsearch | sudo gpg --dearmor -o /usr/share/keyrings/elasticsearch-keyring.gpg
sudo apt-get install -y apt-transport-https
echo "deb [signed-by=/usr/share/keyrings/elasticsearch-keyring.gpg] https://artifacts.elastic.co/packages/9.x/apt stable main" \
  | sudo tee /etc/apt/sources.list.d/elastic-9.x.list > /dev/null
sudo apt-get update
sudo apt-get install -y elasticsearch=9.5.3
```

最小单节点配置（`/etc/elasticsearch/elasticsearch.yml`）：

```yaml
cluster.name: knowledge-os
node.name: node-1
discovery.type: single-node
network.host: 127.0.0.1
http.port: 9200
xpack.license.self_generated.type: basic
```

堆内存：

```bash
echo "-Xms2g
-Xmx2g" | sudo tee /etc/elasticsearch/jvm.options.d/heap.options > /dev/null
```

**方案 A（保留安全认证，推荐生产）**

```bash
sudo systemctl enable --now elasticsearch
sudo /usr/share/elasticsearch/bin/elasticsearch-reset-password -u elastic -i
```

对应配置：

```ini
ELASTICSEARCH_ENABLED=true
ELASTICSEARCH_URL=https://127.0.0.1:9200
ELASTICSEARCH_USERNAME=elastic
ELASTICSEARCH_PASSWORD=<上一步设置的密码>
ELASTICSEARCH_VERIFY_SSL=false     # 自签证书场景
```

**方案 B（内网测试，关闭安全）**

```bash
echo "xpack.security.enabled: false" | sudo tee -a /etc/elasticsearch/elasticsearch.yml
sudo systemctl restart elasticsearch
```

对应配置：`ELASTICSEARCH_URL=http://127.0.0.1:9200`，用户名密码留空。

验证：

```bash
curl -fsS http://127.0.0.1:9200/_cluster/health    # 方案 B
curl -fsSk -u elastic:<密码> https://127.0.0.1:9200/_cluster/health   # 方案 A
```

---

## 十、安装 Milvus 3.0.1（混合部署）

Milvus 官方单机版没有 deb 包，**建议在本机安装 Docker 只用于跑 Milvus**：

```bash
sudo apt-get install -y docker.io docker-compose-v2
sudo systemctl enable --now docker
```

复用仓库编排，只拉起 Milvus 三件套：

```bash
cd /opt/ai-knowledge-os
MYSQL_ROOT_PASSWORD=unused docker compose -f docker-compose.yml up -d milvus-etcd milvus-minio milvus
docker compose -f docker-compose.yml ps | grep -E "milvus"
curl -fsS http://127.0.0.1:9091/healthz && echo " milvus OK"
```

端口已映射宿主 `19530/9091`，裸机进程用 `MILVUS_URL=http://127.0.0.1:19530` 访问。

**完全不用 Docker 的场景**：跳过 Milvus，改本地向量通道：

```ini
MILVUS_ENABLED=false
RETRIEVAL_MODE=local
```

> `RETRIEVAL_MODE=local` 时检索走内置的 `_bm25` / `_typed` 本地实现（`app/services/retrieval/hybrid.py`），功能可用、规模受限。
> `RETRIEVAL_MODE=auto` 则是"优先 ES/Milvus，失败自动降级"，不要设为 `external` + `RETRIEVAL_EXTERNAL_REQUIRED=true`，否则依赖缺失会直接报错。

---

## 十一、部署后端（backend）

### 11.1 获取代码并构建

```bash
cd /opt
sudo git clone -b v1.4 <你的仓库地址> ai-knowledge-os
sudo chown -R root:root /opt/ai-knowledge-os
cd /opt/ai-knowledge-os/backend
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64     # 按实际路径调整
mvn -q -DskipTests package
ls target/*.jar    # knowledge-os-backend-1.1.0-SNAPSHOT.jar
```

离线/有网限制时可用 `-o`（仓库已有完整本地仓库缓存）：

```bash
mvn -o -DskipTests package
```

### 11.2 环境文件

```bash
sudo tee /etc/ai-knowledge-os/backend.env > /dev/null <<'EOF'
SERVER_PORT=8080

DB_URL=jdbc:mysql://127.0.0.1:3306/knowledge_os?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
DB_USERNAME=knowledge
DB_PASSWORD=Str0ng_Db_Pwd
DB_POOL_MAX=20
DB_POOL_MIN=5

REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=

AI_SERVICE_BASE_URL=http://127.0.0.1:12000
AI_CONNECT_TIMEOUT_MS=10000
AI_READ_TIMEOUT_MS=180000

SOURCE_STORAGE_DIR=/data/ai-knowledge-os/knowledge/source-files
MAX_FILE_SIZE=100MB
MAX_REQUEST_SIZE=100MB

AUTH_ENABLED=true
AUTH_ALLOW_ANONYMOUS=false
AUTH_SESSION_HOURS=12
AUTH_BOOTSTRAP_ADMIN_USERNAME=admin
AUTH_BOOTSTRAP_ADMIN_PASSWORD=Str0ng_Admin_Pwd

INDEX_OUTBOX_ENABLED=true
INDEX_OUTBOX_POLL_INTERVAL_MS=1000
INDEX_OUTBOX_BATCH_SIZE=20
INDEX_OUTBOX_MAX_ATTEMPTS=8
INDEX_OUTBOX_ARCHIVE_ENABLED=true

OPS_RBAC_ENABLED=true
OPS_VIEWER_TOKEN=<openssl rand -hex 16>
OPS_OPERATOR_TOKEN=<openssl rand -hex 16>
OPS_ADMIN_TOKEN=<openssl rand -hex 16>

STARTUP_FAIL_FAST=true
FLYWAY_ENABLED=true
LOG_LEVEL=INFO
EOF
sudo chmod 600 /etc/ai-knowledge-os/backend.env
```

> systemd 的 `EnvironmentFile` **不做变量展开**，`${VAR}` 引用不会被解析，务必写死值。
> `AUTH_BOOTSTRAP_ADMIN_PASSWORD` 留空时，后端首次启动会随机生成密码并打印到日志（见 13.3）。

### 11.3 systemd 单元

```bash
sudo tee /etc/systemd/system/kos-backend.service > /dev/null <<'EOF'
[Unit]
Description=Knowledge OS Backend
Documentation=file:///opt/ai-knowledge-os/README.md
After=network-online.target mysql.service redis-server.service kos-ai.service
Wants=network-online.target kos-ai.service

[Service]
Type=simple
User=kos
Group=kos
WorkingDirectory=/opt/ai-knowledge-os/backend
EnvironmentFile=/etc/ai-knowledge-os/backend.env
Environment=JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
ExecStart=/usr/lib/jvm/java-8-openjdk-amd64/bin/java \
  -Xms1g -Xmx2g -XX:+ExitOnOutOfMemoryError \
  -jar /opt/ai-knowledge-os/backend/knowledge-os-backend-1.1.0-SNAPSHOT.jar
Restart=always
RestartSec=10
TimeoutStopSec=30
LimitNOFILE=65536
StandardOutput=append:/var/log/ai-knowledge-os/backend.log
StandardError=append:/var/log/ai-knowledge-os/backend.log

[Install]
WantedBy=multi-user.target
EOF

sudo chown -R kos:kos /data/ai-knowledge-os /var/log/ai-knowledge-os /opt/ai-knowledge-os/backend/data 2>/dev/null
sudo systemctl daemon-reload
```

---

## 十二、部署 AI 服务（ai-service）

### 12.1 安装依赖

```bash
sudo /opt/miniconda3/envs/llmkb/bin/pip install --no-cache-dir \
  -r /opt/ai-knowledge-os/ai-service/requirements.txt
```

需要 Reranker（BGE/CrossEncoder）时追加 `requirements-ml.txt`，这会拉取 torch（数 GB）：

```bash
sudo /opt/miniconda3/envs/llmkb/bin/pip install --no-cache-dir \
  -r /opt/ai-knowledge-os/ai-service/requirements-ml.txt
```

### 12.2 数据目录

```bash
sudo mkdir -p /data/ai-knowledge-os/knowledge/{indexes,uploads,source-files,model-cache}
sudo chown -R kos:kos /data/ai-knowledge-os
```

### 12.3 环境文件

```bash
sudo tee /etc/ai-knowledge-os/ai-service.env > /dev/null <<'EOF'
KNOWLEDGE_DATA_DIR=/data/ai-knowledge-os/knowledge

# ---- LLM（示例：本机 Ollama）----
LLM_PROVIDER=ollama
LLM_BASE_URL=http://127.0.0.1:11434
LLM_MODEL=qwen2.5:7b
LLM_API_KEY=

# ---- Embedding：向量维度必须与模型一致 ----
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text
EMBEDDING_DIM=768
EMBEDDING_API_KEY=

# ---- Graph ----
GRAPH_PROVIDER=neo4j
NEO4J_URI=bolt://127.0.0.1:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=Str0ng_Neo4j_Pwd
NEO4J_DATABASE=neo4j

# ---- Retrieval ----
RETRIEVAL_MODE=auto
RETRIEVAL_EXTERNAL_REQUIRED=false
RETRIEVAL_TIMEOUT_SECONDS=5
RETRIEVAL_BATCH_SIZE=200

ELASTICSEARCH_ENABLED=true
ELASTICSEARCH_URL=http://127.0.0.1:9200
ELASTICSEARCH_INDEX=llmkb_knowledge_v1
ELASTICSEARCH_USERNAME=
ELASTICSEARCH_PASSWORD=
ELASTICSEARCH_VERIFY_SSL=false
ELASTICSEARCH_READ_ALIAS=llmkb_knowledge_read

MILVUS_ENABLED=true
MILVUS_URL=http://127.0.0.1:19530
MILVUS_COLLECTION=llmkb_knowledge_v1
MILVUS_TOKEN=
MILVUS_READ_ALIAS=llmkb_knowledge_read

# ---- Reranker（可选，需 ML 依赖）----
RERANKER_ENABLED=false

# ---- 答案质量门禁 ----
ANSWER_EVIDENCE_GATE_ENABLED=true
ANSWER_FAITHFULNESS_MIN=0.03
ANSWER_ENTAILMENT_MIN=0.08
ANSWER_POLICY_VERSION=answer-policy-v1
EOF
sudo chmod 600 /etc/ai-knowledge-os/ai-service.env
```

> 若第九步采用 ES 安全认证，`ELASTICSEARCH_URL` 改为 `https://127.0.0.1:9200` 并填写 `elastic` 与其密码。

### 12.4 systemd 单元

```bash
sudo tee /etc/systemd/system/kos-ai.service > /dev/null <<'EOF'
[Unit]
Description=Knowledge OS AI Service
After=network-online.target mysql.service neo4j.service
Wants=network-online.target

[Service]
Type=simple
User=kos
Group=kos
WorkingDirectory=/opt/ai-knowledge-os/ai-service
EnvironmentFile=/etc/ai-knowledge-os/ai-service.env
Environment=PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
ExecStart=/opt/miniconda3/envs/llmkb/bin/uvicorn app.main:app --host 127.0.0.1 --port 12000
Restart=always
RestartSec=10
LimitNOFILE=65536
StandardOutput=append:/var/log/ai-knowledge-os/ai-service.log
StandardError=append:/var/log/ai-knowledge-os/ai-service.log

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now kos-ai kos-backend
```

---

## 十三、部署前端（frontend + Nginx）

### 13.1 构建静态资源

```bash
cd /opt/ai-knowledge-os/frontend
npm install
npm run build                     # 产物 dist/
sudo rm -rf /var/www/knowledge-os/*
sudo cp -r dist/. /var/www/knowledge-os/
sudo chown -R www-data:www-data /var/www/knowledge-os
```

> 可选类型检查：`npm run typecheck`。

### 13.2 Nginx 站点

等价于容器内的 `frontend/nginx.conf`（静态站 + `/api` 反代到 8080），本机版如下：

```bash
sudo tee /etc/nginx/sites-available/knowledge-os.conf > /dev/null <<'EOF'
upstream kos_backend { server 127.0.0.1:8080; }

server {
    listen 8088;
    server_name _;
    client_max_body_size 200m;          # 与 backend MAX_FILE_SIZE 配套

    root /var/www/knowledge-os;
    index index.html;

    location / { try_files $uri $uri/ /index.html; }

    location /api/ {
        proxy_pass http://kos_backend;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Trace-Id $http_x_trace_id;
        proxy_connect_timeout 10s;
        # AI_READ_TIMEOUT_MS=180000，读超时必须大于它
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
EOF

sudo ln -sfn /etc/nginx/sites-available/knowledge-os.conf /etc/nginx/sites-enabled/knowledge-os.conf
sudo nginx -t && sudo systemctl reload nginx
```

访问：`http://<主机IP>:8088`

### 13.3 首次初始管理员

后端首次启动（`t_user` 为空）会创建管理员：

- 已配置 `AUTH_BOOTSTRAP_ADMIN_PASSWORD` → 直接用该密码；
- 未配置 → 随机生成并打印到 `/var/log/ai-knowledge-os/backend.log`。

```bash
sudo grep -A3 "已创建初始管理员账号" /var/log/ai-knowledge-os/backend.log
```

登录后立即修改密码。该账号同时成为默认知识库（`KB_DEFAULT`，`t_knowledge_base.id=1`）的 Owner。

---

## 十四、可选：独立 index-worker

默认 backend 内已消费 Outbox 队列（`INDEX_OUTBOX_ENABLED=true`）。若索引量较大需要分离，**只能有一个进程开启 outbox**：

```bash
sudo tee /etc/ai-knowledge-os/index-worker.env > /dev/null <<'EOF'
SERVER_PORT=8081
DB_URL=jdbc:mysql://127.0.0.1:3306/knowledge_os?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
DB_USERNAME=knowledge
DB_PASSWORD=Str0ng_Db_Pwd
REDIS_HOST=127.0.0.1
AI_SERVICE_BASE_URL=http://127.0.0.1:12000
INDEX_OUTBOX_ENABLED=true
INDEX_OUTBOX_ARCHIVE_ENABLED=true
FLYWAY_ENABLED=false
STARTUP_FAIL_FAST=true
EOF
sudo chmod 600 /etc/ai-knowledge-os/index-worker.env
```

```bash
sudo tee /etc/systemd/system/kos-index-worker.service > /dev/null <<'EOF'
[Unit]
Description=Knowledge OS Index Worker
After=network-online.target mysql.service kos-ai.service
Wants=network-online.target

[Service]
Type=simple
User=kos
Group=kos
WorkingDirectory=/opt/ai-knowledge-os/backend
EnvironmentFile=/etc/ai-knowledge-os/index-worker.env
Environment=JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
ExecStart=/usr/lib/jvm/java-8-openjdk-amd64/bin/java -Xms512m -Xmx1g -XX:+ExitOnOutOfMemoryError \
  -jar /opt/ai-knowledge-os/backend/knowledge-os-backend-1.1.0-SNAPSHOT.jar
Restart=always
RestartSec=10
LimitNOFILE=65536
StandardOutput=append:/var/log/ai-knowledge-os/index-worker.log
StandardError=append:/var/log/ai-knowledge-os/index-worker.log

[Install]
WantedBy=multi-user.target
EOF
```

启用前把 backend 的 `INDEX_OUTBOX_ENABLED` 改为 `false` 并重启：

```bash
sudo sed -i 's/^INDEX_OUTBOX_ENABLED=true/INDEX_OUTBOX_ENABLED=false/' /etc/ai-knowledge-os/backend.env
sudo systemctl daemon-reload
sudo systemctl restart kos-backend
sudo systemctl enable --now kos-index-worker
```

---

## 十五、验收

### 15.1 服务状态

```bash
systemctl status mysql redis-server kos-ai kos-backend nginx --no-pager
```

### 15.2 端点

```bash
curl -fsS http://127.0.0.1:12000/health            | jq .
curl -fsS http://127.0.0.1:8080/actuator/health
curl -s -o /dev/null -w "frontend HTTP %{http_code}\n" http://127.0.0.1:8088/
```

### 15.3 业务链路

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Str0ng_Admin_Pwd"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

curl -s http://127.0.0.1:8080/api/knowledge/bases -H "Authorization: Bearer $TOKEN" | head -c 300
```

### 15.4 端到端脚本

```bash
export PATH=/opt/maven/bin:$PATH
E2E_BACKEND_URL=http://127.0.0.1:8080 \
E2E_AUTH_USERNAME=admin E2E_AUTH_PASSWORD='Str0ng_Admin_Pwd' \
/opt/miniconda3/envs/llmkb/bin/python scripts/e2e-test.py
```

脚本覆盖：登录 → 创建知识源 → 上传文档 → 摄入解析 → 检索问答 → 治理。

### 15.5 数据正确性快检

```bash
mysql -uknowledge -p'Str0ng_Db_Pwd' knowledge_os -e "SHOW TABLES;" | wc -l     # 应远大于 16
mysql -uknowledge -p'Str0ng_Db_Pwd' knowledge_os -e "SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

---

## 十六、运维

### 16.1 日志

```bash
sudo tail -f /var/log/ai-knowledge-os/backend.log
sudo journalctl -u kos-ai -f
```

logrotate：

```bash
sudo tee /etc/logrotate.d/knowledge-os > /dev/null <<'EOF'
/var/log/ai-knowledge-os/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
EOF
```

### 16.2 备份

```bash
sudo tee /usr/local/bin/kos-backup.sh > /dev/null <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
STAMP=$(date +%F-%H%M)
DIR=/backup/ai-knowledge-os/$STAMP
mkdir -p "$DIR"

mysqldump -uknowledge -p'Str0ng_Db_Pwd' \
  --single-transaction --routines --events --default-character-set=utf8mb4 \
  knowledge_os | gzip > "$DIR/knowledge_os.sql.gz"

tar -czf "$DIR/knowledge-data.tar.gz" -C /data/ai-knowledge-os/knowledge .
redis-cli --rdb "$DIR/dump.rdb" >/dev/null 2>&1 || true
find /backup/ai-knowledge-os -maxdepth 1 -type d -mtime +14 -exec rm -rf {} +
echo "backup -> $DIR"
EOF
sudo chmod 700 /usr/local/bin/kos-backup.sh

echo '0 2 * * * root /usr/local/bin/kos-backup.sh >> /var/log/ai-knowledge-os/backup.log 2>&1' \
  | sudo tee /etc/cron.d/knowledge-os-backup > /dev/null
```

Neo4j / ES / Milvus 均为可重建视图（见仓库 `README.md` 设计边界），生产上建议至少保留 Neo4j 的 `neo4j-admin dump`：

```bash
sudo systemctl stop neo4j
sudo -u neo4j neo4j-admin database dump neo4j --to-path=/backup/ai-knowledge-os/neo4j
sudo systemctl start neo4j
```

### 16.3 恢复

```bash
systemctl stop kos-backend kos-index-worker kos-ai
mysql -uknowledge -p'Str0ng_Db_Pwd' knowledge_os < restore.sql
tar -xzf knowledge-data.tar.gz -C /data/ai-knowledge-os/knowledge
systemctl start kos-ai kos-backend kos-index-worker
```

### 16.4 升级

```bash
cd /opt/ai-knowledge-os
git pull

# 后端
cd backend && JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 mvn -q -DskipTests package
sudo systemctl restart kos-backend kos-index-worker

# AI 服务
sudo /opt/miniconda3/envs/llmkb/bin/pip install -r ../ai-service/requirements.txt
sudo systemctl restart kos-ai

# 前端
cd ../frontend && npm install && npm run build
sudo cp -r dist/. /var/www/knowledge-os/
```

升级前务必先执行备份；Flyway 会自动应用新增迁移脚本。

### 16.5 卸载 / 回滚

```bash
sudo systemctl disable --now kos-backend kos-index-worker kos-ai
sudo rm -f /etc/systemd/system/kos-*.service /etc/nginx/sites-enabled/knowledge-os.conf
sudo systemctl daemon-reload
```

---

## 十七、故障排查 FAQ

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `mvn package` 报 `invalid target release: 1.8` | 用了 JDK 11/17 | `JAVA_HOME` 指向 JDK 8 再构建 |
| 后端启动报 `Access denied for user 'knowledge'` | 账号/密码不一致或库不存在 | 复查第三节建库语句，注意 `DB_PASSWORD` 与 grant 一致 |
| 后端报 `Public Key Retrieval is not allowed` | JDBC 缺参数 | URL 必须带 `allowPublicKeyRetrieval=true` |
| 登录成功但刷新即掉线 | Redis 不可达或被清 | `redis-cli ping`；确认 `REDIS_HOST=127.0.0.1` |
| Flyway 报 validate 失败 | 手工改过表结构 | 生产禁止手工改库；用备份恢复后重新升级 |
| 问答超时 504 | nginx 读超时太短 / AI 慢 | `proxy_read_timeout 300s`，并提高 `AI_READ_TIMEOUT_MS` |
| Milvus 报维度不匹配 | `EMBEDDING_DIM` 不等于模型输出维度 | `nomic-embed-text` → 768；改后需重建集合 |
| 图谱为空但不报错 | Neo4j 未起 | 属预期降级（`NullGraphProvider`）；如需图谱按第八节安装 |
| 检索结果质量差 | 只跑本地检索 | 检查 ES/Milvus 连通；`RETRIEVAL_MODE` 不要设为 `local` |
| 前端 404（刷新子路由） | nginx 缺 `try_files` | 确认 13.2 的 `location /` 配置 |
| 大文件上传失败 | nginx / 后端体积上限 | `client_max_body_size 200m` + `MAX_FILE_SIZE=100MB` |
| 端口占用 | 宿主已有服务冲突 | 改 `SERVER_PORT` / Nginx `listen`，并同步反向代理 |
| 时区不对 | 系统/MySQL 时区 | `timedatectl set-timezone Asia/Shanghai` 且 MySQL `default-time-zone=+08:00` |
