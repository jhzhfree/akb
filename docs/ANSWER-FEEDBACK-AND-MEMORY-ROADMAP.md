# 回答反馈闭环与记忆能力扩展

## 代码现状

AKB 已实现文档接入与治理、知识卡片和断言、知识图谱、多路召回、加权 RRF、重排、证据约束、引用及 Query Trace。查询链路可以解释回答如何生成，但缺少用户在真实使用后对答案做评价的持久化入口；离线评测的知识缺口与真实用户反馈还没有连接。

## TencentDB-Agent-Memory 的可借鉴点

参考 `TencentCloud/TencentDB-Agent-Memory` 的 MemoryCore 和 Agent 适配设计：

- 把 Agent 可调用的记忆服务与权威知识内容服务分开。MemoryCore 管记忆、资产元数据与访问关系；知识解析、索引、检索仍由知识内容服务负责。
- 将 Capture 与 Recall 作为明确的数据流程，并以 L0 对话、L1 原子记忆、L2 场景记忆、L3 核心画像表达不同抽象层级。
- 检索支持关键词、向量和混合召回；写入与归纳有异步化、溯源和可清理边界。
- Agent、Team、User、Task 等作用域是隔离条件的一部分。不能只在提示词中声明隔离，必须在存储和查询中执行。

参考：[`MemoryCore/README_CN.md`](https://github.com/TencentCloud/TencentDB-Agent-Memory/blob/feat/server_team/MemoryCore/README_CN.md)、[`memory-search.ts`](https://github.com/TencentCloud/TencentDB-Agent-Memory/blob/feat/server_team/MemoryCore/src/core/tools/memory-search.ts)。

## 本轮落地

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

## 与记忆系统的边界

回答反馈是有来源、有上下文、有审核状态的应用反馈，不等同于 L1/L2/L3 记忆，也不会自动成为模型可召回的事实。当前已建立独立记忆数据域与服务契约：

- L0 保存受保留策略控制的原始会话片段；
- L1 保存经抽取、去重、确认来源的原子记忆；
- L2 保存团队或任务场景记忆；
- L3 保存经过授权的稳定画像；
- 所有层级分别配置 Team / Project / Agent / Task / Workflow / Session 范围、API Key ACL、有效期、删除与审计；用户级身份范围待接入可信 subject 身份映射；
- 当前 Recall 采用关键词匹配和重要度排序，后续加入 BM25/向量混合召回及异步归并；知识库证据和记忆召回融合时仍须保留不同可信度、来源和生命周期。

## API

- `POST /api/knowledge/feedback`：提交或更新本人反馈；请求包含 `queryId`、`rating`、可选 `reasonCode`、`comment`、`suggestedAnswer`。
- `GET /api/knowledge/feedback/mine/{kb}`：查看本人反馈。
- `GET /api/knowledge/feedback/ops/{kb}?status=&limit=`：知识运营反馈队列及状态统计。
- `PUT /api/knowledge/feedback/ops/{id}/review`：审核并设置状态与处理说明。

迁移 `V48__query_feedback_workflow.sql` 为 Query Trace 增加调用者归属，并创建持久化反馈工作流表。

记忆 API 与 MCP 契约详见 [Agent 平台记忆服务集成规范](AGENT-MEMORY-INTEGRATION.md)，数据库迁移为 `V49__agent_memory_platform.sql`。

## 后续实施次序

1. 在运营页面把 `ACTION_REQUIRED` 和知识资产/版本/裁决任务关联，并能从反馈跳到目标资产。
2. 为嵌入式问答和外部 API 增加基于 API Key 的反馈身份与限流，同时不接受客户端伪造用户 ID。
3. 将重复负反馈聚合为 Knowledge Gap 候选，按问题、召回通道、缺失证据和知识库分组。
4. 增加 BM25/向量混合召回与异步记忆整理，并基于可信用户 subject 映射扩充用户级作用域。
5. 将 Octop 作为首个 Agent 平台接入方，使用 REST/MCP Memory API 在虚拟员工任务前 Recall、任务后幂等 Capture；不将短期工作记忆默认写回权威知识。

上述扩展沿用 AKB 既有治理能力，不重做知识抽取、图谱、混合检索或证据链。
