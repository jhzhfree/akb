# AKB 知识库检索权限落地方案

**依据：** 《AKB_知识库检索权限设计说明_V1.0.md》
**分支：** `feature/octop-mcp-integration`
**日期：** 2026-10-20

本文是设计说明的工程落地方案：先把设计目标与当前代码的差距核实清楚，再给出分阶段改造路径、数据模型与上线步骤。

---

## 1. 核查结论：当前实现基线

对代码逐条核实后的结论（与设计说明 §3 的基线描述一致，并补充了确切落点）：

| 核查项 | 结论 | 证据 |
|---|---|---|
| **外部标签被当作授权依据** | ❌ **确认存在**。请求标签原样进入检索过滤条件 | `ExternalKnowledgeFacade.java:144-145` → `KnowledgeQueryService.java:68`（`aiClient.post`）→ ai-service `app/services/retrieval/hybrid.py:177/183/195/214` 把标签传给 ES / BM25 / Milvus / Neo4j |
| **跨库越权** | ✅ 已有防护 | `ExternalAccessService.java:85 resolveKnowledgeBase`：绑定库与请求库不符抛 `SecurityException`；未绑定时走 `assertExternalAccess` 公开库规则 |
| **对象读取链路** | ✅ 未受影响 | `ExternalApiController` 的 cards / claims / evidence / graph / overview 传的都是 `null`，不接收外部标签 |
| **操作权限枚举** | ✅ 已有第一阶段细分 | 新 Key 支持 `LIST` / `QUERY` / `SEARCH` / `READ_OBJECT` / `INGEST`；`READ` 与 `WRITE` 保持旧 Key 兼容。`MANAGE_ACL` 尚未开放，因为外部 ACL 管理端点尚不存在 |
| **租户** | ❌ 不存在 | 全仓 grep `tenant` 零命中，当前为单租户 |
| **资料 ACL 字段** | ✅ 已有基础 | `t_source` / `t_knowledge_card` / `t_claim` 均有 `permission_tags` JSON 列；已有 `t_acl_access_audit`、`t_acl_change` |
| **知识库可见性** | ✅ 已有 | `KnowledgeBaseVisibility`：`PUBLIC` / `PRIVATE` / `GROUP` |
| **查询缓存** | — 不适用 | 无 `@Cacheable`、无 Redis 查询缓存。设计 §11 的缓存键要求暂不涉及，但将来引入时必须带上授权上下文 |
| **委托身份** | 🟡 本轮补齐身份验证与知识库/标签交集 | `DelegatedIdentityService` 校验 OIDC JWT，用户映射必须是显式 ACTIVE USER Principal |

### 1.1 漏洞链路（唯一确认的高危项）

```text
外部请求 permissionTags
  → ExternalKnowledgeFacade.payload()  原样塞进 payload
  → KnowledgeQueryService.query()      aiClient.post(QUERY, payload)
  → ai-service hybrid.py               permission_tags 作为 ES/BM25/Milvus/Neo4j 过滤条件
```

ai-service 的测试直接固化了这一行为：

- `ai-service/tests/test_acl_contract.py:8` — `{"terms": {"permission_tags": ["team-a"]}}`
- `ai-service/tests/test_graph_acl.py:21` — `any(tag IN coalesce(n.permissionTags, []) WHERE tag IN $permissionTags)`

即：**传入什么标签，就召回什么标签的资料**。调用方自填标签即可扩大召回范围。

---

## 2. 落地策略：四个前置决策

| # | 决策点 | 结论 | 理由 |
|---|---|---|---|
| 1 | **租户** | 本轮不引入多租户；新表预留 `tenant_id BIGINT NOT NULL DEFAULT 0` | 当前全仓无 tenant 概念，引入要给所有表与查询动刀；预留字段可让未来开启只填值 |
| 2 | **权限枚举** | 落地完整 6 个：`LIST` / `QUERY` / `SEARCH` / `READ_OBJECT` / `INGEST` / `MANAGE_ACL` | 现有 `hasScope` 已支持多值，成本不高；接口与审计记录操作类型，为将来拆分留口 |
| 3 | **外部标签退场** | 影子模式过渡，配置项开关，禁止回滚到客户端自声明 | 直接收紧会影响正在依赖该行为的调用方；影子期先量化影响面 |
| 4 | **未绑定库的 Key** | 迁移时显式授权：有绑定 → 转等价 grant；无绑定 → 仅 PUBLIC 库 | 现状语义不明（可访问任意库 vs 仅公开库），必须显式化 |

---

## 3. 分阶段落地路径

### P0：安全闭环（✅ 已完成）

目标：把授权收回到服务端，消除客户端自声明标签。

| # | 任务 | 落点 |
|---|---|---|
| 1 | 凭证标签授权字段 | `V55__api_key_permission_tags.sql`：`t_api_key.permission_tags JSON NULL` |
| 2 | 服务端授权上下文 | 新增 `common/RetrievalAuthorizationContext`：三态语义 + `effectiveTags`(交集) / `removedTags` |
| 3 | 授权解析服务 | 新增 `service/RetrievalAuthorizationService`：`SHADOW` / `ENFORCE` / `CLIENT` 三模式 |
| 4 | 链路接入 | `ExternalKnowledgeFacade.query` / `streamQuery` 出口收窄；`search` 与 MCP 工具因复用 `query` 自动覆盖 |
| 5 | 配置开关 | `knowledge.acl.external-tag-mode`（默认 `SHADOW`） |
| 6 | 回归测试 | `RetrievalAuthorizationServiceTest` 11 例 |

**生效公式**（设计 §4.4 权限只收窄）：

```text
effectiveTags = 凭证授权标签 ∩ 请求标签
请求为空 → 不收窄（用授权全集）
授权为空 → 只看公开资料（默认拒绝）
```

### P1：主体与多知识库授权（基础闭环已实现）

1. ✅ 完成基础动作 Scope：新 Key 可按 `LIST` / `QUERY` / `SEARCH` / `READ_OBJECT` 分配读取动作；MCP 工具清单过滤未授权工具，REST、SSE、Embed、MCP 调用逐次执行相同 Scope 校验。旧 `READ` 继续等价于四个读取动作，旧 `WRITE` 继续隐含读取动作与 `INGEST`。
2. ✅ 增加 `t_principal`、`t_principal_kb_grant` 与 V61 迁移；每个现有 API Key 迁成独立主体，原单库绑定转成同等 grant。
3. ✅ API Key 创建时创建主体；管理端可读取或原子替换知识库授权，空数组表示撤销全部。授权替换会写 `t_principal_kb_grant_audit`，保留授予与撤销记录。
4. ✅ REST `/bases`、MCP 工具及资源清单按 grant 返回；访问具体知识库时逐请求校验 grant。未绑定且无 grant 的旧 Key 仍只可访问公开知识库；已迁移 Key 撤销 grant 后不能再借旧 `knowledge_base_id` 回退访问。
5. ✅ 管理端通过授权配置接口按知识库设置 `expires_at` 与审计原因；过期时间到达后授权自动失效。
6. ✅ 增加共享 Principal 管理：多个 API Key 可关联同一主体并立即共用授权；新主体会复制源 Key 的有效授权；主体迁移由 V62 审计记录，主体不物理删除。
7. ✅ V63 为知识库 grant 增加独立标签范围；有效标签为 API Key 全局授权与知识库 grant 授权的交集，再与请求标签相交。`null` 继承全局配置，空数组只允许公开资料；变更审计保留标签快照。
8. ⏳ 后续按 P2 顺序完成 OIDC/OAuth 委托身份、员工与 Agent 权限交集、资料级 ACL 和撤权传播。数据库迁移验收需在 Docker 可用环境执行。

### P2：委托身份与细粒度 ACL（身份委托 MVP 已实现）

1. ✅ 可选双凭证校验：`X-API-Key` 代表 Agent，`Authorization: Bearer <JWT>` 代表员工；功能默认关闭。开启后缺任一凭证即拒绝，并拒绝独立 Embed Token。JWT 验签并校验 `iss` / `aud` / `exp` / `nbf` / `sub`。
2. ✅ 新增 `principal_type` 与 `t_principal_identity_link`。管理员创建 USER Principal 并显式绑定配置 issuer 下的 `(issuer, subject)`；不自动开户，不读取 `X-User-Id`。绑定及撤销写入审计表。
3. ✅ 查询授权取 Agent Principal 与员工 USER Principal 的知识库 grant 交集；同库资料标签再取两主体授权范围交集。两方缺任一有效 grant 均拒绝，公共库不会绕过委托交集。
4. ✅ 管理端可配置 USER Principal 的逐库标签、期限与原因，和查看/吊销身份绑定。
5. ⏳ 资料级 `t_resource_acl`、`visibility` / `classification`、DENY 优先以及索引侧撤权传播尚未实现；这是下一段 P2。

配置项（环境变量形式）：

```properties
knowledge.external.delegated-identity.enabled=false
knowledge.external.delegated-identity.issuer=https://idp.example.com/
knowledge.external.delegated-identity.jwk-set-uri=https://idp.example.com/.well-known/jwks.json
knowledge.external.delegated-identity.audience=akb-external-api
```

启用后外部调用必须同时携带 `X-API-Key` 和 IdP 签发的员工 Bearer JWT。每个 JWT `sub` 需预先由管理员映射到 USER Principal。对于一个目标知识库，Agent grant ∩ 员工 grant 必须包含该库；资料标签按 Key 全局范围 ∩ Agent grant 标签 ∩ 员工 grant 标签进一步收窄。该 MVP 只对知识库 membership 和检索标签做主体交集，API 操作 Scope 仍由 API Key 控制；细粒度员工动作权限归入后续改造。

### P3：外接知识库治理（未开始）

连接器 ACL 能力声明、同步延迟告警、无法映射的连接器走隔离模式。

---

## 4. 数据模型（P1 起）

### 4.1 主体与凭证

```sql
CREATE TABLE t_principal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  principal_code VARCHAR(64) NOT NULL,
  principal_type VARCHAR(16) NOT NULL,      -- USER / GROUP / APPLICATION / AGENT
  name VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  source VARCHAR(64) NULL,                  -- 来源系统（IdP / 管理端）
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_principal (tenant_id, principal_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 外部 IdP 的 issuer + subject → AKB Principal 映射
CREATE TABLE t_principal_identity_link (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  principal_id BIGINT NOT NULL,
  issuer VARCHAR(255) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE KEY uk_identity (tenant_id, issuer, subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE t_api_key ADD COLUMN principal_id BIGINT NULL AFTER id;
```

### 4.2 知识库授权

```sql
CREATE TABLE t_principal_kb_grant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  principal_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  actions VARCHAR(255) NOT NULL,            -- LIST,QUERY,SEARCH,READ_OBJECT,INGEST,MANAGE_ACL
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  valid_from DATETIME NULL,
  valid_to DATETIME NULL,
  granted_by BIGINT NULL,
  reason VARCHAR(255) NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_grant (tenant_id, principal_id, knowledge_base_id),
  KEY idx_grant_kb (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 授权变更审计：记录旧值、新值、操作人与原因
CREATE TABLE t_principal_kb_grant_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  grant_id BIGINT NOT NULL,
  old_actions VARCHAR(255) NULL,
  new_actions VARCHAR(255) NULL,
  old_status VARCHAR(16) NULL,
  new_status VARCHAR(16) NULL,
  changed_by BIGINT NULL,
  reason VARCHAR(255) NULL,
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.3 资料 ACL（P2）

```sql
CREATE TABLE t_resource_acl (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL DEFAULT 0,
  knowledge_base_id BIGINT NOT NULL,
  resource_type VARCHAR(24) NOT NULL,       -- SOURCE / DOCUMENT / CARD / CLAIM / EVIDENCE / ENTITY
  resource_code VARCHAR(64) NOT NULL,
  subject_type VARCHAR(16) NOT NULL,        -- USER / GROUP / APPLICATION / AGENT
  subject_code VARCHAR(64) NOT NULL,
  actions VARCHAR(255) NOT NULL,
  effect VARCHAR(8) NOT NULL DEFAULT 'ALLOW',   -- ALLOW / DENY，DENY 优先
  valid_from DATETIME NULL,
  valid_to DATETIME NULL,
  policy_version BIGINT NOT NULL DEFAULT 1,
  UNIQUE KEY uk_resource_acl (tenant_id, resource_type, resource_code, subject_type, subject_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> 资料表已具备 `permission_tags`，P2 再补 `visibility` / `classification` 列。

---

## 5. 上线与灰度步骤

```text
1. 部署 P0（默认 SHADOW）——行为不变，只在日志里记录差异
     grep "[ACL][SHADOW]" 日志 → 统计哪些 Key、哪些标签会被收窄
2. 为受影响的 Key 配置 permission_tags（服务端授权范围）
3. 评估日志确认无误拒绝后，切 ENFORCE：
     KNOWLEDGE_ACL_EXTERNAL_TAG_MODE=ENFORCE
4. 保留 CLIENT 作为应急回滚（不允许长期开启）
5. P1 上线后，移除旧的单库 + 客户端标签授权路径
```

**日志示例（SHADOW）**

```text
[ACL][SHADOW] 外部标签超出服务端授权，强制模式生效后将被收窄：
  externalTag source=REST key=KEY_xxx kb=12 mode=SHADOW
  requested=[team-a, team-b] removed=[team-b] granted=[team-a]
```

---

## 6. 验收用例映射（设计 §14）

| 用例 | 状态 | 覆盖方式 |
|---|---|---|
| 1. Key 无权访问库 B | ✅ 已有 | `ExternalAccessService.resolveKnowledgeBase` |
| 2. 仅 QUERY 不能 INGEST | ⚠️ P1 | 需 actions 细分后补测试 |
| 3. 伪造标签不能召回 | ✅ 已补 | `narrowsRequestedTagsToGrantedScope` |
| 4. 过滤只缩小不扩大 | ✅ 已补 | `emptyRequestUsesFullGrant` |
| 5 / 6. 委托模式双向拒绝 | ⬜ P2 | |
| 7. 多库部分授权 | ⬜ P1 | |
| 8. 不经向量/BM25/图谱/缓存泄漏 | ⬜ P2 | 图谱每跳校验 |
| 9. 撤权传播 SLA | ⬜ P2 | |
| 10. 跨租户隔离 | ⬜ 待定租户决策 | |
| 11. MCP 隐藏工具仍鉴权 | ✅ 部分 | `tools/call` 走同一 facade；工具级 action 校验待 P1 |
| 12. 连接器身份映射失败 | ⬜ P3 | |

---

## 7. 风险与待办

1. **影子期必须看日志**：默认 `SHADOW` 不改变行为，不主动排查就永远发现不了影响面。
2. **空标签的历史语义**：迁移前必须盘点空 `permission_tags` 到底是"公开"还是"无约束"，不能静默提升为全平台公开（设计 §5.3）。
3. **图谱跨库遍历**（§11 每跳校验）改造成本高，P2 单独评估。
4. **既有失败测试**：`QueryFeedbackServiceTest.negativeFeedbackMustHaveReasonBeforeWrite` 当前失败（期望 `IllegalArgumentException`，实际 `SecurityException`，实现先校验发起人再校验原因）。经 `git stash` 验证为**既有问题**，与本次改动无关，建议单独修。
