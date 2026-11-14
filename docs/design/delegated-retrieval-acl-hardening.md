# 委托检索内容 ACL 安全切片（2026-11-14）

## 实际改动与边界

本切片修复既有检索路径，不启用 Octop 知识增强规划，不新增跨 IdP 协议，不将 capability 探测当成授权。

- `RetrievalAuthorizationService` 对已认证的 `delegatedPrincipalId` 强制服务端标签交集，不受 CLIENT/SHADOW 兼容模式影响。缺少服务器 mapper、KB 或服务主体上下文拒绝；Agent 或用户未配置标签策略按仅公开处理，不能继承另一方的较宽策略。KB grant 是否存在仍由 ExternalAccessService 强制校验，PUBLIC_ONLY 绝不是无需 KB 授权。
- `ExternalKnowledgeFacade` 向 AI 服务保留空标签数组。`null` 表示无内容标签约束；`[]` 表示仅公开资料；非空表示公开资料加匹配标签资料。之前省略 `[]` 会经 QueryRequest 默认值变为 `None`，导致全文、向量和本地混合检索失去该限制。
- 委托用户请求共享 Agent 记忆明确失败，不自动注入、返回空记忆或降级。记忆独立的逐用户授权尚未实现。直接传给阻塞/流式内部方法的非空记忆上下文也受同一限制。

## 升级与兼容

普通非委托 CLIENT/SHADOW 的模式选择及 null/非空语义保留。但显式空数组曾经错误地变成无约束；此次所有公共 payload 入口恢复 PUBLIC_ONLY，可能减少普通调用方结果。这是有意的安全收紧，不能通过改回 null 恢复权限。

委托调用方必须由 AKB 管理员明确配置 Agent 和用户 KB grant/标签。不要用用户名或邮箱猜测绑定。若用户没有标签但有 KB grant，仅公开资料可见；缺少 KB grant 则整个 KB 不可访问。调用方不得从请求参数直接赋值 delegatedPrincipalId。

委托调用方应关闭 includeMemory；若业务需要记忆，必须先补独立用户授权，不得改用共享服务身份。普通非委托记忆行为不变。旧 V2 workload 仍拒绝同时携带 X-Octop-Identity；不得为接入用户身份而移除拒绝或自动切换 legacy Agent 凭据。

## 验证范围

Java 定向测试使用真实授权服务和 Facade，repository/身份验证器/AI 传输为测试替身：同 KB 共享 Agent 下用户 A/B 分离、A 标签撤回后 B 不变、缺失策略、CLIENT/SHADOW 兼容、请求参数不能设置委托主体、认证验证器拒绝即终止、query/search/standard retrieval/stream/context 五入口 JSON 空列表保留、委托记忆阻断。

Python 离线测试使用真实 QueryRequest JSON 往返、ES/Milvus filter 构造、本地 hybrid.allowed 逐文档过滤；无实际向量服务/全文服务、无模型调用，测试运行禁止 socket 连接。重用同一文档集合验证授权从 A 变 [] 时不复用旧授权，B 保持可见。

这些不是签名密钥/真实 JWT、数据库重放并发、不同 IdP、真实双用户登录或实部署撤权验收。不能据此宣称所有历史/详情/图谱出口都已通过完整内容 ACL；这些出口须独立审计和封闭。

## 下一协议的最小安全契约（尚未实施）

保留当前 V2 proof：绑定方法、原始目标路径及 query、原始 body SHA-256、服务凭据摘要、policy/application/Agent、短时有效期和持久化一次性 JTI。新用户委托使用明确独立 profile/入口，默认禁用，不扩展旧协议含义。

用户证明必须由受信 Octop 服务签署，绑定明确 issuer+instance+subject、audience、application/policy、Agent、同次 V2 proof 摘要或标识及 request binding；不能仅签 user ID。使用管理员显式用户映射，每请求重查 ACTIVE 状态及 KB/标签交集。新协议拒绝 SERVICE_SCOPE 跳过 Agent 授权，拒绝未知版本和重复/混合身份头；不能失败后回落共享 Key。

验收至少覆盖：用户/Agent/app/proof互换、方法/target/body改变、过期/未来时间/错audience、同JTI串行与并发重放、数据库失败关闭；同KB public/A/B内容在全文/向量/混合/逐块详情/图谱/历史所有出口一致；A绑定/KB grant/标签撤销及缓存更新不影响B；模型调用前重验撤权，发送后无法撤回已发送内容必须如实说明。身份签名成功、capability configured、探测成功均不能替代这些授权检查。
