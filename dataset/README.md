# 场景数据集总览

本目录提供可直接导入知识库的**场景化 demo 数据集**，覆盖三类典型企业知识场景。每套数据集都按真实业务的体量与结构编写（含表格、命令、编号、冲突口径），并配套 Schema、检索策略与评测用例，可一键构建出完整的知识库生命周期。

| # | 场景 | 目录 | 文档数 | 知识库名 | 主要内容 |
|---|---|---|---|---|---|
| 1 | 运维知识库 | `ops-knowledge/` | 18 | 运维知识库 | 运维规范、操作手册、命令操作记录、故障与拦截事件、FAQ、月报复盘、口径通知 |
| 2 | 公司制度 | `company-policy/` | 15 | 公司制度知识库 | 员工手册、考勤休假、薪酬福利、报销差旅、绩效晋升、培训、信息安全、采购合同、奖惩、离职交接、IT 资产、FAQ、修订通知 |
| 3 | 工程协同 | `engineering-workspace/` | 19 | 工程协同知识库 | Jira 问题清单、Confluence 设计文档、OA/ITSM 工单、日报周报、岗位职责、迭代与季度计划、绩效评估、项目复盘 |
| — | 堡垒主机运维审计 | `bastion-audit/` | 13 | （既有数据集） | 堡垒主机账号授权、高危命令、审计溯源 SOP、会话审计记录、FAQ、月报 |

三套场景共用同一虚构主体「蓝湖科技」，工号、主机名、工单号互通，便于跨知识库验证实体关联。

## 构建方式

```bash
# 全部场景：建库 → 批量导入 → 应用 Schema → 创建检索策略
python3 scripts/build-scenario-kbs.py --token <管理员 token>

# 单场景 / 仅配置 / 不等候摄入
python3 scripts/build-scenario-kbs.py --scenario ops --token <token>
python3 scripts/build-scenario-kbs.py --skip-upload          # 只应用 Schema 与策略
python3 scripts/build-scenario-kbs.py --no-wait              # 提交批次后立即返回
```

脚本与配置说明见 `_scenarios/README.md`；每个场景目录下的 `README.md` 给出文件清单、设计意图、Schema 与策略要点；`retrieval-eval/检索质量评测用例集.md` 给出 L1–L4 四类评测用例。

## 目录约定

```text
dataset/
├─ README.md                      本文件：场景总览
├─ _scenarios/                    机器可读配置（不导入知识库）
│  ├─ README.md                   构建脚本与配置说明
│  ├─ <场景>.schema.json          知识类型 / 实体类型 / 关系类型
│  ├─ <场景>.profile.json         场景检索策略（Query Policy）
│  └─ build-report-<ts>.json      构建报告（脚本产出）
├─ ops-knowledge/                 场景 1 数据
│  ├─ README.md
│  ├─ *.md                        文档数据（导入对象）
│  └─ retrieval-eval/             评测用例集
├─ company-policy/                场景 2 数据
├─ engineering-workspace/         场景 3 数据
└─ bastion-audit/                 既有堡垒主机审计数据集
```

## 设计原则

1. **体量贴近真实**：单篇 1,500–3,000 字，含表格与代码块，模拟真实文档而非示例片段；
2. **具有场景典型性**：每套都包含该场景特有的对象（运维的命令与工单、制度的额度与天数、研发的 issue 与迭代）；
3. **刻意保留冲突**：每套都设计了口径不一致（如运维 09↔18、制度 03/05↔15、工程 07↔19），用于验证版本化断言与冲突裁决；
4. **同一事实多源**：关键事件在多个文档中出现，用于验证证据聚合与去重；
5. **可评测**：配套四类评测用例，覆盖精确召回、语义改写、冲突多跳与拒答边界。
