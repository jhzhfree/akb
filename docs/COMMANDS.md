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

```bash
make dev-install   # 一次性：创建 ai-service/.venv、安装 python 与 npm 依赖
make dev-ai        # uvicorn --reload，:12000
make dev-backend   # mvn spring-boot:run，:8080（需本地 Maven 3.8+ 与 JDK8+）
make dev-frontend  # vite dev，:5173
make dev-all       # 并行启动以上三个
```

模型服务默认指向 `http://192.168.22.6:11434`，可用变量覆盖：

```bash
make dev-ai OLLAMA_URL=http://192.168.22.6:11434 LLM_MODEL=qwen2.5:7b
```

本地裸跑的 `ai-service` 默认关闭 ES/Milvus，走本地 BM25 + 本地向量 + Neo4j 图召回。

> 本地无 Maven 时，`make dev-backend` 会直接报错退出，此时改用
> `make dev-backend-up` 以容器方式启动后端。

### 1.4 访问地址

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
