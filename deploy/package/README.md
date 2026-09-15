# AI Knowledge OS 离线安装包

## 一、安装

```bash
tar -xzf ai-knowledge-os-<VERSION>.tar.gz
cd ai-knowledge-os-<VERSION>
./install.sh          # 预检环境 + 加载镜像 + 生成 .env
```

安装脚本会在当前目录生成 `.env`（源自 `.env.example`）。**上线前必须修改**其中所有
`CHANGE_ME` 占位值，并确认模型服务地址：

```ini
LLM_BASE_URL=http://<模型服务地址>:11434
LLM_MODEL=qwen2.5:7b
EMBEDDING_MODEL=nomic-embed-text
EMBEDDING_DIM=768
```

## 二、启停

```bash
./start.sh            # 启动全部服务
./status.sh           # 查看容器状态与健康端点
./logs.sh backend     # 跟随单个服务日志；不带参数则跟随全部
./stop.sh             # 停止服务（保留数据卷）
./uninstall.sh        # 停止并删除数据卷（不可恢复）
```

## 三、默认访问地址

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| 前端 | `http://<HOST>:8088` | 可在 `.env` 用 `PUBLIC_HTTP_PORT` 调整 |
| 后端 API | `http://<HOST>:8080/swagger-ui.html` | `BACKEND_HTTP_PORT` |
| Prometheus | `http://<HOST>:9090` | `PROMETHEUS_HTTP_PORT` |
| Grafana | `http://<HOST>:3000` | `GRAFANA_HTTP_PORT`，默认 `admin/admin123` |

## 四、目录说明

```
.
├── docker-compose.yml   # 发布编排（固定项目名 knowledge-os）
├── release.env          # 版本号与镜像前缀，由打包脚本写入
├── .env / .env.example  # 运行配置
├── images/              # 离线镜像包，install.sh 自动 docker load
├── monitoring/          # Prometheus 抓取规则、告警、Grafana 面板
├── install.sh start.sh stop.sh status.sh logs.sh uninstall.sh
└── README.md
```

## 五、数据与升级

- 数据全部存放在命名卷（`mysql_data`、`neo4j_data`、`milvus_data`、`elasticsearch_data` 等），
  项目名固定为 `knowledge-os`，`./stop.sh` 不会丢数据。
- 升级：解压新版本包，在新目录执行 `./install.sh` 后 `./start.sh`；数据卷同名不会被重建，
  请先按需备份。
- 端口冲突：修改 `.env` 中的 `*_HTTP_PORT` 后重新 `./start.sh`。

## 六、常见问题

**ai-service 启动后调用模型失败**：检查 `.env` 中是否为 `LLM_BASE_URL` 与 `LLM_MODEL`
（不是 `OLLAMA_BASE_URL`）。若模型服务跑在容器宿主本机，填 `http://host.docker.internal:11434`。

**检索无结果**：`EMBEDDING_DIM` 必须与模型输出维度一致，`nomic-embed-text` 为 `768`。
切换 embedding provider 后需重建索引。
