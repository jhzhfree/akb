# AI Knowledge OS 产品安装包（docker compose 版）

本包是可交付单元：解开即可安装，不需要编译环境。目标机只需满足下面的前提条件。

> 包内 `monitoring/` 是 Prometheus 与 Grafana 的抓取规则、告警与面板，以 bind 挂载方式
> 被容器读取，**不能删除**。少了它容器照样启动，只是没有监控数据，问题要到排查时才发现。

## 一、前提条件

| 项 | 要求 |
| --- | --- |
| 操作系统 | Linux x86_64 |
| Docker | 20.10 及以上 |
| docker compose | v2 插件（`docker compose version` 有输出即可） |
| 内存 | 建议 16 GB 以上；低于 8 GB 时 `install.sh` 会告警（ES 2g + Milvus + Neo4j 1g 起步） |
| 磁盘 | 建议 50 GB 以上可用空间（镜像 + 数据卷） |
| 网络 | 离线包不需要外网；在线包需要能访问镜像仓库 |

## 二、安装

```bash
tar -xzf ai-knowledge-os-<VERSION>.tar.gz
cd ai-knowledge-os-<VERSION>
./install.sh
```

`install.sh` 依次做四件事：预检环境（docker / 内存 / 磁盘）→ 加载 `images/` 下的离线镜像
（在线包无此目录，跳过）→ 生成 `.env` → 检查待办项与编排文件。

**上线前必须修改 `.env` 中所有 `CHANGE_ME` 占位值**，安装脚本会把它们逐行列出来：

```ini
MYSQL_PASSWORD=CHANGE_ME
REDIS_PASSWORD=CHANGE_ME
ELASTICSEARCH_INITIAL_ADMIN_PASSWORD=CHANGE_ME   # ES 初始密码，仅在首次启动生效
ELASTICSEARCH_PASSWORD=CHANGE_ME                 # 需与上一项一致
NEO4J_PASSWORD=CHANGE_ME
EXTERNAL_EMBED_TOKEN_SECRET=CHANGE_ME
GRAFANA_ADMIN_PASSWORD=CHANGE_ME
```

并确认模型服务地址：

```ini
LLM_BASE_URL=http://<模型服务地址>:11434
LLM_MODEL=qwen2.5:7b
EMBEDDING_MODEL=nomic-embed-text
EMBEDDING_DIM=768
```

改完启动：

```bash
./start.sh            # 或 ./start.sh --wait，等全部健康检查通过后再返回
./status.sh           # 确认状态
```

## 三、日常运维

| 操作 | 命令 | 说明 |
| --- | --- | --- |
| 启动 | `./start.sh` | 保留数据卷，重新创建容器 |
| 启动并等待 | `./start.sh --wait` | 等所有健康检查通过才返回，适合脚本里调用 |
| 停止 | `./stop.sh` | 停止并移除容器，**数据卷保留** |
| 检查 | `./status.sh` | 容器状态、健康端点、数据卷、磁盘占用 |
| 日志 | `./logs.sh backend` | 跟随后端日志；不带参数跟随全部 |
| 重建单个服务 | `./start.sh backend` | 改了某个服务的配置后重建它 |
| 重启 | `./stop.sh && ./start.sh` | 改 `.env` 后需要走这一步 |
| 卸载 | `./uninstall.sh` | 停止并**删除数据卷**，不可恢复，需输入 `yes` |

## 四、默认访问地址

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| 前端 | `http://<HOST>:8088` | `.env` 里 `PUBLIC_HTTP_PORT` |
| 后端 API | `http://<HOST>:8080/swagger-ui.html` | `BACKEND_HTTP_PORT` |
| Prometheus | `http://<HOST>:9090` | `PROMETHEUS_HTTP_PORT` |
| Grafana | `http://<HOST>:3000` | `GRAFANA_HTTP_PORT`，账号见 `.env` 的 `GRAFANA_ADMIN_*` |

## 五、目录说明

```
.
├── docker-compose.yml   # 发布编排（镜像名由 image: 指定，不在目标机构建）
├── release.env          # 版本号与镜像前缀，由打包脚本写入，勿手工改
├── VERSION              # 纯版本号，便于外部脚本读取
├── .env / .env.example  # 运行配置 / 模板
├── config/              # 出厂默认配置（场景模板、衍生规则），与容器内 /app/config 对应
├── monitoring/          # Prometheus 抓取规则、告警、Grafana 面板（bind 挂载，勿删）
├── images/              # 离线镜像包，install.sh 自动 docker load（在线包无此目录）
├── install.sh start.sh stop.sh status.sh logs.sh uninstall.sh
└── README.md
```

## 六、数据与升级

- 数据全部位于命名卷（`mysql_data`、`neo4j_data`、`milvus_data`、`elasticsearch_data`、
  `ai_data`、`backend_config` 等）。`./stop.sh` 不会删除，只有 `./uninstall.sh` 会。
- **升级**：解开新版本包到新目录 → `./install.sh` → `./start.sh`。数据卷按 compose 项目名
  命名，新目录若项目名不同会新建空卷。**升级前先备份**，或在同一目录内替换文件以保持项目名。
- **改默认配置**：`config/` 下的 JSON 是出厂副本；容器内 `/app/config` 由 backend 首次启动
  播种。要改生效配置请改卷里的文件后重启 backend，或在系统配置页用「系统初始化」恢复出厂。

## 七、常见问题

**端口被占用**：`install.sh` 会点名冲突端口。改 `.env` 中对应的 `*_HTTP_PORT` 再启动，
或停掉占用方。本机已有 8080/9090 服务时最常见。

**CHANGE_ME 一直提示**：脚本只检查赋值行。改完仍提示，说明还有没改到的项，按列出的行号找。

**容器起了但没监控数据**：检查包内 `monitoring/` 是否随包解压，以及
`docker compose config` 里 prometheus 的挂载路径是否解析到了它。

**ai-service 调用模型失败**：`.env` 里用 `LLM_BASE_URL` 与 `LLM_MODEL`。模型服务在容器宿主
本机时填 `http://host.docker.internal:11434`（Linux 需要 compose 里的
`extra_hosts: "host.docker.internal:host-gateway"`）。

**检索无结果**：`EMBEDDING_DIM` 必须与模型输出维度一致，`nomic-embed-text` 是 `768`。
换 embedding provider 后需重建索引。

**首次启动很慢**：ES、Milvus、Neo4j 首次初始化需要数分钟，`./status.sh` 里的「尚未就绪」
段落会列出还在 starting 的服务，属正常；持续 unhealthy 就看 `./logs.sh <服务>`。

**离线包启动报镜像缺失**：确认 `install.sh` 输出了「加载镜像包」且数量与包内 `images/`
一致；镜像与版本严格对应，不要混用不同版本的离线包。
