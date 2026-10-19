# Agent 平台记忆服务集成规范

## 目标

AKB 在既有知识服务之外提供独立的 Agent Memory Engine，供本系统、Octop 及其他 Agent / 工作流平台共用。外部平台使用版本化 REST API 或 MCP Server，不需要依赖 AKB 内部 Java 类或数据库结构。

## 边界

- AKB Knowledge 保存经治理的企业事实、证据、卡片、断言和图谱；Memory 保存 Agent 在具体任务中的上下文、经验和经确认画像。两者不混表、不互相自动提升。
- Memory 只在调用方显式启用并提交时写入，不会自动保存员工对话。L0 会话片段需显式 Capture，并使用较短 TTL。
- `L1` 原子记忆、`L2` 团队/项目/任务/工作流场景记忆、`L3` Agent 画像分别有类型约束、来源和默认有效期。L3 只接受用户确认或管理员手工录入。
- 当前版本的 Recall 是隔离作用域内的关键词匹配，并按重要度/更新时间排序。后续可在不改变 API 契约的情况下加入 BM25、向量召回和异步记忆归并。

## 身份和隔离

管理员在 API Key 上分别授予 `MEMORY_READ`、`MEMORY_WRITE`，并配置：

| 配置 | 用途 |
|---|---|
| `memoryNamespace` | 记忆共享边界；留空时按 API Key 独立隔离 |
| `memoryAgentCode` | 服务端绑定的 Agent 身份；留空时使用 API Key 编码 |

Agent 作用域的 owner 永远由 API Key 推导，忽略请求中的身份字段。TEAM / PROJECT / TASK / WORKFLOW / SESSION 作用域使用 namespace + 显式 scope code。当前权限粒度是 namespace：持有同一 namespace 且具有 MEMORY_READ 的凭证可读取该 namespace 下匹配的共享 scope；scope code 用于隔离检索上下文，不代表独立 ACL。管理员应按租户/安全边界分配 namespace，不得跨租户或跨应用复用。若项目之间需要不同授权边界，应先使用不同 namespace；细粒度项目 ACL 属于后续扩展。

API Key 撤销会立即阻止继续读写。旧记忆仍留在 namespace 中，可由同 namespace 中仍有效且具备权限的凭证继续访问。Forget 会立即物理删除单条记忆。L0 到期后由小时级清理任务删除；其他层级到期后不再召回，并在 30 天恢复窗口结束后清理。

当前 REST / MCP Key 代表 Agent 服务身份，不代表最终员工身份。因此本版实现 Agent、团队、项目、任务、工作流和会话作用域；最终用户个人记忆需要由下游身份提供方把可信 subject 映射进服务端凭证上下文，不能让模型从请求体自行声明 userId。

## Agent 工作流调用模式

1. 工作流运行前，Agent 使用 `MEMORY_READ` 调用 Recall：先读取自己的 AGENT 记忆，再读取当前 namespace 中指定的 PROJECT / TASK / WORKFLOW 记忆。
2. Agent 执行任务时，将召回内容与 AKB Knowledge 检索结果分别放入上下文；模型提示需保留二者的来源和可信度区别。
3. 任务完成后，仅把对后续任务有用的结构化经验显式 Capture；携带稳定 `sourceRef` 和重试稳定的 `idempotencyKey`。
4. L3 画像只能由用户确认或管理员录入。需要变成企业正式知识的经验走既有知识接入、裁决和发布流程。
5. 不再需要的记忆通过 Forget 删除；过期数据由服务端 TTL 清理。

## 集成方式

### REST

- `POST /api/v1/external/memories/capture`
- `POST /api/v1/external/memories/recall`
- `DELETE /api/v1/external/memories/{memoryCode}`

使用与其他外部服务相同的 `X-API-Key` 或 Bearer 鉴权、配额和调用审计。Memory 请求正文和 Recall 结果不会写入通用调用审计，避免把记忆内容复制到运营日志。

### MCP

`POST /api/mcp` 的 `tools/list` 按 API Key scope 动态显示：

- `MEMORY_READ`：`memory_recall`
- `MEMORY_WRITE`：`memory_capture`、`memory_forget`

Octop 和其他兼容 MCP 的平台可直接将 AKB 注册为远程 MCP Server。只支持 REST 的平台也可直接调用上述 HTTP 契约。

## 当前交付与后续阶段

本次版本包含独立存储、作用域控制、L0–L3 写入规则、TTL、幂等 Capture、关键词 Recall、Forget、REST、MCP、API Key scope 配置与接口文档。语义向量检索、异步 L0→L1/L2/L3 抽取、用户 subject 身份映射、记忆管理控制台和跨系统经验审批仍属于后续阶段；Memory 结果不会自动写成权威知识或 Skill。
