# 三场景数据集 · 配置与构建说明

本目录存放三个场景的**机器可读配置**（Schema、检索策略、评测用例），`dataset/<场景>/` 存放**文档数据**。

| 场景 | 数据目录 | Schema | 检索策略 | 知识库名 |
|---|---|---|---|---|
| 1 运维 | `dataset/ops-knowledge/`（18 篇） | `ops-knowledge.schema.json` | `ops-knowledge.profile.json` | 运维知识库 |
| 2 公司制度 | `dataset/company-policy/`（15 篇） | `company-policy.schema.json` | `company-policy.profile.json` | 公司制度知识库 |
| 3 工程协同 | `dataset/engineering-workspace/`（19 篇） | `engineering-workspace.schema.json` | `engineering-workspace.profile.json` | 工程协同知识库 |

## 一、一键构建

```bash
# 全部场景：建库 → 批量导入 → 应用 Schema → 创建检索策略
python3 scripts/build-scenario-kbs.py --token <管理员 token>

# 单个场景
python3 scripts/build-scenario-kbs.py --scenario ops --token <token>

# 用账号密码登录（或设置 KOS_ADMIN_PASSWORD 环境变量）
python3 scripts/build-scenario-kbs.py --username admin --password <pwd>

# 只做 Schema 与策略（文档已导入时）
python3 scripts/build-scenario-kbs.py --skip-upload

# 只提交导入不等待（摄入较慢时）
python3 scripts/build-scenario-kbs.py --no-wait
```

脚本产出 `build-report-<时间戳>.json`，记录知识库 id、批次号、类型与策略写入结果。

## 二、Schema 文件结构

```json
{
  "scenario": "ops-knowledge",
  "knowledgeBase": { "name": "运维知识库", "description": "…", "visibility": "PUBLIC", "permissionTags": "ops,sre,dba" },
  "knowledgeTypes": [ { "kind": "KNOWLEDGE_TYPE", "typeCode": "OPS_RULE", "label": "运维规范", "properties": { "properties": [ { "name": "docCode", "type": "STRING" } ] } } ],
  "entityTypes":    [ { "kind": "ENTITY_TYPE", "typeCode": "HOST", "properties": { … } } ],
  "relationTypes":  [ { "kind": "RELATION_TYPE", "typeCode": "COMMAND_ON_HOST", "properties": { "constraints": { "fromKinds": ["COMMAND"], "toKinds": ["HOST"] } } } ]
}
```

约定：

- `typeCode` 必须大写字母开头（后端校验 `^[A-Z][A-Z0-9_]{0,63}$`）；
- 属性 `type` 只能取 `STRING / TEXT / NUMBER / BOOLEAN / DATE / ENUM / REF`，`ENUM` 必须给 `values`；
- 关系类型的端点（`fromKinds` / `toKinds`）必须引用已登记的实体类型，否则发布时告警；
- Schema 通过 `POST /api/knowledge/schema/types?kb=<id>` 写入，`POST /api/knowledge/schema/publish?kb=<id>` 发布为版本快照。

## 三、检索策略文件结构

`*.profile.json` 即一份完整的 Query Policy（与 `GET /api/knowledge/settings/query-policy/defaults` 同构），按场景定制了三部分：

| 部分 | 场景化调整 |
|---|---|
| `route.rules` | 8 类问题（EXACT / PROCEDURE / FACT / CONFLICT / RELATION / MULTIHOP / CONDITION / GLOBAL）的关键词与通道顺序按场景重写，例如运维给命令与错误码最高优先级，制度给冲突类最高优先级 |
| `recall.channels` | 运维 BM25 1.30 / 卡片 1.25；制度 卡片 1.30 / 断言 1.25；工程 BM25 1.25 / 图谱 1.15 |
| `fusion.qualityWeights` | 运维重权威（0.20）；制度重权威 + 时效（0.25 / 0.20）；工程重时效（0.25） |
| `answer` | 各场景定制 `contextMaxChars`、证据门槛与拒答文案（引导补充工单号 / 咨询 HRBP / 补充 issue Key） |

策略通过 `POST /api/knowledge/settings/query-policy/profiles` 创建；控制台「系统配置 → 检索策略」可一键切换生效（`POST /profiles/{name}/activate`）。**同一时刻只有一个策略生效**，三个场景策略并存，按需切换对比。

## 四、生命周期与验证路径

| 阶段 | 落点 |
|---|---|
| 导入 | 批量接入（`/api/knowledge/documents/batch-upload`），可按批次查看每文件状态 |
| 解析与抽取 | 摄入流水线产出文档、证据片段、知识卡片与断言 |
| Schema 约束 | 场景 Schema 发布后，抽取按登记的实体/关系类型收敛 |
| 治理与裁决 | 各场景均设计了口径冲突（运维 09↔18、制度 03/05↔15、工程 07↔19），可在「知识裁决」中核对冲突断言 |
| 检索 | 「知识检索」页用场景策略提问，观察引用是否命中期望文档 |
| 评测 | 各场景 `retrieval-eval/检索质量评测用例集.md` 提供 L1–L4 四类用例 |

## 五、实测结果（参考）

在本仓库开发环境中按上述脚本执行一遍的结果（后端 8080 + AI 服务 12000，LLM qwen2.5:7b）：

| 场景 | 知识库 id | 文档 | 卡片 | 断言 | 实体 | 关系 | 识别冲突 | 健康分 |
|---|---|---|---|---|---|---|---|---|
| 运维知识库 | 10 | 18 | 173 | 173 | 532 | 256 | 60 | 40 |
| 公司制度 | 11 | 15 | 139 | 139 | 318 | 141 | 33 | 40 |
| 工程协同 | 12 | 19 | 221 | 217 | 601 | 288 | 96 | 40 |

- 导入：52 篇文档分 10 批提交，全部 `SUCCEEDED`，无失败文件；
- 抽取：卡片类型分布符合场景预期（运维 PROCEDURE 98 / FACT 39，制度 FACT 82 / RULE 16，工程 FACT 105 / PROBLEM_SOLUTION 64）；
- 治理：三库分别识别出 60 / 33 / 96 条冲突（即各场景刻意设计的口径差异），可在「知识治理 → 冲突裁决」中逐条处理；
- 检索验证（示例）：
  - 运维：`rm -rf / 属于什么等级的命令` → 命中 03/04/01，答 L1 + 拦截与双签，证据门控同时拦截了 4 项不达标结论；
  - 制度：`年假有多少天` → 同时给出旧口径 5 天与 2026-09-01 起 10 天，并说明以修订通知为准；
  - 工程：`PAY-2499 是什么问题，目前谁在处理` → 命中 07/19，给出死锁根因、经办人吴桐、报告人孙悦。

## 六、与既有数据集的关系

`dataset/bastion-audit/` 是此前已有的**堡垒主机运维审计**数据集（13 篇 + 评测集），聚焦安全审计；本次三个场景是通用运维、公司制度与工程协同，彼此互补，可共存于不同知识库。
