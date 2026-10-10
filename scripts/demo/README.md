# 演示环境与演示数据

本目录的脚本用于把一套**开箱可演示**的环境固化下来：清库 → 建账号 → 按场景模板建知识库 → 导出快照 → 打进镜像。
部署后无需再跑 AI 流水线，登录即可看到有内容、可检索、可追溯的平台。

## 一、演示账号（统一口令 `1qaz@WSX`）

| 用户名 | 角色 | 可见范围 |
|---|---|---|
| `admin` | ADMIN 系统管理员 | 全部功能，含用户与组织、角色权限、模型、索引、任务告警、审计 |
| `kb-admin` | KNOWLEDGE_ADMIN 知识系统管理员 | 知识域全功能：生成（含连接器、外部知识、裁决、衍生）+ 检索（含策略、评测） |
| `kb-operator` | KNOWLEDGE_OPERATOR 知识运营员 | 简化知识生成（导入、查看对象）+ 只读检索；无裁决、无衍生 |
| `kb-auditor` | KNOWLEDGE_AUDITOR 知识审核员 | 知识运营员 + 知识裁决 + 知识测评 |
| `kb-viewer` | KNOWLEDGE_VIEWER 知识浏览员 | 只读：知识概览（总览 / 资产 / 版本 / 质量）与知识检索 |

`admin` 之外四个账号覆盖全部非管理员角色，用于现场演示「角色决定能不能做、视图决定看到多少」。

## 二、演示知识库（场景模板）

| 知识库 | 场景 | 文档数 | 内容 |
|---|---|---|---|
| 运维知识库 | 运维 | 19 | 运维规范、操作手册、命令操作记录、故障与拦截事件、FAQ、月报复盘 |
| 公司制度知识库 | 制度 | 16 | 员工手册、考勤休假、薪酬福利、报销差旅、绩效晋升、信息安全、采购合同 |
| 工程协同知识库 | 研发协同 | 19 | Jira 问题、Confluence 设计文档、工单、日报周报、迭代计划、项目复盘 |

三套场景共用虚构主体「蓝湖科技」，工号、主机名、工单号互通，可跨知识库验证实体关联。
每个场景同时下发 Schema（知识/实体/关系类型）与场景检索策略，并导入评测用例集。

## 三、脚本

| 脚本 | 作用 |
|---|---|
| `reset-storages.sh` | 清空 MySQL 业务表、Elasticsearch 索引、Milvus 集合、Neo4j 图、Redis、摄入原件；重建默认知识库与管理员 |
| `seed-users.py` | 用管理员登录并创建四种角色的演示账号（已存在则重置口令） |
| `export-snapshot.py` | 把当前环境导出为快照：`backend/demo-seed/` |
| `restore.sh` | 在镜像内回放快照（bash + curl + mysql 客户端，无需 Python） |

典型流程：

```bash
# 1) 清库
scripts/demo/reset-storages.sh
# 2) 建账号
python3 scripts/demo/seed-users.py
# 3) 建场景知识库（走公开 API，需后端与 AI 服务可用）
python3 scripts/build-scenario-kbs.py --username admin --password '1qaz@WSX' --with-eval
# 4) 导出快照
python3 scripts/demo/export-snapshot.py
# 5) 构建镜像（快照随 backend 镜像打包）
VERSION=1.1.0 REGISTRY=192.168.22.6:5000/ make release-build
```

## 四、镜像内的自动恢复

`backend/Dockerfile` 把 `backend/demo-seed/`（快照）与 `entrypoint.sh` 打进镜像：

- `DEMO_SEED=auto`（默认）：容器启动时若 `t_document` 为空，自动回放快照；已有数据则不动；
- `DEMO_SEED=true`：每次启动都覆盖为演示数据；
- `DEMO_SEED=false`：不恢复，保持空系统。

恢复顺序：清空 → MySQL 权威状态 → Elasticsearch 全文索引 → Milvus 向量 → Neo4j 图 → 摄入原件。
任一步失败只打印日志，**不会阻断应用启动**；恢复所需连接参数由编排文件注入（见 `docker-compose.yml` 与
`deploy/docker-compose.release.yml`、`deploy/docker-compose.prod.yml` 的 backend 段）。

恢复结束会打印一行自检结果，例如：

```
[demo-seed] 恢复结果：文档 55 篇 / 卡片 519 张 / ES 2142 条 / 向量 1588 条 / 图节点 2928 个
[demo-seed] 演示数据恢复完成。
```

`向量 0 条` 或 `以下依赖本次不可用：xxx` 说明对应存储没连上，应用照常起来、但检索会缺数据。

### 远程部署看不到演示数据时的核对顺序

1. **镜像是否真的是新的。** tag 复用（`1.1.0`）时，远端若命中本地缓存或 `imagePullPolicy: IfNotPresent`
   就不会更新：
   ```bash
   docker pull 192.168.22.6:5000/knowledge-os/backend:1.1.0
   docker inspect --format '{{index .RepoDigests 0}}' 192.168.22.6:5000/knowledge-os/backend:1.1.0
   ```
2. **看容器日志里的 `[demo-seed]` 行。** 有 `[entrypoint] 演示数据恢复未完成` 时会紧跟排查提示；
   依赖不可用会打印 `xxx 不可用（最后一次 HTTP 状态：401/000）`。
3. **连接变量是否注入。** 恢复脚本读 `MYSQL_*`、`ES_URL/ES_USER/ES_PASSWORD`、`MILVUS_URL`、
   `NEO4J_URL/NEO4J_USER/NEO4J_PASSWORD`；也接受平台变量 `DB_USERNAME/DB_PASSWORD`、
   `ELASTICSEARCH_URL/ELASTICSEARCH_USERNAME/ELASTICSEARCH_PASSWORD`。编排文件没传且 `.env`
   里也没有时，会退回 `http://elasticsearch:9200` + 空密码，在 https + 认证的部署上必然失败。
4. **`auto` 模式只在业务表为空时灌入。** 环境里已有文档就跳过；要强制覆盖用 `DEMO_SEED=true`。
5. **`index-worker` 与 `backend` 同镜像**，必须给它 `DEMO_SEED=false`，否则两个容器会各自回放一遍。

## 五、快照内容

```
backend/demo-seed/
├─ manifest.json                 清单：导出时间、各库条目数
├─ mysql/knowledge_os.sql        业务表数据（排除 flyway 历史，表结构仍由 Flyway 保证）
├─ es/<index>.mapping.json       Elasticsearch 映射
├─ es/<index>.bulk-*.ndjson      全文索引文档（bulk 格式分片）
├─ milvus/<collection>/          向量集合 schema 与分片 insert 请求体
├─ neo4j/nodes-<Label>.json      图节点（按标签分片，MERGE 幂等）
├─ neo4j/rels-<Type>.json        图关系（按类型分片）
└─ source-files.tar.gz           摄入原件（文档预览用）
```
