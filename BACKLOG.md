# 待办工作列表

V1.1 闭环验证（多跳派生 + 图谱召回 + 结论来源）之后遗留的两项工作。
记录日期：2026-09-24 · 分支 `v1.4` · 验证环境：本机 dev（MySQL / Neo4j / ai-service / backend / frontend）

两项都不是"功能没做"，而是"做出来了但不稳定/看不到"，按影响排序：抽取稳定性决定知识能不能被推导出新结论，门控拦截率决定推导出来的结论用户看不看得到。

---

## 待办 1 · 抽取粒度与类型不稳定（P1）

### 现象

同一份材料（`/tmp/demo_docs`：安全公告 + 产品 BOM + 项目部署）连续导入三次，抽出的实体类型与粒度逐次退化：

| 知识库 | 实体数 | CVE-2026-1234 | OpenSSL | SecureGateway | 客户/项目/组织 | 派生候选 |
|---|---|---|---|---|---|---|
| kb=7 | 21 | `VULNERABILITY` | `PRODUCT`（另有 `OpenSSL 3.0.12/3.0.13`） | `PRODUCT` | 齐全 | **1 条**（"CVE-2026-1234 可能影响 上海银行"，confidence 0.4374） |
| kb=8 | 19 | `VULNERABILITY` | `TERM`（退化） | `PRODUCT` | 齐全 | 0 条 |
| kb=9 | 24 | `TERM`（退化） | `TERM` | `TERM` | 齐全 | 0 条 |

两个独立问题叠在一起：

1. **类型退化**：`VULNERABILITY/PRODUCT` 退化成 `TERM`。关系类型在 kb=9 仍是 `AFFECTS/PART_OF`，说明是实体类型这一路没抽出来。
2. **粒度摇摆**：`OpenSSL` 与 `OpenSSL 3.0.12`、`OpenSSL 3.0.13` 时而各自成实体、时而合并；有时甚至把区间 `OpenSSL 3.0.0 至 3.0.12` 抽成一个实体。

### 为什么必须处理

派生规则是按「关系类型 + 跳数」精确匹配的。`IMPACT_SCOPE` 走
`AFFECTS>DEPENDS_ON:REV>DEPLOYED_IN:REV>BELONGS_TO`，只要中间任何一跳因粒度分叉而断开（例如
`SecureGateway` 依赖的是 `OpenSSL 3.0.12`，而漏洞 `AFFECTS` 的是 `OpenSSL`），四跳路径就不成立，候选直接归零 —— kb=7 有 1 条、kb=8/9 为 0 的差别就在这里。

对使用者的后果：同一份资料，昨天能推出"这个漏洞影响到哪个客户"，今天推不出来，且没有任何报错，只是"没有候选"。

### 相关代码

- 抽取：`ai-service/app/services/extractor.py`（LLM 抽取、空结果重试、共现兜底）
- 类型与关系词汇：`ai-service/app/core/knowledge_schema.py`
- 实体归一：`ai-service/app/services/entity_resolver.py`

### 建议动作

1. **版本与别名归一**：`OpenSSL 3.0.12` 归一到产品主实体 `OpenSSL`，版本号作为属性；`entity_resolver` 增加版本号识别规则（`<产品> <主.次.补丁>`）。
2. **类型强约束**：抽取 prompt 中实体类型限定为枚举，返回后校验；`type` 不在词汇表内或落到 `TERM` 的比例超过阈值时，用更精简的 prompt 重试一次（复用 `extractor` 里已有的 strict 重试通道）。
3. **可观测**：每次导入记录实体类型分布与 `TERM` 占比、以及"每条预设派生规则的链连通率"，导入详情里能看到，而不是只能在数据库里数。

### 验收标准

- 同一份材料连续导入 3 次，实体类型分布一致（`TERM` 占比波动 < 10%），`OpenSSL` 系列合并为 1 个产品实体。
- 3 次导入中 `IMPACT_SCOPE` 均产出 ≥ 1 条候选，不再出现 0 条。
- 新增断言测试：抽取结果中实体类型必须落在词汇表内，否则重试。

---

## 待办 2 · 答案门控拦截率过高（P2）

### 现象

同一个知识库（kb=9）、同一类问题，两种拦截形态都出现过：

- **部分拦截**：答案给出部分结论，尾部附"另有 2 项结论未通过证据完整性与忠实度校验，已阻止输出"。
- **整体拦截**（本次复现，queryId `Q_acb162c6c9d34bafb3ae015e32a6e4b7`）：
  `gateStatus=BLOCKED`、`evidenceGateStatus=BLOCKED`、`structuredGateStatus=PASS`、`claimCount=0`，
  用户看到的整段答案是"现有知识不足以形成全部可验证的结论；未通过证据完整性与忠实度校验的内容已阻止输出。"

后者最伤：图谱已经走通（graph 通道 18 条、card 5 条）、结论也生成了，但一句都没留下。

### 拦截链条

```
ai-service/app/services/answer_evidence_gate.py:30
    ok = bool(valid_refs) and exact and score >= faithfulness_min
        valid_refs  → 句末 [证据N] 的 N 必须在证据列表内
        exact       → 该证据必须同时带 evidenceCode 与 evidenceHash
        score       → 句子与证据的词元重合率（中文二元组），阈值默认 0.03
ai-service/app/services/answer_qualification.py:73-114
    证据门控优先：未 PASS 时只保留 supported 且非 CONTRADICTED 的句子
    保留 0 句 → BLOCKED（整段替换为兜底句）；保留部分 → PARTIAL + 提示
```

### 关键判断：不该只调阈值

`faithfulnessMin` 默认已经是 **0.03**（`ai-service/app/core/config.py:41` `ANSWER_FAITHFULNESS_MIN`，
策略字段 `ai-service/app/core/query_policy.py:301`），再往下调几乎只能放行噪声。真正的卡点是另外两个：

1. **`exact`**：引用必须带 `evidenceCode` + `evidenceHash`。图谱路径的引用是向支撑文档"借"的
   （`app/services/retrieval/hybrid.py` `_path_support` / `_as_graph_candidate`），找不到支撑文档的路径
   两个字段都为空 → 这类引用一律不算数，图谱结论因此系统性被拦。
2. **逐句强校验**：`split_claims` 切出来的每一句都要自带引用，归纳句、过渡句也算，命中即整句丢弃。

### 建议动作

1. **先量化再调参**：在门控结果里分别统计"无引用 / 引用非精确 / 忠实度不足"三类拦截的句数与占比（当前只有 `gateStatus` 与 `claimCount`，无法判断该动哪里）。
2. **给图谱路径可验证引用**：无支撑文档时，用路径本身（实体码 + 关系类型序列）生成稳定 `evidenceCode`
   与其哈希，让图谱结论不必借用文档也能通过 `exact`（来源标注仍是"图谱遍历"）。
3. **分层判据**：原文检索结论沿用现有严格判据；图谱遍历 / 规则派生结论改用"路径可复现 + 基础断言支撑"判据，
   与 `conclusionSource` 的三类来源一一对应。
4. **阈值可调 + 观测**：`ANSWER_FAITHFULNESS_MIN` 走策略配置并暴露到调优界面，配合拦截率看板（按来源类型拆分）。

### 进度（2026-09-24）

段落块提权、候选保底、忠实度加权覆盖已落地（见 `STABILITY-OPTIMIZATION-PLAN.md`）：kb=9 同类问题从整段 `BLOCKED` 变为 `evidenceGate=PASS`、`PARTIAL` 保留 11/12 条结论并给出完整影响路径。

剩余：结构化门控仍判定 `BLOCKED`（冲突或证据不足），以及门控拦截构成需要重启后端后才能在页面看到。

### 验收标准

- kb=9 同类问题不再出现整段 `BLOCKED`；至少 `PARTIAL` 且保留的结论与图谱路径一致。
- 门控结果输出三类拦截的计数，能直接看出拦截来源。
- 图谱遍历结论在无支撑文档时也能通过 `exact`，并在界面上标注"图谱遍历推导"。

---

## 复现命令

```bash
# 抽取粒度对比
docker exec kos-dev-mysql mysql -uknowledge -pknowledge123 knowledge_os -e \
  "select knowledge_base_id kb, entity_type, canonical_name from t_entity \
   where knowledge_base_id in (7,8,9) order by knowledge_base_id, canonical_name;"

# 门控判定（queryId 取自一次查询的返回）
curl -s http://127.0.0.1:8080/api/knowledge/answer-qualification/<queryId> \
  -H "Authorization: Bearer <token>"
```
