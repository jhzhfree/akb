# AKB–Octop 身份认证与授权改造实施说明

版本：P0–P5 AKB 侧实施稿
适用分支：`feature/akb-identity-auth-p0-p5`
基线：AKB `feature/octop-mcp-integration`
范围：AKB 服务端和 AKB API 契约；Octop/Agent Memory 代码不在本次修改范围内。

## 1. 身份模型

AKB 和 Octop 保留各自的用户目录及 OIDC IdP。AKB 不验证 Octop 普通 OIDC Token，也不通过邮箱、名称或 IP 自动合并用户。AKB 管理员显式维护 Octop 外部 ID 到 AKB `USER` Principal 的绑定；Agent 的稳定身份绑定在 `AGENT` Principal 上。

| 对象 | 用途 | 是否可登录 AKB Web |
| --- | --- | --- |
| `USER` Principal | AKB 人类用户的外部权限主体；可绑定本地 AKB user 和 Octop 外部身份 | 不能单独登录；本地登录仍使用 `t_user` |
| `AGENT` Principal | Octop Agent/虚拟员工的稳定机器身份 | 否，只用于服务端 API/MCP |
| `APPLICATION` Principal | 其他外部应用的稳定机器身份 | 否，只用于服务端 API/MCP |
| `USER_PAT` | 用户自助创建、绑定一个 AKB 用户及其 USER Principal 的短期凭据 | 不能用于 Web Session |
| `SERVICE_KEY` | 管理员给 AGENT/APPLICATION Principal 签发、可单独轮换的服务凭据 | 不能用于 Web Session |

API Key 只承担认证凭据角色。知识库授权、Memory namespace、Agent code 和 Memory scope grant 归属 Principal；轮换 Key 不改变调用身份或 Memory 身份。

## 2. PAT 和 Service Key 策略

### 2.1 个人 PAT

- `POST /api/me/personal-access-tokens` 创建；`expiresAt` 必填。
- 默认 scope 精确为 `SEARCH`、`QUERY`、`LIST`、`READ_OBJECT`。
- 用户自助创建暂不允许 `MEMORY_MANAGE`、`INGEST`、`WRITE` 或 legacy 写入 scope；超出范围会被服务端拒绝。
- 管理员审批后可通过 `PUT /api/external/admin/personal-access-tokens/{id}/scopes` 增加/调整 scope；必须填写原因并写入审计表。
- 强制有效期不超过 365 天。前端/调用方应提供明确到期日期；建议默认值为 90 天。
- 创建时完整 secret 只返回一次；列表只返回前缀和元信息；用户只能撤销自己的 PAT。
- Owner 用户停用、Principal 停用、Token 过期或撤销都会使后续请求失败。

个人 PAT 的授权仍受 AKB Principal 的知识库/资料标签授权约束。PAT scope 只限制 API 动作，不自动创建知识库 Grant。

### 2.2 服务 Key

- 创建流程为：AKB 管理员创建 AGENT/APPLICATION Principal，设置外部身份、Memory Profile，再给 Principal 签发一个或多个 Service Key。
- 管理接口：`POST /api/external/admin/principals`、`POST /api/external/admin/principals/{id}/keys`。
- 默认要求 Service Key 设置到期时间，最长 365 天；可通过 `EXTERNAL_SERVICE_KEY_EXPIRY_REQUIRED` 和 `EXTERNAL_SERVICE_KEY_MAX_LIFETIME_DAYS` 配置部署策略。
- 旧 `/api/external/admin/keys` 接口仍可创建 APPLICATION Principal 与 Service Key，并沿用现有管理操作；新 Agent 身份建议走 Principal 独立创建流程。
- Service Key 的 scope、知识库 Grant、Memory scope Grant 分别管理。单个 Key 撤销不影响同一 Principal 的其他 Key；Principal 停用会使其凭据失效。

## 3. 跨 IdP 身份绑定和委托请求

### 3.1 管理员绑定 Octop 用户

AKB 管理员先创建/选择 AKB `USER` Principal，再调用：

```http
POST /api/external/admin/identity-bindings/octop
Content-Type: application/json
```

```json
{
  "providerInstanceId": "octop-prod-01",
  "externalUserId": "octop-immutable-user-id",
  "principalId": 123,
  "reason": "工单或审批说明"
}
```

可通过 `GET /api/external/admin/identity-bindings/octop` 查询，通过 `POST /api/external/admin/identity-bindings/octop/{id}/revoke` 撤销。一个 Octop 实例中的外部用户 ID 只能有一个 ACTIVE 绑定。旧记录先撤销，重新绑定时复用该记录并留审计。

若这个外部身份应与 AKB 原生登录用户一致，管理员可调用 `PUT /api/external/admin/principals/{id}/owner-user` 显式关联 `t_user`。系统不按邮箱猜测或自动绑定。

### 3.2 每请求短期签名断言

Octop 和 AKB 使用不同 IdP，因此 Octop 后端不能把普通用户 Token 直接转发给 AKB。本版 AKB 运行时契约采用 `X-API-Key` 加 `X-Octop-Identity`：

```http
X-API-Key: <绑定到 AGENT Principal 的 Service Key>
X-Octop-Identity: <Octop 后端签名的 JWT assertion>
```

断言至少包含：

| JWT 项 | 含义和校验 |
| --- | --- |
| `iss` | 必须等于 `EXTERNAL_OCTOP_IDENTITY_ISSUER` |
| `aud` | 必须包含 `EXTERNAL_OCTOP_IDENTITY_AUDIENCE` |
| `sub` | Octop 不可变用户 ID；通过 AKB identity binding 映射到 USER Principal |
| `octop_instance_id` | 必须等于 AKB 配置实例 ID及 Agent Principal 外部实例 ID |
| `agent_id` | 必须匹配 Service Key 对应 Agent Principal 的外部 subject ID |
| `iat` / `exp` | 必填；有效期大于 0 且不超过 5 分钟，允许最多 30 秒时钟偏差 |
| `jti` | 必填且一次性；重复断言拒绝，已过期 jti 周期清理 |

AKB 使用配置的 JWKS 验证签名及 issuer，再验证 assertion、服务 Key、Agent Principal、Octop 用户绑定和当前授权。KB 访问按 Agent Principal 与 USER Principal 权限交集执行。`AGENT_SERVICE` 后台请求只需 Service Key，不应伪造用户身份。

Octop 必须在服务端从已验证会话取得 `sub`，不能从模型输入、用户请求参数或普通 Header 取用户/Agent 身份。每次调用（含重试）需要新的 `jti`。请求同时带通用 `Authorization` Bearer Token 和 Octop assertion 会被拒绝。

AKB 配置项：

```yaml
knowledge:
  external:
    octop-identity:
      enabled: false
      issuer: ""
      jwk-set-uri: ""
      audience: ""
      instance-id: ""
```

对应环境变量：`EXTERNAL_OCTOP_IDENTITY_ENABLED`、`EXTERNAL_OCTOP_IDENTITY_ISSUER`、`EXTERNAL_OCTOP_IDENTITY_JWK_SET_URI`、`EXTERNAL_OCTOP_IDENTITY_AUDIENCE`、`EXTERNAL_OCTOP_IDENTITY_INSTANCE_ID`。默认关闭；只有完成 JWKS、实例 ID 和主体映射配置后才能启用。原有单 OIDC issuer 委托配置保留向后兼容，但不适用于 Octop 独立 IdP。

## 4. Memory 主体化

新 Service Principal 创建时建立 Principal Memory Profile：稳定 namespace、Agent code 和默认自身 `AGENT` scope。新签发的 Key 不再写入 namespace/Agent identity；查询时优先读取 Principal Profile，旧字段仅做兼容回退。

对外授权 scope 统一为 `MEMORY_MANAGE`，覆盖 Recall、Capture/Write、Delete/Forget。服务端仍根据具体操作执行 read/write 判别；共享 Memory scope grant 继续限制具体 namespace、scope type/code 及读写边界。要求同时满足：

1. 平台 `agent-memory-enabled` 已启用。
2. Credential 有 `MEMORY_MANAGE`（legacy 完整 READ+WRITE Memory scopes 在兼容期可被识别）。
3. Agent Principal 对目标 Memory scope 有授权。
4. 委托请求中，USER Principal 对同一 namespace/scope 也有授权。
5. Octop 的 `useMemory`/`captureMemory` 策略启用对应行为；默认仍关闭自动使用/捕获。

旧的 API Key Memory scope 表保留兼容查询。V72 迁移只把同一 Principal 下全部 Key 都具有且权限一致的共享 Memory grants 自动迁到 Principal；不一致的 grant 留在旧表并通过兼容路径执行，等待管理员复核，避免迁移时将 Key 权限并集扩大。Profile 冲突也不自动合并，旧 Key 字段保留回退。

## 5. AKB 主要 API

普通用户可从左侧账号菜单进入“个人访问令牌”，在控制台完成令牌创建、查看和撤销；创建时显示一次完整令牌并提供复制操作。前端只允许选择下列只读 scope，服务端仍会对请求 scope 再次校验：

- `SEARCH`、`QUERY`、`LIST`、`READ_OBJECT`

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/me/personal-access-tokens` | 当前用户 PAT 列表，不含明文 |
| POST | `/api/me/personal-access-tokens` | 创建带 scope/expiry 的 PAT，一次性返回 secret |
| DELETE | `/api/me/personal-access-tokens/{id}` | 撤销自己的 PAT |
| POST | `/api/external/admin/principals` | 管理员创建 AGENT/APPLICATION Principal |
| POST | `/api/external/admin/principals/{id}/keys` | 给服务 Principal 签发 Service Key |
| PUT | `/api/external/admin/principals/{id}/owner-user` | 人工核验后关联 AKB 本地 USER |
| POST | `/api/external/admin/identity-bindings/octop` | 建立 Octop 用户 ID 绑定 |
| GET | `/api/external/admin/identity-bindings/octop` | 查看 Octop 用户绑定 |
| POST | `/api/external/admin/identity-bindings/octop/{id}/revoke` | 撤销 Octop 用户绑定 |
| PUT | `/api/external/admin/personal-access-tokens/{id}/scopes` | 审批并调整个人 PAT scope，必须有原因 |

Memory 管理授权仍通过现有 `/api/external/admin/keys/{id}/memory-access` 管理端点操作，但授权记录已改为写入该 Key 所属 Principal。

## 6. V72 部署和迁移

Flyway 迁移 `V72__principal_credentials_identity_and_memory.sql` 会：

- 区分 `USER_PAT`/`SERVICE_KEY`，增加 PAT owner 关系。
- 增加 Principal 到 AKB 用户及外部 Agent identity 字段。
- 建立 Octop 用户 ID 绑定与审计表、PAT scope 审批审计表、assertion jti 防重放表。
- 创建 Principal Memory Profile/Grant，并迁移无歧义的历史配置。

发布前应在备份/预生产环境核对：

```sql
-- 同一 Principal 下 namespace/Agent identity 冲突，需要管理员确认。
SELECT principal_id,
       COUNT(DISTINCT COALESCE(memory_namespace, CONCAT('key:', id))) AS namespace_count,
       COUNT(DISTINCT COALESCE(memory_agent_code, key_code)) AS agent_count,
       COUNT(*) AS key_count
FROM t_api_key
WHERE principal_id IS NOT NULL
GROUP BY principal_id
HAVING namespace_count > 1 OR agent_count > 1;

-- 同一 Principal 的旧 Memory grant 未能安全自动合并时，核对后再配置 Principal grant。
SELECT k.principal_id, COUNT(DISTINCT s.api_key_id) AS keys_with_grant,
       s.namespace_code, s.scope_type, s.scope_code
FROM t_agent_memory_scope_access s
JOIN t_api_key k ON k.id = s.api_key_id
WHERE k.principal_id IS NOT NULL
GROUP BY k.principal_id, s.namespace_code, s.scope_type, s.scope_code;
```

旧列和旧 grant 表在兼容阶段保留。确认 Principal Profile/Grant 结果后再安排后续版本移除旧字段；不要在部署时手工清空旧 Key 或 Memory 授权。

## 7. 阶段完成情况与非目标

- **P0**：确认不同 IdP、Principal/credential 分离、 PAT scope 与 Memory 组合权限；本文作为 AKB API/配置实施契约。
- **P1**：AKB 用户 PAT 生命周期与 Service Key 管理 API。
- **P2**：Octop 外部用户 ID 映射及按请求验证的签名 assertion。
- **P3**：AKB 管理员创建/绑定 Agent 主体、签发服务 Key 的接口。
- **P4**：Principal 级 Memory Profile/Grant 和冲突安全迁移。
- **P5**：版本化 Flyway 迁移、兼容回退、部署核对 SQL 和接口文档。

本次不实现 Octop 客户端、Octop 前端或 Agent Memory 工程改动。Octop 调用端需要根据本文契约自行配置 `X-API-Key`、`X-Octop-Identity`，维护 assertion 签名/JWKS 轮换，并由其后续独立改造交付。
