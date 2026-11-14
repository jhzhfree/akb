# MCP 授权能力部署报告 v1

现有已认证 MCP initialize 的 capabilities.experimental 增加 `akb.authorization`，不增加免认证端点，也不改变工具发现、工具调用或身份验证规则。

| 字段 | 含义 |
|---|---|
| schema_version | 固定整数 1 |
| workload_v2_user_delegation_supported | false；仍拒绝混合 Authorization / X-Octop-Identity |
| legacy_agent_delegation_configured | Octop 旧 Agent 委托验证器是否配置启用，不代表已建立任何用户绑定 |
| external_content_tag_mode | 实际 RetrievalAuthorizationService 模式；非 Spring 构造未配置时 UNKNOWN |
| workload_content_tag_enforcement | true；仅说明 workload 的标签收窄行为，不代表双主体授权 |
| explicit_external_user_binding_required | true；跨系统用户需显式绑定 |
| empty_tag_grant_semantics | PUBLIC_ONLY，不是 DENY_ALL |
| capability_is_authorization | 固定 false |

报告只含部署策略，不查询/返回用户、Agent、KB、资源或 secret。报告不能证明任何请求授权，不能替代 scope、KB、身份和内容检查，不能自动打开 Octop 知识检索。旧客户端可忽略 experimental 扩展。

定向验证：`mvn -f backend/pom.xml -Dtest=McpAuthorizationCapabilitiesTest,McpServerServiceTest,WorkloadIdentityServiceTest,RetrievalAuthorizationServiceTest test`。
新增测试核验字段白名单、配置模式、兼容构造器、无业务查询；既有测试覆盖认证/调度/标签行为。没有真实索引与逐用户内容检索 E2E 验证。
