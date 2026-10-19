# 默认内置知识数据镜像（五类场景）

把 `dataset/enterprise_kb_validation_5sets` 导入并验证通过后的五个知识库固化成可装载的数据镜像，用于新环境一键拥有"有内容"的演示/回归数据。

## 内容

| 知识库 id | 名称 | 场景 | 文档 |
|---|---|---|---|
| 6 | 01_综合知识库 | GENERAL | 4 |
| 7 | 02_运维知识库 | OPS | 4 |
| 8 | 03_制度条款知识库 | POLICY | 4 |
| 9 | 04_公司业务人员关系知识库 | BUSINESS | 4 |
| 10 | 05_漏洞库知识库 | VULN | 4 |

组成：

- `knowledge_data.sql`：MySQL 中与这五个库相关的权威数据（源、版本、文档、内容单元、卡片、断言、证据、实体与关系、冲突与裁决、分块策略、场景配置、摄入运行与索引发布记录）。审计与运行痕迹（查询 trace、调用审计、告警、归档 outbox）不导出——它们属于运行过程，不该随演示数据复制。
- `source-files/`：上传的 20 份 DOCX，按 `后端 data/source-files/<kb>/<source_code>/` 的目录结构保存，与 `t_source.storage_path` 一一对应。
- `load.sh`：装载脚本（校验 id 空闲 → 导数据 → 还原源文件 → 提示重建索引）。

## 装载

```bash
cd deploy/seed/enterprise-kb-demo
MYSQL_CONTAINER=kos-dev-mysql ./load.sh
```

前置条件：

1. 库表已创建（启动一次 backend，Flyway 会建表）；
2. id 6–10 未被占用（脚本会检查，占用则中止）；
3. backend 最好处于停止状态，避免与运行中的任务抢写。

## 装完还要做一件事：重建索引

Elasticsearch / Milvus / Neo4j 里的是可重建视图，不随镜像复制。装载后按 `load.sh` 末尾的提示对每个库执行一次蓝绿重建 + 激活，否则检索召回为空。

## 回归验证

```bash
python3 dataset/enterprise_kb_validation_5sets/validate.py   # 5 条验收用例
make validate-kb                                             # 等价入口
make validate-kb only=漏洞库                                  # 只跑一个库
BASE=http://192.168.22.6:8080 make validate-kb                # 打目标机
```

判定三项：期望证据是否命中、答案是否被引用校验拦截、期望行为关键词是否出现。
被引用校验拦掉（答案里出现「已阻止输出 / 现有知识不足以」）直接判 FAIL，
因为此时用户拿不到结论。退出码 0 表示全通过。

期望：5/5 命中期望证据。重点看三处：

- 制度库：按 V2.0（30000 元门槛）回答，并说明 V1.0 只用于历史追溯；
- 人员关系库：陈远负责技术交付、王岚负责客户验收，引用 REL-001 与 ROLE-001；
- 漏洞库：公告 → 资产 → 修复 → 复测四份文档的跨文档链路。

## 重新生成镜像

数据集重新导入或产品逻辑变更后：

```bash
python3 scripts/export_default_kb_image.py 6 7 8 9 10
```

## 注意

数据全部为合成模拟数据，指标、人员、资产、漏洞编号均为测试设定，不能作为真实运维或合规依据。
