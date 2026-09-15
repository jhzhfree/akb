# v1.1 环境配置说明

本文说明 v1.1 分支的容器环境划分、启动方式与配置项含义。

## 一、环境划分

| 编排文件 | 用途 | 说明 |
| --- | --- | --- |
| `docker-compose.yml` | 全栈默认环境 | 一次性拉起 MySQL/Redis/ES/Milvus/Neo4j/应用/监控，资源占用高 |
| `docker-compose.dev.yml` | **v1.1 开发 / 联调环境** | 基础中间件默认启动，应用与监控按 profile 按需开启 |
| `deploy/docker-compose.prod.yml` | 生产部署 | 与 `docker-compose.dev.yml` 共用 `.env` 体系 |

`docker-compose.dev.yml` 的 profile 划分：

| profile | 包含服务 | 启动方式 |
| --- | --- | --- |
| 默认（无 profile） | mysql、redis、neo4j | `make dev-up` |
| `app` | ai-service、backend、frontend | `make dev-app-up` |
| `dual-retrieval` | elasticsearch、milvus（含 etcd/minio） | `--profile dual-retrieval` |
| `monitoring` | prometheus(9092)、grafana(3001) | `--profile monitoring` |

## 二、快速开始

完整命令清单见 [COMMANDS.md](./COMMANDS.md)（开发裸跑、容器、发布打包、产品启停）。

```bash
cp .env.dev.example .env.dev     # 或直接 make dev-env
make dev-up                      # 仅启动 mysql + redis + neo4j
make dev-ps                      # 查看健康状态
make dev-app-up                  # 追加构建并启动应用栈
make dev-logs                    # 跟随日志
make dev-down                    # 停止（保留数据卷）
make dev-clean                   # 停止并清除数据卷
```

访问地址：

| 服务 | 地址 | 凭据 |
| --- | --- | --- |
| MySQL | `localhost:3306` | `knowledge / knowledge123`，库 `knowledge_os` |
| Redis | `localhost:6379` | 默认无密码 |
| Neo4j Browser | `http://localhost:7474` | `neo4j / knowledge123` |
| Backend | `http://localhost:8080` | Swagger：`/swagger-ui.html` |
| AI Service | `http://localhost:12000` | `/health` |
| Frontend | `http://localhost:8088` | — |
| Grafana | `http://localhost:3001` | `admin / admin123` |
| Prometheus | `http://localhost:9092` | — |

## 三、模型服务配置

模型使用外部 Ollama：**`http://192.168.22.6:11434`**

```ini
LLM_PROVIDER=ollama
LLM_BASE_URL=http://192.168.22.6:11434
LLM_MODEL=qwen2.5:7b
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text
EMBEDDING_DIM=768
```

要点：

1. **LLM 与 Embedding 共用 `LLM_BASE_URL`**。`OllamaEmbeddingProvider` 读取的是
   `settings.llm_base_url`，不存在独立的 embedding 地址配置项。
2. **`EMBEDDING_DIM` 必须为 768**。`nomic-embed-text` 输出 768 维；若改回
   `EMBEDDING_PROVIDER=hash`（零外部依赖兜底），则需同步改回 `384`，否则本地向量
   检索会因维度不匹配直接返回空结果。
3. 该地址上已具备的模型：`qwen2.5:7b`、`llama3.2`、`deepseek-R1`、`deepseek-ocr`、
   `nomic-embed-text`。切换 `LLM_MODEL` 时须确保模型已拉取，否则生成式回答会失败。
4. 容器内访问该地址走默认 bridge + 宿主 NAT，**不需要** `host.docker.internal` 映射
   （该映射仅当模型服务跑在 Docker 宿主本机的 `127.0.0.1` 时才需要）。

## 四、检索策略

开发环境默认关闭双路检索，由 `RETRIEVAL_MODE=auto` 自动降级：

- 词法通道：本地 BM25 快照（进程内缓存，按 index generation 失效）
- 向量通道：本地 NumPy 矩阵检索
- 卡片 / 论断通道：本地类型化词法召回
- 图通道：Neo4j（不可用时降级为 CLAIM 词法召回）

需要验证双路检索时再开启：

```bash
docker compose --env-file .env.dev -f docker-compose.dev.yml \
  --profile app --profile dual-retrieval up -d
```

并同步在 `.env.dev` 中设置 `ELASTICSEARCH_ENABLED=true`、`MILVUS_ENABLED=true`。
**注意**：切换 provider 后已入库向量的维度会变，需重建索引后再对比召回效果。

## 五、端口规划（已规避宿主占用）

宿主 `3000`、`9090` 已被其他服务占用，开发环境映射为：

| 服务 | 容器内端口 | 宿主端口 |
| --- | --- | --- |
| Grafana | 3000 | **3001** |
| Prometheus | 9090 | **9092** |
| MySQL / Redis / Neo4j / Backend / AI / Frontend | — | 保持默认 |

## 六、数据持久化

全部使用命名卷，前缀 `dev_`，与全栈环境的卷隔离，互不覆盖：

`dev_mysql_data`、`dev_redis_data`、`dev_neo4j_data`、`dev_neo4j_logs`、`dev_ai_data`、
`dev_es_data`、`dev_etcd_data`、`dev_minio_data`、`dev_milvus_data`、
`dev_prometheus_data`、`dev_grafana_data`

## 七、已知环境约束

**docker daemon 需配置代理才能拉取镜像。** 当前 WSL 环境外网仅能通过
`http://127.0.0.1:7890` 访问，daemon 侧 DNS 与直连 443 均不可用。

已写入 systemd 覆盖片段 `/etc/systemd/system/docker.service.d/http-proxy.conf`：

```ini
[Service]
Environment="HTTP_PROXY=http://127.0.0.1:7890"
Environment="HTTPS_PROXY=http://127.0.0.1:7890"
Environment="NO_PROXY=localhost,127.0.0.1,::1,192.168.0.0/16,172.16.0.0/12,10.0.0.0/8"
```

写入后需执行以下命令才会生效（应用镜像构建同样依赖此配置）：

```bash
sudo systemctl daemon-reload && sudo systemctl restart docker
```

在此之前，镜像需通过代理侧的 `crane` 拉取后 `docker load` 装载：

```bash
crane pull mysql:8.0.36 /tmp/mysql.tar && docker load -i /tmp/mysql.tar
```
