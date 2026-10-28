# AKB 与 Octop 统一身份认证与权限模型详细设计

版本：V1.1（决策确认版）\
日期：2026-10-28\
状态：设计待确认；确认后进入代码改造\
代码基线：AKB `feature/octop-mcp-integration`、Octop `feature/virtual-employees` 的已抽取版本\
范围：身份映射、个人访问令牌、服务身份、AKB 权限、Memory 主体化、Octop 绑定和迁移

## 1. 本版结论与已确认决策

本文沿用上一版对 AKB 和 Octop 最新抽取代码的分析，并将用户确认的六项决策转化为可实施设计。核心结论是：**两套系统保留各自的用户目录；AKB 通过受控身份绑定接口建立跨系统 ID 映射；API Key 只是凭据，身份和 Memory 归属以主体为准。**

已确认的设计决策如下：

| # | 决策                                                          | 设计落实                                                                                                                             |
| - | ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| 1 | Octop 与 AKB 使用不同 OIDC IdP；AKB 提供身份 ID 绑定接口                  | 不转发或互认双方 OIDC Token；在 AKB 保存经过授权的 `(Octop 实例, Octop 用户 ID) → AKB 用户主体` 映射。跨系统委托请求使用 AKB 可验证的服务端断言/令牌交换，而非伪造 OIDC Token。          |
| 2 | 服务身份默认禁止网页登录，只允许 API/MCP 调用                                 | AGENT/APPLICATION 服务主体不进入普通用户登录链路，不设置密码和 Web Session；凭据仅可用于已登记的 API/MCP 认证方式。                                                    |
| 3 | 普通 PAT 强制到期；服务 Key 单独管理策略                                   | USER PAT 的 `expires_at` 必填且不可创建永久令牌；服务 Key 使用独立策略、生命周期和轮换流程。                                                                     |
| 4 | 个人 PAT 默认只开放检索只读范围；Memory 和写入权限另行申请                         | 默认 scope 为 `SEARCH/QUERY/LIST/READ_OBJECT`；默认不授予 `MEMORY_MANAGE`、`INGEST`、`WRITE` 等能力。                                           |
| 5 | Memory 权限合并为“记忆管理”；namespace/Agent identity 从 API Key 迁移到主体 | 对外使用组合权限 `MEMORY_MANAGE`，覆盖 `MEMORY_READ/MEMORY_WRITE/MEMORY_DELETE`；实际可操作范围仍由 AKB 的 Principal 级 namespace/Agent scope grant 限定。 |
| 6 | 第一阶段由 AKB 管理员创建并绑定服务身份；自动化 provisioning 后续实施                | P1 先通过 AKB 管理端/API 由 AKB 管理员建主体、授权、签发服务 Key，并将绑定信息录入 Octop；后续再做受控自动开通。                                                           |

### 1.1 未改变的关键边界

- Octop 继续负责本系统用户、Agent/虚拟员工、组织、场景、审批和任务运行；AKB 继续负责知识库、知识 ACL、服务主体、Token、Memory 权限和知识审计。
- 不合并 AKB 与 Octop 用户表，不根据邮箱、显示名、IP 或用户自报字段自动匹配身份。
- Memory 是平台可选能力；身份有权访问不等于系统自动启用或自动捕获。`useMemory`、`captureMemory` 仍按平台、API 凭据/主体、Agent 和任务策略逐层控制，默认关闭。
- AKB 授权是数据访问最终判定点；Octop 的 Agent/组织/审批状态是调用前置约束，不能扩大 AKB 的授权。
- 本文只更新设计，不代表本轮已修改任何代码。

## 2. 代码抽取基线与当前实现

### 2.1 抽取基线

| 工程    | 分支                              | 抽取提交                                       | 提交日期       |
| ----- | ------------------------------- | ------------------------------------------ | ---------- |
| AKB   | `feature/octop-mcp-integration` | `c53e8684d3421aa6a2751250c6d3e59d6b3fa11e` | 2026-10-27 |
| Octop | `feature/virtual-employees`     | `8f86e55884bb10cb76688f64f07420b6343ced01` | 2026-10-27 |

AKB 原工作目录当时有 demo seed 文件未提交修改；本次分析基于单独抽取的远端快照，没有覆盖这些文件。Octop 分析基于上述虚拟员工分支快照。

### 2.2 AKB 当前能力

AKB 已有可复用的外部访问基础：`ExternalPrincipal`、API Key、Principal 级知识库 Grant、OIDC `issuer + subject` 用户映射、委托用户授权交集、Memory API 与 Memory scope 校验。Key 只保存 hash/prefix，创建响应一次性返回明文。相关实现集中在 `ApiKey`、`ExternalPrincipal`、`ExternalAccessService`、`DelegatedIdentityService`、`ApiKeyService`、`AgentMemoryScopeAccessService` 和外部服务管理 Controller。

当前的主要差距是：

- OIDC 委托以 AKB 可验证的 OIDC 用户 Token 为基础；Octop 使用另一个 IdP，因此不能通过“共享 issuer”实现。
- AKB 现有身份关联以可信 OIDC `issuer + subject` 为主要形态，需要补充 Octop 外部用户 ID 的显式绑定及其信任边界。
- Memory namespace、Agent code 以及部分共享 Memory 授权与 API Key 关联；Key 轮换会带来身份/授权迁移问题。
- Memory 的底层操作 scope 细分为读/写等能力，而新决策要求用户可申请的权限入口统一为 `MEMORY_MANAGE`。
- 当前授权配置要区分身份、API scope、知识资源 Grant 和 Memory 作用域 Grant，不能因为简化界面而合并成一项无边界的全局权限。

### 2.3 Octop 当前能力

Octop 已有本地用户和 SSO 身份字段、Agent 所有者/共享关系、自定义 MCP Connector 凭据加密、虚拟员工知识源和 Memory scope 绑定、运行快照与授权复核、Memory 候选及经理审批流程。相关实现集中在 `deps.py`、users/agents repository、Connector service、digital worker service、scene service、memory service、AKB client 及 `docs/akb-integration.md`。

当前 AKB Connector 凭据偏向用户级保存。Memory 审核回写使用候选创建者的 AKB Connector/Key，可能导致同一个 Worker 在不同申请人下使用不同 AKB 主体。目标是绑定 Worker/Agent 的稳定 AKB 服务主体，并保留申请人、审核人和任务的业务审计身份。

### 2.4 现状与目标差距

| 领域           | 当前差距                                               | 目标                                                             |
| ------------ | -------------------------------------------------- | -------------------------------------------------------------- |
| 跨系统用户        | 两个 IdP 不相同，无法直接校验另一侧用户 Token                       | AKB 显式绑定外部 ID；由 AKB 验证 Octop 服务端发出的短期身份断言或交换令牌                 |
| 服务身份         | 既有 ExternalPrincipal/API Key 基础已存在，但需强化服务主体管理和模式校验 | AGENT/APPLICATION 主体只用于 API/MCP，管理员建绑，主体状态实时参与鉴权               |
| PAT          | 现有 Key 能力需要明确个人归属、强制过期和创建者自助管理                     | USER PAT 生命周期必填到期，默认仅检索只读 scope                                |
| Memory       | Key 保存 Memory 身份或授权配置                              | 主体拥有稳定 namespace/Agent identity/作用域 Grant，Key 仅提供认证和 API scope |
| Memory scope | 细粒度操作能力已存在                                         | 对申请人使用一个显式 `MEMORY_MANAGE` 组合授权；资源范围仍严格按主体 Grant 限制            |
| Octop 绑定     | 用户级 Connector 凭据不足以表达 Agent 稳定身份                   | Worker/Agent 显式绑定 AKB 实例、Principal、凭据引用和身份模式                   |

## 3. 目标身份与信任模型

### 3.1 身份、凭据、授权分层

| 对象                                   | 职责                             | 不负责                              |
| ------------------------------------ | ------------------------------ | -------------------------------- |
| AKB User                             | AKB 控制台登录用户                    | 不表示 Octop 用户身份，除非有显式绑定           |
| Octop User                           | Octop 登录、组织操作、任务发起身份           | 不能直接决定 AKB 资源权限                  |
| ExternalPrincipal(USER)              | AKB 内承接人类知识授权的稳定主体             | 不承载 API 密钥明文                     |
| ExternalPrincipal(AGENT/APPLICATION) | Octop Agent 或应用服务身份            | 不允许网页登录，不等同于人类用户                 |
| PAT / Service Key                    | 证明请求持有对应主体凭据，并限制 API scope     | 不作为稳定身份标识，不直接保存 Memory namespace |
| OIDC ID binding                      | 将一个受信外部身份 ID 关联到一个 AKB USER 主体 | 不做邮箱猜测或隐式用户合并                    |
| Knowledge/Memory Grant               | 定义主体可访问的资源范围                   | 不证明请求是谁发出的                       |

### 3.2 跨系统身份映射

两套 IdP 不互认时，Octop 用户身份按以下唯一映射建立：

```text
AKB binding key = (octop_instance_id, octop_user_id)
AKB target      = AKB ExternalPrincipal(USER)
```

Agent 服务身份使用：

```text
Agent identity = (octop_instance_id, agent_id)
AKB target     = ExternalPrincipal(AGENT)
```

`octop_instance_id` 是部署实例级稳定 ID，不能用可变 URL 代替。`octop_user_id` 与 `agent_id` 使用源系统不可变 ID。显示名、邮箱、IP、Agent 名称可作为审核信息，但不是身份键。

AKB 原有 OIDC `issuer + subject` 映射继续支持直接 OIDC 客户端；它与 Octop ID binding 是两类身份链接来源，统一映射到 AKB USER Principal，但必须保留 `identity_provider_type`、源实例、创建者和验证方式用于审计。

### 3.3 身份绑定流程与防冒用

**第一阶段采用管理员确认绑定**：

1. AKB 管理员在 AKB 管理端创建/选择 Octop 外部用户到 AKB 用户主体的绑定；用户记录操作前确认两侧和授权归属。
2. 如果由 Octop 发起申请，Octop 只提交 `(octop_instance_id, octop_user_id)` 和关联说明，AKB 将其置为 `PENDING`；该 API 不允许调用方自行指定 AKB Principal 并直接激活绑定。
3. AKB 管理员通过 AKB 登录会话批准绑定到明确的 AKB USER Principal，形成审计事件；必要时要求双方管理员二次复核。
4. 对已绑定身份提供只读查询、解绑/停用及冲突诊断 API；任何解绑都立即阻止新的委托身份交换。
5. 唯一约束保证一个 Octop 外部用户在同一 AKB 实例中只有一个有效绑定；一个 AKB 用户可否绑定多个 Octop 实例由管理员策略控制。

禁止 Octop 普通 API Key 直接调用“任意绑定并激活”接口。自动化 provisioning 阶段也必须使用专用 AKB 管理客户端、最小作用域、幂等请求、审批/审计，并受绑定策略约束。

### 3.4 不同 IdP 下的运行期委托身份

Octop 的 ID Token/Access Token 不转发给 AKB；AKB 也不尝试验证 Octop IdP 的普通用户 Token。需要同时表达 Agent 和用户的请求时，采用两阶段信任链：

1. Octop 后端从已验证的 Octop 服务端会话中确定 `octop_user_id`，从已授权 Agent/Worker 绑定中确定 `agent_id`。身份不能从模型 prompt、工具参数或未验证 Header 取值。
2. Octop 后端使用 AKB 管理员配置的服务端客户端身份（建议 mTLS 或非对称签名客户端密钥）签发短时身份断言，包含 `iss`（Octop instance）、`sub`（Octop user ID）、`agent_id`、`aud`（AKB 身份交换接口）、`iat/exp`、唯一 `jti` 和 `task_id/trace_id`。不得放入用户 Token、Key 明文或多余个人资料。
3. AKB 验证断言签名/客户端、issuer、audience、有效期、唯一性、Agent 状态以及 `(instance_id, user_id)` 绑定状态。
4. AKB 根据绑定得到 USER Principal，结合已认证的 Agent service credential，签发极短时、限定 audience 和用途的交换令牌（建议有效期不超过 5 分钟），或在同一受保护连接内生成等价的不可伪造 server-side auth context。
5. AKB 每次请求仍实时检查 Agent 和 USER 当前 grant。令牌只是身份证明，不固化长期授权，不因断言中声明某个 `agent_id` 而自动授予 Agent 权限。
6. `jti` 防重放；令牌不允许换 audience、扩大 scope 或脱离原始 Agent 主体使用。日志记录 assertion ID/hash、主体 ID、trace ID，不记录断言/令牌全文。

若 AKB 尚未完成断言验证和令牌交换，第一阶段先运行 `AGENT_SERVICE`，对需要用户级隔离的知识请求返回明确“尚未绑定/不支持委托身份”错误；不得临时降级为某个共享用户或把 Octop 的普通 JWT 当作 AKB OIDC Token。

## 4. 认证模式和授权计算

### 4.1 三种运行模式

| 模式                     | 调用主体                                     | 用户身份                                 | 用途                                 |
| ---------------------- | ---------------------------------------- | ------------------------------------ | ---------------------------------- |
| `USER_PAT`             | PAT 所属 AKB USER Principal                | PAT 本身代表用户                           | 用户 SDK/脚本或获批的直接 API 调用             |
| `AGENT_SERVICE`        | API Key 所属 AGENT/APPLICATION Principal   | 无委托用户                                | Cron、后台任务、MCP 服务调用                 |
| `AGENT_DELEGATED_USER` | Agent Principal + AKB 映射的 USER Principal | 来自经过 AKB 验证的 Octop 服务端断言及 ID binding | 用户在 Octop 前台发起、必须按用户权限裁剪的 Agent 操作 |

`identity_mode` 必须由凭据类型、服务端路由和绑定配置确定，不能接受客户端自行声明后跳过校验。未知模式失败关闭。`AGENT_SERVICE` 不得因为请求额外带了一个 `user_id` 就变成委托模式。

### 4.2 API scope 设计

| Scope           | 操作范围                                                 | 个人 PAT 默认                       |
| --------------- | ---------------------------------------------------- | ------------------------------- |
| `SEARCH`        | 检索候选/搜索                                              | 开启                              |
| `QUERY`         | 知识问答/查询                                              | 开启                              |
| `LIST`          | 列出当前授权范围内的知识库或对象                                     | 开启                              |
| `READ_OBJECT`   | 读取授权范围内的知识对象/证据                                      | 开启                              |
| `MEMORY_MANAGE` | Memory Recall、Capture/Write、Delete/Forget 的组合 API 能力 | 关闭，单独申请                         |
| `INGEST`        | 外部资料导入或知识接入                                          | 关闭，单独申请                         |
| `WRITE`         | 知识对象修改或治理写入                                          | 关闭，单独申请                         |
| 管理 scope        | 主体、用户、权限、令牌等管理操作                                     | 不由 PAT 授予；使用 AKB 管理员会话/API 管理身份 |

scope 名称与当前 API 路由之间需建立唯一服务端映射，避免只在 UI 隐藏按钮却未在 API 鉴权。个人 PAT 默认集合精确为 `{SEARCH, QUERY, LIST, READ_OBJECT}`。用户申请 Memory 或写入能力需要管理员批准并记录理由；PAT scope 仍不能超越用户当前资源授权。

### 4.3 Memory 权限合并语义

对外使用一个明确的 `MEMORY_MANAGE` 授权，包含读取、写入和删除三个 Memory 操作族。该组合权限用于简化令牌和授权申请；它**不意味着全局 Memory 管理员权限**：

- Principal 上仍配置允许的 namespace、Agent identity、scope type/code、有效期和启停状态。
- Memory 操作执行时要求凭据具备 `MEMORY_MANAGE`，主体具备对应 namespace/Agent scope Grant，平台 Memory 功能已启用，且 Octop Agent/任务策略允许 `useMemory` 或 `captureMemory`。
- `captureMemory=false` 时即使身份拥有 `MEMORY_MANAGE` 也不得自动写入；删除/Forget 也必须经过 AKB 当前 grant、业务调用策略和审计。
- 代码内部可保留 read/write/delete 作为操作判别、审计动作及细粒度资源规则，但用户授权界面和默认 API scope 以组合权限为准。
- 若某类部署需要禁止删除，应通过独立的资源策略/管理员审批规则限制，而不是让默认 PAT 获得部分 Memory 操作。

### 4.4 权限公式

用户 PAT：

```text
有效操作 = PAT scopes ∩ 用户当前 AKB scopes ∩ 用户知识库/对象 Grant ∩ 资料标签 ACL
```

Agent 后台调用：

```text
有效操作 = Service Key scopes ∩ Agent Principal scopes
         ∩ Agent 知识库/对象 Grant ∩ Memory Principal Grant（如适用）
         ∩ Octop Agent/场景/审批策略 ∩ 资料标签 ACL
```

Agent 委托用户调用：

```text
有效操作 = Agent Key scopes ∩ Agent Principal Grant ∩ 映射用户当前 Grant
         ∩ 用户/Agent 共同可访问资源 ∩ Octop 本次场景和 Connector 范围
         ∩ 资料标签 ACL
```

委托模式中 Agent 与用户授权按交集计算，不允许并集；任何一方被停用、解绑、撤权或 scope 不足，都必须拒绝相应操作。

## 5. PAT 与服务凭据策略

### 5.1 个人 PAT

- 仅已启用的 AKB 登录用户可创建；PAT 绑定创建者对应的 USER Principal，不能创建后转让。
- `expires_at` 数据库必填、创建 API 必填。建议起始策略：默认 90 天、管理员可配置最大 365 天；具体数值作为部署策略参数，不能将其解释成永不过期许可。
- 到期后立即认证失败；不能通过编辑延长已有 Token 生命周期，应重新签发并吊销旧 Token。
- 创建响应仅显示一次完整 secret；数据库存 hash 和展示 prefix，不提供明文恢复接口。
- 支持按 Token 立即吊销、按用户禁用批量失效、查看最后使用时间/IP/客户端标识、轮换与关联审计。
- 创建和轮换均展示最终 scope、资源限制和到期日，提示高风险授权。
- 管理 scope 不允许普通 PAT 使用；PAT 的 `READ_OBJECT` 不代表可以绕过对象 ACL。

### 5.2 服务 Key

- 单独采用 `SERVICE_KEY` 类型，不套用个人 PAT 的默认生命周期参数。
- Key 关联一个服务 Principal；一个 Principal 可以有多把并行 Key 供轮换，Key 轮换不改变 Principal、Memory namespace 或授权。
- 生命周期由 AKB 管理员配置的服务 Key policy 控制。建议默认有到期日、定期轮换并对例外设置负责人和审批；避免默认永久 Key。具体时限由部署安全策略配置。
- 可以设置 scope、Knowledge Base grants、Memory Principal grants、速率限制、来源网络策略和环境标记；scope 与资源授权独立校验。
- Key 默认只允许 AKB API/MCP 认证端点；登录、密码重置、Web session、控制台 cookie 认证不接受服务 Key。
- 吊销 Principal 时，其所有有效 Key 立即失效；禁用单把 Key 不影响同一主体其他 Key。

### 5.3 调用方信息与来源 IP

- `caller_type`（AGENT/APPLICATION）、源系统、名称、环境和负责人是管理员维护的 Principal 元数据，不接受调用方每次请求自报并据此授权。
- 请求来源 IP 由网络连接层和配置过的可信代理链解析；只信任明确列入白名单的反向代理转发头。
- IP 可用于审计、告警和可选 allowlist，不能替代 API Key/断言，也不能绑定跨系统用户身份。
- 记录请求 ID、Principal、Credential ID、scope、资源、判定结果、可信来源 IP、调用模式及委托映射 ID；不记录完整 secret、断言或 Memory 内容。

## 6. 目标数据模型

### 6.1 AKB

尽量在现有 Principal、API Key、KB Grant、OIDC identity link 基础上演进；具体表名以抽取代码实体和 Flyway 版本为准。

| 实体/表                     | 关键属性                                                                                           | 设计要求                                                    |
| ------------------------ | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| Principal                | `principal_type`、稳定 `principal_code`、状态、显示信息、负责人、来源实例                                          | USER/AGENT/APPLICATION 区分明确；服务主体不具有登录凭据                 |
| PAT/ApiKey               | credential kind、principal ID、owner user ID、hash、prefix、scopes、`expires_at`、状态、最近使用             | USER_PAT 强制到期；Service Key 走独立策略；Key 不保存 Memory identity |
| Identity binding         | provider type、external instance ID、external subject ID、AKB USER principal ID、状态、验证/审批来源、操作者、时间 | 对 Octop ID 建唯一约束，支持待审/生效/暂停/解绑和审计                       |
| Octop client trust       | Octop instance ID、client ID、验证方式、JWKS/mTLS 配置引用、允许的 audience、状态                                | 仅 AKB 管理员注册；验证服务端身份断言和交换请求                              |
| KB Principal Grant       | Principal、KB/资源范围、有效期、状态、来源                                                                    | 当前授权模型可复用，继续以 Principal 为粒度                             |
| Principal Memory Profile | Principal、稳定 namespace、Agent code、scope 策略版本                                                   | Memory 身份迁离 Key；Agent 停用不应因 Key 轮换改变 namespace          |
| Principal Memory Grant   | Principal、namespace、Agent identity、scope type/code、有效期、状态                                      | 限定 MEMORY_MANAGE 可操作的数据范围                               |
| Audit event              | actor、target、operation、before/after 摘要、request/trace ID                                        | 覆盖身份绑定、凭据、Grant 和 Memory 操作                             |

建议约束：

1. 一个 PAT 只属于一个 USER Principal；一个 Service Key 只属于一个 AGENT/APPLICATION Principal。
2. 同一 Octop 实例内 `(octop_instance_id, octop_user_id)` 有效绑定唯一；同一 Agent 的服务主体映射唯一，除非有明确的多 AKB 实例场景。
3. namespace、Agent identity、Memory grants 均通过 Principal 查询；Key 上的旧字段只作为迁移兼容字段，不再作为授权最终来源。
4. 旧 Key 无法确认主体归属时，不自动继承或扩大 Memory grants，需人工复核。
5. Binding、主体、Key、scope、资源 Grant 的停用状态都必须进入每次请求的最终授权判定。

### 6.2 Octop

新增 Agent/Worker 到 AKB 的绑定记录，避免把 AKB 主体属性散落在个人 Connector 或 Prompt 配置中。

| 字段                                   | 含义                                   |
| ------------------------------------ | ------------------------------------ |
| `binding_id`                         | Octop 内部稳定 ID                        |
| `provider` / `provider_instance_id`  | AKB 服务及具体实例                          |
| `organization_id`                    | Octop 组织                             |
| `agent_id` / `digital_worker_id`     | 绑定目标                                 |
| `external_principal_code`            | AKB AGENT Principal code             |
| `credential_ref`                     | Octop 加密 Secret 存储的引用，不是明文 Key       |
| `identity_mode`                      | AGENT_SERVICE 或 AGENT_DELEGATED_USER |
| `binding_status` / `binding_version` | 生命周期和并发控制                            |
| `created_by` / `updated_by`          | 操作者                                  |
| 时间字段                                 | 创建、更新时间和最近诊断时间                       |

Octop 用户的 AKB 绑定关系可按组织/AKB 实例缓存 `external_user_binding_id` 或映射状态，但最终有效状态以 AKB 查询/交换接口为准。Octop 不在本地复制 AKB 的完整资源授权表。

场景运行快照只保留主体编码、绑定版本、credential ID/prefix、scope 摘要和策略版本，不保存 API Key、断言或交换 Token 明文。

## 7. AKB API 设计

下列路径是新设计契约建议，需结合当前 Controller 路由统一命名；不是已有接口声明。

### 7.1 管理员身份绑定 API

```http
POST   /api/external/admin/identity-bindings
GET    /api/external/admin/identity-bindings?provider=octop&instanceId=...
GET    /api/external/admin/identity-bindings/{bindingId}
POST   /api/external/admin/identity-bindings/{bindingId}/approve
POST   /api/external/admin/identity-bindings/{bindingId}/suspend
DELETE /api/external/admin/identity-bindings/{bindingId}
```

创建/待绑定请求示例：

```json
{
  "provider": "OCTOP",
  "providerInstanceId": "octop-prod-01",
  "externalUserId": "usr_01J...",
  "targetPrincipalId": "akb-user-principal-id",
  "requestedBy": "octop-admin-client",
  "reason": "绑定虚拟员工委托检索身份"
}
```

安全要求：写接口要求 AKB 管理员会话或专用低范围管理客户端；`targetPrincipalId` 的选择与激活需 AKB 侧审批；Octop 发起的申请默认 `PENDING`。创建应具备幂等键、冲突返回、审计事件和最小返回字段；禁止暴露其他用户的敏感资料。

### 7.2 Octop 客户端注册及身份交换 API

AKB 管理员需要登记 Octop 实例、验证方式和允许的 audience。建议运行时 API：

```http
POST /api/external/auth/token-exchange
Authorization: Service <AKB-Octop-client-credential>
Content-Type: application/json
```

请求携带短期签名身份断言和 Agent Service Key 的受保护引用/证明。交换结果是有效期很短、`aud=AKB external API`、限定 Agent Principal 与绑定 USER Principal 的令牌。令牌 scope 取 Service Key、双方 Grant 和请求 scope 的交集；token exchange 不允许请求端增加 scope。

安全要求：签名密钥轮换、`jti` 重放保护、短期有效、时钟容差、限流、客户端吊销、绑定状态检查、audience 固定。禁止把通用 Octop Access Token 当作上述客户端凭据；禁止把服务管理密钥存进 Agent 可读配置。

### 7.3 个人 PAT API

```http
GET    /api/me/personal-access-tokens
POST   /api/me/personal-access-tokens
POST   /api/me/personal-access-tokens/{tokenId}/rotate
DELETE /api/me/personal-access-tokens/{tokenId}
```

创建请求示例：

```json
{
  "name": "个人只读检索",
  "scopes": ["SEARCH", "QUERY", "LIST", "READ_OBJECT"],
  "expiresAt": "2027-01-26",
  "reason": "本地数据分析脚本"
}
```

服务端必须验证到期日不为空、未超过管理员策略、scope 在允许集合内。PAT 的资源绑定只能继承或收窄本人当前授权，不能新增 Principal Grant。完整 secret 仅在成功创建/轮换响应中出现一次。

### 7.4 服务主体和 Key 管理 API

优先扩展现有 External Service Admin API：

```http
POST   /api/external/admin/principals
PUT    /api/external/admin/principals/{principalId}
POST   /api/external/admin/principals/{principalId}/keys
POST   /api/external/admin/keys/{keyId}/rotate
POST   /api/external/admin/keys/{keyId}/revoke
PUT    /api/external/admin/principals/{principalId}/knowledge-grants
PUT    /api/external/admin/principals/{principalId}/memory-profile
PUT    /api/external/admin/principals/{principalId}/memory-grants
```

第一阶段调用者是 AKB 管理员。创建主体、配置资源 Grant、Memory Profile 和创建 Key 分为可审计步骤；如需一键向导，服务端仍应分别记录这些操作的 before/after 和操作者。

## 8. Octop 改造设计

### 8.1 管理员绑定 Agent/Worker

Octop 组织管理员在虚拟员工知识身份设置中选择已由 AKB 管理员创建的 Principal，并录入/选择加密保存的 Service Key。初版可以通过人工安全流程交付 Key；自动 provisioning 后续迭代。

绑定页面显示：AKB 实例、Principal code、Agent/Worker、身份模式、Key prefix/到期时间、KB 和 Memory 授权摘要、状态/版本、最近诊断结果。Octop 不允许用户自行填写一个 Principal ID 就推导出权限，也不展示明文 Key。

### 8.2 交互任务

1. Octop 验证当前用户会话、组织成员权限、Agent 所有权/共享状态、场景权限和审批状态。
2. Octop 根据 Worker 绑定读取 AGENT Principal 和加密 credential reference。
3. 若操作只需 Agent 公共授权，使用 `AGENT_SERVICE`。
4. 若需要受用户授权约束，Octop 后端依据已验证会话中的不可变 `octop_user_id` 请求 AKB 交换短期委托令牌；AKB 通过 Identity Binding 得到 USER Principal。
5. AKB 按 Agent ∩ 用户的授权及当前 ACL 执行请求；Octop 不得缓存授权结论作为永久放行依据。
6. 日志串联 Octop user、Agent、task、approval、AKB principal、credential ID、scope、trace ID；不记录任何密钥/token 明文。

### 8.3 Cron/后台任务

后台任务使用 `AGENT_SERVICE`，不需要或伪造用户身份。任务需绑定负责人、审批和授权目的。若数据只允许某个员工访问，Cron 必须拒绝该数据请求或通过受控的用户委托执行上下文获取短期令牌；不得静默借用任务创建人的长期 PAT。

### 8.4 Memory 候选与回写

- 运行期 Recall/自动 Capture 分别受 Octop 的 `useMemory`、`captureMemory` 设置控制，默认关闭。
- 候选先写入 Octop 待审核状态，经理审核通过后才调用 AKB Memory 写接口。
- 回写始终使用虚拟员工绑定的 AKB Agent Principal/Service Key（必要时再叠加获准的用户委托身份），不使用候选创建者的个人 Connector Key。
- AKB 要求 `MEMORY_MANAGE` scope、主体 Memory Profile/Grant 和目标 namespace/Agent scope 均匹配。
- 所有写入记载申请者、审核者、源 task、目标 Principal、Memory scope、AKB 返回 ID 和 trace ID；删除/Forget 也有完整审计。

## 9. 管理界面与产品体验

### 9.1 AKB 个人 PAT

“个人设置 → 个人访问令牌”支持创建、scope 选择、强制到期日、理由、一次性 secret 展示、复制提示、列表、最后使用信息和撤销。默认勾选只读检索集合；`MEMORY_MANAGE`、`INGEST`、`WRITE` 默认关闭并显示申请/审批说明。过期策略由服务端强制，前端只提供有效日期选择。

### 9.2 AKB 管理端身份中心

建议以“外部身份与凭据”承载：

- 服务主体：AGENT/APPLICATION、来源、负责人、状态、Key 生命周期。
- 用户绑定：Octop 实例、外部用户 ID、AKB 目标主体、待审/生效/暂停状态。
- 资源权限：Knowledge Grant 与 Memory Profile/Grant 分开展示。
- 诊断和审计：最近调用、身份映射结果、scope 拒绝、到期、IP 命中及操作者。

### 9.3 Octop Worker 设置

按“知识身份”与“Memory”展示绑定关系。提供 Principal 选择/校验、凭据录入、连接诊断、授权摘要、切换/解绑和到期提醒。诊断只返回可操作错误代码，例如“主体停用”“ID 未绑定”“scope 不足”“Memory scope 不匹配”，不返回上游堆栈或秘钥。

## 10. 生命周期、审计与错误处理

| 事件                 | 必须行为                                                              |
| ------------------ | ----------------------------------------------------------------- |
| PAT 到期/撤销          | 该 PAT 立即拒绝；其他凭据不受影响；保留最小审计记录                                      |
| 用户禁用               | PAT、登录会话和该用户的委托身份交换失效                                             |
| Octop 用户绑定暂停/解绑    | 后续新 token exchange 立即失败；已签发的极短令牌到期时间受限，并可通过绑定版本/撤销版本使其失效          |
| Agent Principal 停用 | 该主体所有 Service Key 和 Memory 调用失败                                   |
| Service Key 轮换     | 新 Key 仍指向同一 Principal；旧 Key 按轮换策略吊销；Memory identity 不变            |
| Service Key 到期     | API/MCP 认证拒绝并返回可诊断状态；不能静默延长                                       |
| Knowledge Grant 变更 | AKB 后续请求按最新 grant 判定，不依赖 Octop 老快照放行                              |
| Memory grant 撤销    | Recall、Capture、Delete 均按当前 Principal Grant 拒绝                     |
| `captureMemory` 关闭 | 不自动写入记忆，即使 `MEMORY_MANAGE` scope 存在                               |
| Octop 组织成员离职       | 撤销其 Octop 操作/审批权；根据绑定策略撤销该人的 ID binding 或只撤销组织角色，不影响 Agent 其他合法授权 |

错误响应应使用稳定错误码：`INVALID_CREDENTIAL`、`CREDENTIAL_EXPIRED`、`PRINCIPAL_DISABLED`、`IDENTITY_NOT_BOUND`、`IDENTITY_BINDING_SUSPENDED`、`ASSERTION_INVALID`、`ASSERTION_REPLAYED`、`SCOPE_DENIED`、`RESOURCE_GRANT_DENIED`、`MEMORY_DISABLED`、`MEMORY_SCOPE_DENIED`。对外错误不泄露主体是否存在等不必要敏感信息。

## 11. 迁移与兼容

### 11.1 盘点和兼容原则

- 先只读盘点现存 API Key、Principal、KB Grant、scope、Memory namespace/Agent code、Memory grants、Octop Connector 引用及最近使用情况，不导出 secret 明文。
- 按 Key 的现有 Principal/Agent 配置与 Octop Worker 记录人工确认一对一映射。
- 旧 Key 的 Memory namespace/Agent identity 迁到 Principal Profile；旧授权迁到 Principal Memory Grant，并对数量/资源/权限做迁移前后核对。
- 同一主体的多把 Key 合并为共享主体身份是预期行为；不同 Agent 的 Key 不得误合并到同一 Principal。
- 旧接口继续工作于兼容期，但所有鉴权最终落到 Principal 和最新 Grant；未知/不一致配置不得默认放宽权限。
- 个人 PAT 迁移时必须补齐过期时间及 owner；无法判定个人归属的旧 Key 不自动转换为 PAT。

### 11.2 分步迁移

1. **Schema 扩展**：增加 credential kind、PAT owner/expiry、身份绑定、Octop trust client、Principal Memory Profile/Grant 和审计字段；旧列暂留。
2. **管理面先行**：AKB 管理员可创建 Agent/Application 主体、关联绑定信息、设置 KB 和 Memory Grant、签发 Service Key。
3. **Memory 迁移**：只读 dry-run 生成旧 Key → Principal Profile/Grant 映射和冲突报告；管理员确认后执行幂等迁移；核对后启用 Principal 级读写。
4. **PAT 上线**：创建个人 PAT 页面/API，强制到期和默认只读 scope；旧 Key 不自动归入用户 PAT。
5. **Octop 单 Worker 灰度**：手工录入 AKB 主体和密钥 Secret reference，运行 Agent Service 模式并验证检索及 Memory 审核回写。
6. **绑定接口**：发布管理员审核的 Octop user ID binding API；完成冲突、解绑和审计验证。
7. **委托身份交换**：配置 Octop 实例 trust，完成短期断言、签名校验、重放保护、绑定查询和交集授权；未上线则继续只支持 Service 模式。
8. **扩面与清理**：按 Agent/组织迁移；停用旧 Key 依赖列和兼容逻辑，清理旧 scope 命名需另行版本化。
9. **自动 provisioning（后续）**：在前述安全边界稳定后，再实现 Octop 申请 AKB 主体/绑定与密钥发放的自动化审批流。

### 11.3 回滚

- 以可恢复调用为目的，不恢复已吊销凭据或已撤销授权。
- 新绑定/交换失败可把 Agent 切回已人工确认的兼容 Service Key；不要创建跨 Agent 共享的高权限 Key。
- 数据库新增列先兼容旧读取；Memory 迁移使用可重复 dry-run、映射快照和迁移版本记录。
- 出现权限扩大、映射冲突或身份审计缺失时暂停扩面，撤销新 Key/绑定并回到旧调用路径。

## 12. 验证和验收

所有数据库迁移和集成测试按工程既有 Docker 环境执行；跨 AKB/Octop 的鉴权测试使用 Docker/Testcontainers 或对应集成环境，不能只依赖 Mock 通过。

| 验收用例                                       | 预期                                             |
| ------------------------------------------ | ---------------------------------------------- |
| 普通 PAT 创建时缺少 expiry                        | 服务端拒绝                                          |
| PAT 请求超出允许最大期限                             | 服务端拒绝并返回策略错误                                   |
| PAT 默认 scope 检查                            | 只有 SEARCH/QUERY/LIST/READ_OBJECT               |
| PAT 调用 Memory、INGEST 或 WRITE               | 默认拒绝；获批后仍受资源 Grant 约束                          |
| 服务主体尝试网页登录/创建用户 session                    | 明确拒绝                                           |
| Service Key scope 足够但 Principal 无 KB Grant | 拒绝                                             |
| Principal 有 KB Grant 但 Key 无对应 API scope   | 拒绝                                             |
| AKB/Octop 两侧用户 ID 未绑定                      | 委托 token exchange 拒绝；不能按 email 自动匹配            |
| Octop 普通登录 JWT 直接送 AKB                     | 拒绝，不作为 AKB OIDC Token                          |
| 断言 issuer/audience/签名/时效错误                 | token exchange 拒绝                              |
| 断言 `jti` 重放                                | 拒绝并审计                                          |
| 已绑定 Octop user + 有效 Agent + 双方 grant 均满足   | 委托调用通过，且结果仍执行对象 ACL                            |
| Agent 授权或用户授权任一侧不足                         | 委托调用拒绝，不能并集放行                                  |
| Octop 请求体伪造 user ID/Agent ID               | 不改变服务端解析出的身份；拒绝或忽略并审计                          |
| 一个 Agent 的 Key 配到另一个 Agent binding         | Principal/binding 不匹配时拒绝                       |
| 轮换 Service Key                             | 新 Key principal/Memory identity 不变；旧 Key 按策略失效 |
| Key 删除但 Principal 存续                       | Principal 其他有效 Key 对应身份/Memory grants 不漂移      |
| Principal 级 Memory grant 缺失                | MEMORY_MANAGE 调用拒绝                             |
| 有 MEMORY_MANAGE 但 captureMemory 关闭         | 不写入 Memory                                     |
| Memory 审核拒绝或未审核                            | Octop 不发起 AKB 写入                               |
| Memory 候选审核通过                              | 使用 Worker 绑定的 Agent Principal 写入，审计含申请人和审核人    |
| identity binding 暂停/解绑                     | 后续 token exchange 拒绝；无长期旧 token 可继续使用          |
| 不可信代理伪造 X-Forwarded-For                    | 不能伪造可信来源 IP 或身份                                |
| 日志/异常/监控检查                                 | 不出现完整 Key、断言、Bearer token 或 Memory 正文          |
| 迁移重复执行/回滚演练                                | 映射不重复、授权不扩大、旧列兼容策略明确                           |

验收需包含 AKB 单元/集成/数据库迁移测试、Octop API/服务测试、MCP 联调、身份绑定安全测试、Memory 审核闭环，以及至少一个真实部署拓扑的端到端测试。

## 13. 实施阶段

| 阶段            | AKB                                                                              | Octop                                     | 阶段完成标准                                      |
| ------------- | -------------------------------------------------------------------------------- | ----------------------------------------- | ------------------------------------------- |
| P0 设计冻结       | 定义 Principal 类型、scope、到期策略、身份绑定 API 和断言信任机制                                      | 确定稳定实例 ID、用户/Agent ID、绑定 UI 和 Secret 保存方式 | 本文关键方案确认；API/DB 方案评审通过                      |
| P1 服务主体与 PAT  | 管理员创建/绑定 Agent/Application Principal；Service Key policy；USER PAT 强制到期和默认只读 scope | Worker 绑定单个 AKB Principal 与加密 Key 引用      | PAT 与 AGENT_SERVICE 正反向验收；服务身份无 Web 登录      |
| P2 Memory 主体化 | 主体级 Memory Profile/Grant；`MEMORY_MANAGE` scope；幂等迁移工具                            | 回写使用 Worker Principal；保留申请人/经理审计          | Key 轮换不变更 Memory namespace/身份；Memory 授权测试通过 |
| P3 ID 绑定      | 管理员绑定/审批/解绑 API 与审计                                                              | 展示 Octop 用户绑定状态、诊断                        | 不自动按邮箱匹配；解绑/停用立即阻断新的委托交换                    |
| P4 委托运行       | Octop trust client、断言校验、token exchange、重放保护、权限交集                                 | 后端签发短时断言并按请求调用                            | 不同 IdP 可安全关联；用户与 Agent 权限交集测试通过             |
| P5 推广与自动化     | 监控、审计、迁移收尾、策略运营                                                                  | 多组织/多 Agent 扩面；后续自动 provisioning          | 灰度、回滚、Docker 集成测试和安全评审通过                    |

### 13.1 建议的首轮交付顺序

先完成 P1 + P2 的可用闭环：AKB 管理员创建服务主体及最小权限 Key；用户可以创建强制到期的默认只读 PAT；Memory identity/grant 迁到主体；Octop Worker 使用稳定主体完成只读检索和审核后的 Memory 写入。接着增加 AKB 管理员用户 ID binding API；最后开发不同 IdP 下的短时断言和委托令牌交换。

这样第一阶段能支持无人值守 Agent 和稳定 Memory 归属，同时在委托身份能力未完成前不会错误假设共享 IdP。

## 14. 设计风险与控制

| 风险                        | 控制                                                                              |
| ------------------------- | ------------------------------------------------------------------------------- |
| Octop 服务端错误地为攻击者断言任意用户    | Octop 仅从已认证会话读取不可变 user ID；AKB 限定已注册实例和已批准绑定；令牌短时并审计；客户端密钥最小范围并可撤销              |
| 管理员把 Octop 用户绑定到错误 AKB 用户 | Pending/审批、显示两侧可核对标识、唯一性校验、操作理由、双人复核可配置、支持快速暂停和审计                               |
| `MEMORY_MANAGE` 权限合并后过宽   | 默认禁用、单独申请、namespace/Agent scope 限制、平台开关和 `useMemory/captureMemory` 控制，所有删除类操作审计 |
| PAT 到期策略妨碍自动任务            | PAT 不用于生产 Agent；服务任务使用单独 Service Key 策略及轮换告警                                    |
| Service Key 长期存在          | 管理员策略、到期/轮换提醒、负责人、调用异常监控、即时吊销；具体生命周期可按部署安全基线配置                                  |
| 迁移把不同 Agent 的 Memory 混在一起 | dry-run、显式映射、冲突中止、逐 Agent 核对，未知归属不自动迁移                                          |
| Octop 缓存旧授权               | AKB 每次访问资源时重新检查当前 Principal Grant/ACL；缓存只用于非安全 UI 提示                            |
| 交换令牌被重放                   | 短期、jti 防重放、固定 audience、签名密钥轮换和 trace 审计                                         |

## 15. 决策记录与待评审细节

### 15.1 已确认，无需再次确认

- 两套系统不共用 OIDC IdP；AKB 提供身份 ID 绑定能力。
- 服务身份禁止网页登录，只用于 API/MCP。
- 普通 PAT 强制到期；Service Key 独立管理策略。
- 个人 PAT 默认只开放检索只读 scope，Memory/写入权限单独申请。
- Memory 外部权限合并为 `MEMORY_MANAGE`；namespace/Agent identity 与授权迁移到 Principal。
- 第一阶段由 AKB 管理员创建并绑定服务身份；自动 provisioning 后续实施。

### 15.2 进入实施前应在技术评审中定值的参数

以下属于实施参数，不改变已确认的产品方向：

1. 普通 PAT 默认有效期和管理员可配置最大有效期；本文建议 90 天默认、365 天上限。
2. Service Key 是否强制有 expiry、默认有效期/最大有效期、轮换周期和紧急延长期审批规则。
3. Octop 断言采用 mTLS、JWKS 非对称签名或二者组合；建议优先非对称签名并支持密钥轮换，管理面用 mTLS 可进一步强化。
4. Token exchange 是否发放短期 bearer token，或仅在 AKB gateway 内部建立等价 server-side auth context；无论实现选择，必须满足短时、固定 audience、重放保护和实时授权。
5. Octop 用户到 AKB 用户是全局按 `(instance,user)` 唯一，还是允许按 AKB 租户/组织区分；默认建议同一 AKB 实例唯一映射，只有确有多租户需求才将 tenant 纳入唯一键。
6. `MEMORY_MANAGE` 的删除操作是否要求更高层业务审批；权限 scope 仍统一，但可由独立资源策略要求审批。

## 16. 参考代码位置

### AKB

- `backend/src/main/java/com/jh/knowledgeos/domain/ApiKey.java`
- `backend/src/main/java/com/jh/knowledgeos/domain/ExternalPrincipal.java`
- `backend/src/main/java/com/jh/knowledgeos/service/ExternalAccessService.java`
- `backend/src/main/java/com/jh/knowledgeos/service/DelegatedIdentityService.java`
- `backend/src/main/java/com/jh/knowledgeos/service/ApiKeyService.java`
- `backend/src/main/java/com/jh/knowledgeos/service/AgentMemoryScopeAccessService.java`
- `backend/src/main/java/com/jh/knowledgeos/controller/ExternalServiceAdminController.java`
- `backend/src/main/resources/db/migration/V61__external_principal_kb_grants.sql`
- `backend/src/main/resources/db/migration/V64__delegated_employee_identity.sql`
- `docs/api/EXTERNAL-API.md`
- `docs/api/AGENT-MEMORY.md`
- `docs/api/OCTOP-INTEGRATION.md`

### Octop

- `src/octop/api/deps.py`
- `src/octop/infra/db/repos/users.py`
- `src/octop/infra/db/repos/agents.py`
- `src/octop/infra/connectors/service.py`
- `src/octop/infra/digital_workers/service.py`
- `src/octop/infra/digital_workers/memory.py`
- `src/octop/infra/scenes/service.py`
- `src/octop/infra/knowledge/akb_client.py`
- `docs/akb-integration.md`

## 17. 评审结论与实施入口

V1.1 已将六项用户决策纳入目标架构，并补齐不同 IdP 下的绑定与委托信任链、PAT/Service Key 生命周期、`MEMORY_MANAGE` 组合授权、主体级 Memory 迁移、AKB 管理员首期流程、Octop 绑定、测试和回滚方案。

**当前状态仍是“设计待确认”。** 请评审本版中的身份绑定审批流程、Octop 服务端断言/token exchange 信任方式、Memory 组合权限边界和 PAT/Service Key 生命周期参数。设计确认后再按 P0—P5 进入代码改造；本轮没有修改 AKB 或 Octop 代码。
