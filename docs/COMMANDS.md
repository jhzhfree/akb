# 命令速查

面向开发、发布、交付三个阶段。所有命令均在项目根目录执行。

## 一、开发阶段

开发采用**中间件走容器、应用本地裸跑**的模式：改代码即时生效，无需重建镜像。
`backend` 默认连接 `127.0.0.1:3306/6379/12000`，`ai-service` 默认连接
`bolt://127.0.0.1:7687`，`frontend` 的 vite dev server 已配置 `/api` 代理到
`127.0.0.1:8080`，三者与容器端口映射天然对齐。

### 1.1 容器：中间件

```bash
make dev-up        # 启动 mysql + redis + neo4j（默认 profile）
make dev-ps        # 查看状态
make dev-logs      # 跟随日志
make dev-down      # 停止（保留数据卷）
make dev-clean     # 停止并删除数据卷
```

### 1.2 容器：应用栈（联调整体链路时使用）

```bash
make dev-app-up          # 构建并启动 ai-service + backend + frontend
make dev-ai-up           # 只重建并启动 ai-service
make dev-backend-up      # 只重建并启动 backend
make dev-frontend-up     # 只重建并启动 frontend
make dev-logs            # 查看日志
```

### 1.3 本地裸跑（热更新）

Python 侧统一使用 conda 环境 **`llmkb`**（Python 3.10）。

```bash
make dev-install   # 一次性：建 llmkb 环境 + 装 ai-service 依赖 + npm install
make dev-pip       # 改了 requirements.txt 后重装 Python 依赖
make dev-ai        # uvicorn --reload，:12000
make dev-backend   # mvn spring-boot:run，:8080（需本地 Maven 3.8+ 与 JDK8+）
make dev-frontend  # vite dev，:5173
make dev-all       # 并行启动以上三个
```

不通过 Makefile、手工开发时的等价写法：

```bash
conda create -y -n llmkb python=3.10        # 仅首次
conda activate llmkb
pip install -r ai-service/requirements.txt

cd ai-service
NEO4J_URI=bolt://127.0.0.1:7687 LLM_BASE_URL=http://192.168.22.6:11434 \
  python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 12000
```

模型服务地址可用变量覆盖：

```bash
make dev-ai OLLAMA_URL=http://192.168.22.6:11434 LLM_MODEL=qwen2.5:7b
```

本地裸跑的 `ai-service` 默认关闭 ES/Milvus，走本地 BM25 + 本地向量 + Neo4j 图召回。

> **为什么 Makefile 里不用 `conda activate`**：Makefile 每条配方在独立的 `/bin/sh`
> 中执行，无法沿用 shell 里的 conda 函数。因此 `dev-ai` 直接调用
> `$(CONDA_BASE)/envs/llmkb/bin/python`，与激活后运行完全等价且更可靠。
> 环境路径不同可用 `CONDA_BASE`、`CONDA_ENV` 覆盖。

> 本地无 Maven 时，`make dev-backend` 会直接报错退出，此时改用
> `make dev-backend-up` 以容器方式启动后端。

### 1.4 停止本地裸跑服务

```bash
pkill -f "uvicorn app.main:app"   # ai-service，:12000
pkill -f "spring-boot:run"        # backend 的 Maven 包装进程
pkill -f "com.jh.knowledgeos"     # backend 的 Java 进程
ss -ltn | grep -E ":8080|:12000"  # 无输出即端口已释放
```

`pkill` 默认发 `TERM`，进程未退出时再 `pkill -9 -f`。中间件容器不受影响，
`make dev-ps` 仍应显示 healthy。

### 1.5 访问地址

| 服务 | 本地裸跑 | 容器模式 |
| --- | --- | --- |
| 前端 | `http://localhost:5173` | `http://localhost:8088` |
| 后端 API | `http://localhost:8080/swagger-ui.html` | 同左 |
| AI Service | `http://localhost:12000/health` | 同左 |
| Neo4j Browser | `http://localhost:7474` | 同左 |

## 二、发布阶段

### 2.1 构建与推送镜像

```bash
make release-build                       # 构建 knowledge-os/*:1.1.0
make release-build VERSION=1.1.1         # 指定版本

export REGISTRY=192.168.22.16:5050/zhanghe/ai-knowledge-os/
make release-build release-push          # 打标签并推送到远端仓库
```

镜像名规则：`$(REGISTRY)knowledge-os/{ai-service,backend,frontend}:$(VERSION)`。

### 2.2 生成离线安装产品包

```bash
make package                             # 产物 dist/ai-knowledge-1.1.0.tar.gz
make package VERSION=1.1.1               # 指定版本
INCLUDE_MIDDLEWARE=0 make package        # 只打应用镜像，目标机需能联网拉中间件
```

打包流程：构建应用镜像 → 组装产品目录 → `docker save` 导出镜像 →
生成 `SHA256SUMS` → 归档为 tar.gz。

产品包结构：

```
ai-knowledge-1.1.0/
├── docker-compose.yml   # 发布编排，项目名固定 knowledge-os
├── release.env          # 版本号与镜像前缀
├── .env.example         # 安装时复制为 .env
├── images/              # 离线镜像包（app / middleware）
├── monitoring/          # Prometheus 规则、告警、Grafana 面板
├── install.sh start.sh stop.sh status.sh logs.sh uninstall.sh
└── README.md
```

## 三、交付：安装与启动产品

在目标机执行：

```bash
tar -xzf ai-knowledge-1.1.0.tar.gz
cd ai-knowledge-1.1.0
./install.sh      # 预检 docker/compose、加载离线镜像、生成 .env
vi .env           # 必须修改所有 CHANGE_ME 占位值与 LLM_BASE_URL
./start.sh        # 启动
./status.sh       # 查看容器状态与健康端点
./logs.sh backend # 跟随指定服务日志
./stop.sh         # 停止（保留数据卷）
./uninstall.sh    # 停止并删除数据卷（不可恢复）
```

发布编排内置 `index-worker`（`INDEX_OUTBOX_ENABLED=true`）与主 `backend`
（`INDEX_OUTBOX_ENABLED=false`）分离部署，端口与凭据均可在 `.env` 调整：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PUBLIC_HTTP_PORT` | 8088 | 前端 |
| `BACKEND_HTTP_PORT` | 8080 | 后端 API |
| `PROMETHEUS_HTTP_PORT` | 9090 | Prometheus |
| `GRAFANA_HTTP_PORT` | 3000 | Grafana（`admin/admin123`） |

## 四、生产环境（源码部署）

```bash
make prod-up      # 首次会由 prod-env 复制 .env.production.example → .env.production
make prod-ps
make prod-logs
make prod-down
```

生产编排为 `deploy/docker-compose.prod.yml`，读取根目录 `.env.production`。

## 五、质量门禁

```bash
make check        # ./scripts/check-v11.sh
make rc-check     # 同上（发布候选检查）
make e2e          # python scripts/e2e-test.py
make diagnose     # ./scripts/diagnose.sh
```

## 六、环境前置要求与排错

### 6.1 本地裸跑后端需要 JDK（不是 JRE）

`make dev-backend` 走 `mvn spring-boot:run`，必须能调用 `javac`。系统只有 JRE 时会报：

```
No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?
```

安装 JDK 8（与生产镜像 `eclipse-temurin:8-jre` 对齐）：

```bash
sudo apt-get install -y openjdk-8-jdk-headless
javac -version   # 应输出 javac 1.8.x
```

`make dev-backend` 已内置该检查，缺 `javac` 时会直接给出上面的安装命令而不继续。

### 6.2 代理环境下 Maven 必须显式配代理

本机若通过代理上网（如 `http_proxy=http://127.0.0.1:7890`），
**JVM 不会读取 `http_proxy` / `https_proxy` 环境变量**，Maven 会直连导致：

```
Non-resolvable parent POM ... Unknown host repo.maven.apache.org: Temporary failure in name resolution
```

仓库侧：`make dev-backend` 会自动把环境变量中的 `https_proxy`/`http_proxy` 转成
`-Dhttp.proxyHost / -Dhttps.proxyPort` 等 JVM 参数传给 Maven，无需手工干预；不设代理变量时自动留空。

全局（`~/.m2/settings.xml`）：同时配置了阿里云镜像与本机代理，使命令行直接跑 `mvn` 也可用。
若改为直连或代理未启动，把其中 `<proxies>` 的 `<active>` 改成 `false`。

### 6.3 依赖坐标踩坑（已修复，勿改回）

| 依赖 | 说明 |
| --- | --- |
| `com.mysql:mysql-connector-j` | Spring Boot 2.7.18 只管理这个新坐标；旧 `mysql:mysql-connector-java` 未被管理，省略版本会直接报 `dependencies.dependency.version is missing` |
| `org.flywaydb:flyway-mysql` | Flyway 8.x 把 MySQL 支持从 `flyway-core` 拆出，缺它会抛 `Unsupported Database: MySQL 8.0` |

两者版本均由父 POM 统一管理，不要在子 POM 里另写版本号，否则会与 Spring Boot 依赖管理脱节。

### 6.4 改了 MyBatis mapper XML 必须重启后端

`make dev-backend` 走 `mvn spring-boot:run`，只热加载编译产物，`src/main/resources/**/*.xml`
不在监听范围内。改完 `IndexMapper.xml` 等 mapper 不重启，接口仍执行旧 SQL——
表现为「代码已改、行为未变」，重建索引也会得到与代码不符的结果。

### 6.5 代理环境下调用本机接口要绕开代理

shell 里若导出过 `http_proxy`（尤其 SOCKS 代理），`curl` 会把 `127.0.0.1` 也走代理导致连接失败。
统一加 `--noproxy '*'`；Python 侧用 `requests.Session()` 后设 `trust_env = False`。

## 七、索引镜像：重建 / 激活 / 回滚

蓝绿索引（ES + Milvus + Neo4j）以「知识库 × `schemaVersion`」为单位发布，重建写蓝环境、
激活才切读别名。以下场景**必须重建**，只改代码不重建不会生效：

- 检索状态白名单变更（如把 DRAFT / UNCERTAIN 纳入可召回集合）
- 更换 Embedding provider 或维度
- 检索契约、字段结构变更，或索引漂移修复

```bash
# 1) 取 console token
TOKEN=$(curl -s --noproxy '*' -X POST http://127.0.0.1:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])')

# 2) 重建：逐个知识库执行，单库耗时随语料增长，超时放到分钟级
curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/api/knowledge/index/schema/6/rebuild?schemaVersion=vfix5"

# 3) 确认 state=READY 且三引擎条数一致后激活（RELEASE_ID 取自上一步返回的 releaseId）
curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/api/knowledge/index/schema/6/activate/$RELEASE_ID"

# 异常时回滚到上一个 ACTIVE 版本
curl -s --noproxy '*' -X POST -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/api/knowledge/index/schema/6/rollback/$RELEASE_ID"
```

核对激活结果：

```bash
docker exec kos-dev-mysql mysql -uknowledge -pknowledge123 knowledge_os -e \
  "select knowledge_base_id kb, schema_version, state, integrity_state,
          elasticsearch_count es, milvus_count mv, snapshot_count snap
   from t_index_schema_release where knowledge_base_id in (6,7,8,9,10)
   order by id desc limit 10;"
```

`state=ACTIVE` 且 `integrity_state=VERIFIED` 即成功，上一版本会被自动置为 `RETIRED`。
旧版本的目标索引 / 集合不再有别名指向，确认无用后可自行删除。

> **重建报 `COUNT_MISMATCH`**：权威快照 SQL（`IndexMapper.listAuthoritativeIndexSnapshot`）
> 与预期行数 SQL（`IndexMapper.countIndexableObjects`）的**状态集合必须逐字一致**。
> 只放宽快照而漏改 count，快照行数会大于预期，完整性校验直接判失败。

## 八、数据镜像：制作与发布

把开发机上「已灌好数据」的中间件打包成镜像，目标机拉起即自带指定知识库。
镜像以官方中间件为基线，tag 与 `VERSION` 保持一致（`1.1.0`）：

| 镜像 | 数据目录 | 说明 |
| --- | --- | --- |
| `mysql-data` | `/var/lib/mysql` | 知识库主数据（卡片 / 断言 / 文档 / 关系） |
| `es-data` | `/usr/share/elasticsearch/data` | BM25 与 typed 检索索引 |
| `neo4j-data` | `/data` | 权威图谱 |
| `milvus-data` | `/var/lib/milvus` | 向量索引 |
| `milvus-etcd-data` | `/etcd` | Milvus 元数据，**必须**与 milvus-data 同批替换 |
| `milvus-minio-data` | `/minio_data` | Milvus 对象存储，同上 |

### 8.1 制作步骤

```bash
# 1) 停应用与中间件，保证落盘一致（顺序：应用 → milvus → etcd/minio → 其它）
pkill -f "uvicorn app.main:app"; pkill -f "spring-boot:run"; pkill -f "com.jh.knowledgeos"
docker stop kos-dev-milvus kos-dev-etcd kos-dev-minio kos-dev-neo4j kos-dev-mysql kos-dev-es

# 2) 导出数据卷（用 tar 才能保留 uid/gid，docker cp 会把属主改成 root）
mkdir -p /root/data-export/build/{mysql,es,neo4j,milvus,etcd,minio}
for v in mysql es neo4j milvus etcd minio; do
  docker run --rm -v ai-knowledge-os_dev_${v}_data:/data \
    -v /root/data-export/build/$v:/out alpine tar -cf /out/$v.tar -C /data .
done
docker cp kos-dev-es:/usr/share/elasticsearch/config/. /root/data-export/build/es/config/
```

每个镜像用多阶段构建（`--chown` 保持原属主，tar 不进入最终镜像）：

```dockerfile
FROM alpine AS data
COPY es.tar /src.tar
RUN mkdir -p /data && tar -xpf /src.tar -C /data
FROM docker.elastic.co/elasticsearch/elasticsearch:9.5.3
USER root
COPY config/ /usr/share/elasticsearch/config/
COPY --from=data --chown=1000:0 /data /usr/share/elasticsearch/data
USER 1000:0
```

```bash
R=192.168.22.6:5000/knowledge-os
for v in mysql es neo4j milvus etcd minio; do
  docker build -t $R/${v}-data:1.1.0 /root/data-export/build/$v
  docker push  $R/${v}-data:1.1.0
done
```

### 8.2 使用约束（踩过的坑，别踩第二次）

1. **ES 必须挂数据卷跑**：数据放在镜像的 overlay 层时 Lucene 起不来
   （`AlreadyClosedException`）；挂命名卷后 Docker 会把镜像内数据带属主复制进卷，可正常启动。
2. **ES 的 `config` 要一起打包**：安全自动配置状态与 `elasticsearch.keystore` 在
   `/usr/share/elasticsearch/config`（容器层）。只搬数据目录会让新容器重跑自动配置、
   重置 `elastic` 密码，表现为镜像拉起后 401。
3. **Milvus 的三份数据（milvus / etcd / minio）必须同一时刻快照**：分开导出会出现
   元数据与对象不一致。
4. **不要清 ES 数据目录里的"残留"索引 UUID**：`.security-7` 等系统索引也在
   `indices/` 下，按业务索引列表求差集删除会连带删掉安全索引，导致认证失效。
5. MySQL 镜像可直接跑（不挂卷也可用），Neo4j 同理；但生产编排本来就有卷，保持一致即可。
