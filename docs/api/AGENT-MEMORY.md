# Agent 平台记忆服务 · 集成规范与能力路线

合并自两份文档：

- `AGENT-MEMORY-INTEGRATION.md`（已合并）：AKB 在知识服务之外提供的独立 Agent Memory Engine 的 REST/MCP 集成契约（本服务的权威接口定义）。
- `ANSWER-FEEDBACK-AND-MEMORY-ROADMAP.md`（已合并）：回答反馈闭环与记忆能力扩展规划（部分内容已被上述规范实现；规划部分请同步查看 `docs/development/BACKLOG.md`）。

> 阅读顺序建议：先读第一部分（契约）了解可用接口，再读第二部分（规划）了解后续扩展方向。



---

> **来源**：`docs/AGENT-MEMORY-INTEGRATION.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## Agent 平台记忆服务集成规范

### 目标

AKB 在既有知识服务之外提供独立的 Agent Memory Engine，供本系统、Octop 及其他 Agent / 工作流平台共用。外部平台使用版本化 REST API 或 MCP Server，不需要依赖 AKB 内部 Java 类或数据库结构。

### 边界

- AKB Knowledge 保存经治理的企业事实、证据、卡片、断言和图谱；Memory 保存 Agent 在具体任务中的上下文、经验和经确认画像。两者不混表、不互相自动提升。
- Memory 只在调用方显式启用并提交时写入，不会自动保存员工对话。L0 会话片段需显式 Capture，并使用较短 TTL。
- `L1` 原子记忆、`L2` 团队/项目/任务/工作流场景记忆、`L3` Agent 画像分别有类型约束、来源和默认有效期。L3 只接受用户确认或管理员手工录入。
- 当前版本的 Recall 是隔离作用域内的关键词匹配，并按重要度/更新时间排序。后续可在不改变 API 契约的情况下加入 BM25、向量召回和异步记忆归并。

### 身份和隔离

管理员在 API Key 上分别授予 `MEMORY_READ`、`MEMORY_WRITE`，并配置：

| 配置 | 用途 |
|---|---|
| `memoryNamespace` | 记忆共享边界；留空时按 API Key 独立隔离 |
| `memoryAgentCode` | 服务端绑定的 Agent 身份；留空时使用 API Key 编码 |

Agent 作用域的 owner 永远由 API Key 推导，忽略请求中的身份字段。TEAM / PROJECT / TASK / WORKFLOW / SESSION 共享作用域除 namespace 外，还必须由管理员对具体 API Key 显式授予读取和/或写入权限。只有 scope code 而没有授权记录时，Capture、Recall、Forget 都会被拒绝。`MEMORY_READ` / `MEMORY_WRITE` 是调用能力的上限，作用域授权不能扩大 Key 本身的能力。授权精确匹配 `scopeType + scopeCode`，比较区分大小写。

API Key 撤销会立即阻止继续读写。旧记忆仍留在 namespace 中，可由同 namespace 中仍有效且单独获得该作用域授权的凭证继续访问。升级到按作用域授权后，原先仅凭相同 namespace 即可访问的共享作用域默认拒绝；管理员须补充精确授权。Forget 会立即物理删除单条记忆。L0 到期后由小时级清理任务删除；其他层级到期后不再召回，并在 30 天恢复窗口结束后清理。

当前 REST / MCP Key 代表 Agent 服务身份，不代表最终员工身份。因此本版实现 Agent、团队、项目、任务、工作流和会话作用域；最终用户个人记忆需要由下游身份提供方把可信 subject 映射进服务端凭证上下文，不能让模型从请求体自行声明 userId。

### Agent 工作流调用模式

1. 工作流运行前，Agent 使用 `MEMORY_READ` 调用 Recall：先读取自己的 AGENT 记忆，再读取当前 namespace 中指定的 PROJECT / TASK / WORKFLOW 记忆。
2. Agent 执行任务时，将召回内容与 AKB Knowledge 检索结果分别放入上下文；模型提示需保留二者的来源和可信度区别。
3. 任务完成后，仅把对后续任务有用的结构化经验显式 Capture；携带稳定 `sourceRef` 和重试稳定的 `idempotencyKey`。
4. L3 画像只能由用户确认或管理员录入。需要变成企业正式知识的经验走既有知识接入、裁决和发布流程。
5. 不再需要的记忆通过 Forget 删除；过期数据由服务端 TTL 清理。

### 集成方式

#### 管理员授权与治理

- `GET /api/external/admin/keys/{keyId}/memory-access`：查看 API Key 的共享记忆授权。
- `PUT /api/external/admin/keys/{keyId}/memory-access`：原子替换授权；传 `[]` 撤销全部共享作用域授权。
- `GET /api/external/admin/keys/{keyId}/memories`：管理该 Key 命名空间内的记忆，支持 status、scopeType、scopeCode 筛选，最多 200 条/页。
- `PUT /api/external/admin/keys/{keyId}/memories/{memoryCode}/status?status=SUSPENDED|ACTIVE`：暂停或恢复 Recall，必须提交处理原因。
- `POST /api/external/admin/keys/{keyId}/memories/{memoryCode}/forget`：物理删除，必须提交原因；审计保留编码、作用域、操作人和原因，不保存正文。
- `GET /api/external/admin/keys/{keyId}/memory-events`：查看该 namespace 最近治理记录，不包含记忆正文。

```json
[
  {"scopeType":"PROJECT","scopeCode":"release-2026","canRead":true,"canWrite":false},
  {"scopeType":"WORKFLOW","scopeCode":"release-flow","canRead":true,"canWrite":true}
]
```

控制台“接口服务 → 凭证”提供“记忆授权”和“记忆管理”操作。暂停的记忆不参与 Agent Recall；恢复后重新可用。

#### REST

- `POST /api/v1/external/memories/capture`
- `POST /api/v1/external/memories/recall`
- `DELETE /api/v1/external/memories/{memoryCode}`

使用与其他外部服务相同的 `X-API-Key` 或 Bearer 鉴权、配额和调用审计。Memory 请求正文和 Recall 结果不会写入通用调用审计，避免把记忆内容复制到运营日志。

#### MCP

`POST /api/mcp` 的 `tools/list` 按 API Key scope 动态显示：

- `MEMORY_READ`：`memory_recall`
- `MEMORY_WRITE`：`memory_capture`、`memory_forget`

Octop 和其他兼容 MCP 的平台可直接将 AKB 注册为远程 MCP Server。只支持 REST 的平台也可直接调用上述 HTTP 契约。

### 当前交付与后续阶段

本次版本包含独立存储、API Key 共享作用域 ACL、L0–L3 写入规则、TTL、幂等 Capture、关键词 Recall、Forget、REST/MCP、记忆治理控制台的查询/暂停/恢复/删除和操作审计。BM25/向量混合 Recall、异步记忆归并、可信员工 subject 身份映射和跨系统经验审批仍属于后续阶段；Memory 结果不会自动写成权威知识或 Skill。



---

> **来源**：`docs/ANSWER-FEEDBACK-AND-MEMORY-ROADMAP.md`（历史路径，已合并归档；原文仅在 git 历史中保留）


## 回答反馈闭环与记忆能力扩展

### 代码现状

AKB 已实现文档接入与治理、知识卡片和断言、知识图谱、多路召回、加权 RRF、重排、证据约束、引用及 Query Trace。查询链路可以解释回答如何生成，但缺少用户在真实使用后对答案做评价的持久化入口；离线评测的知识缺口与真实用户反馈还没有连接。

### TencentDB-Agent-Memory 的可借鉴点

参考 `TencentCloud/TencentDB-Agent-Memory` 的 MemoryCore 和 Agent 适配设计：

- 把 Agent 可调用的记忆服务与权威知识内容服务分开。MemoryCore 管记忆、资产元数据与访问关系；知识解析、索引、检索仍由知识内容服务负责。
- 将 Capture 与 Recall 作为明确的数据流程，并以 L0 对话、L1 原子记忆、L2 场景记忆、L3 核心画像表达不同抽象层级。
- 检索支持关键词、向量和混合召回；写入与归纳有异步化、溯源和可清理边界。
- Agent、Team、User、Task 等作用域是隔离条件的一部分。不能只在提示词中声明隔离，必须在存储和查询中执行。

参考：[`MemoryCore/README_CN.md`](https://github.com/TencentCloud/TencentDB-Agent-Memory/blob/feat/server_team/MemoryCore/README_CN.md)、[`memory-search.ts`](https://github.com/TencentCloud/TencentDB-Agent-Memory/blob/feat/server_team/MemoryCore/src/core/tools/memory-search.ts)。

### 本轮落地

本轮先实现真实问答的显式反馈闭环，再增加独立 Agent Memory 平台接口；两者都不会把未确认内容直接写入权威知识：

1. 用户可对已回答问题选择“有帮助”或“需要改进”，填写问题原因、说明和可选建议答案。
2. 服务端根据 `queryId` 找到已保存的 Query Trace，知识库 ID 从 Trace 派生，不信任客户端传入的 ID。
3. Query Trace 记录登录用户 ID；反馈仅允许创建者提交。每个用户对一次查询保持一条反馈，重复提交会更新原反馈。
4. 知识运营人员可以查看队列、摘要并推进 `NEW → IN_REVIEW → ACTION_REQUIRED / RESOLVED / REJECTED`。
5. `ACTION_REQUIRED` 是知识整改待办。用户建议答案只作为待审核信号；运营人员需通过既有文档、卡片、断言和裁决能力更新权威知识。
6. 运营读取和审核受 `search:evaluate` 权限及知识库写权限保护；反馈提交和本人记录受登录身份与知识库读取权限保护。

7. 新增独立 `t_agent_memory` 数据域，提供 API Key scope 隔离、L0–L3 层级、Agent/Team/Project/Task/Workflow/Session 作用域、TTL、幂等 Capture、Recall、Forget。
8. REST `/api/v1/external/memories/**` 与 MCP `memory_capture / memory_recall / memory_forget` 共用服务实现；记忆读写需显式授予 `MEMORY_READ` / `MEMORY_WRITE`。
9. 默认每把 API Key 独立 namespace；仅管理员明确配置相同 namespace 的 Agent 才共享项目/任务记忆。Agent 身份由凭证绑定，不能从模型参数体传入。
10. 记忆请求正文与 Recall 结果不写入一般外部调用审计，L0 有短 TTL，Forget 立即物理删除，L3 只接受用户确认或手工录入。

### 与记忆系统的边界

回答反馈是有来源、有上下文、有审核状态的应用反馈，不等同于 L1/L2/L3 记忆，也不会自动成为模型可召回的事实。当前已建立独立记忆数据域与服务契约：

- L0 保存受保留策略控制的原始会话片段；
- L1 保存经抽取、去重、确认来源的原子记忆；
- L2 保存团队或任务场景记忆；
- L3 保存经过授权的稳定画像；
- 所有层级分别配置 Team / Project / Agent / Task / Workflow / Session 范围、API Key ACL、有效期、删除与审计；用户级身份范围待接入可信 subject 身份映射；
- 当前 Recall 采用关键词匹配和重要度排序，后续加入 BM25/向量混合召回及异步归并；知识库证据和记忆召回融合时仍须保留不同可信度、来源和生命周期。

### API

- `POST /api/knowledge/feedback`：提交或更新本人反馈；请求包含 `queryId`、`rating`、可选 `reasonCode`、`comment`、`suggestedAnswer`。
- `GET /api/knowledge/feedback/mine/{kb}`：查看本人反馈。
- `GET /api/knowledge/feedback/ops/{kb}?status=&limit=`：知识运营反馈队列及状态统计。
- `PUT /api/knowledge/feedback/ops/{id}/review`：审核并设置状态与处理说明。

迁移 `V48__query_feedback_workflow.sql` 为 Query Trace 增加调用者归属，并创建持久化反馈工作流表。

记忆 API 与 MCP 契约详见本文件；数据库迁移依次为 `V49__agent_memory_platform.sql`、`V50__agent_memory_scope_access.sql`、`V51__agent_memory_governance.sql`。

### 后续实施次序

1. 在运营页面把 `ACTION_REQUIRED` 和知识资产/版本/裁决任务关联，并能从反馈跳到目标资产。
2. 为嵌入式问答和外部 API 增加基于 API Key 的反馈身份与限流，同时不接受客户端伪造用户 ID。
3. 将重复负反馈聚合为 Knowledge Gap 候选，按问题、召回通道、缺失证据和知识库分组。
4. 增加 BM25/向量混合召回与异步记忆整理，并基于可信用户 subject 映射扩充用户级作用域。
5. 将 Octop 作为首个 Agent 平台接入方，使用 REST/MCP Memory API 在虚拟员工任务前 Recall、任务后幂等 Capture；不将短期工作记忆默认写回权威知识。

上述扩展沿用 AKB 既有治理能力，不重做知识抽取、图谱、混合检索或证据链。
