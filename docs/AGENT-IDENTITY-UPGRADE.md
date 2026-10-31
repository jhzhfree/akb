# Agent 身份接入升级 V79

本版将授权语义与简化界面分开：新连接使用 SERVICE_SCOPE；历史连接可以保留 AGENT_INTERSECTION。管理列表同时展示这两种模式，平台类型仅用于展示。私钥不上传 AKB；平台登记 Agent UUID、公钥并逐请求签名。

V78 已覆盖旧 simple_mode，无法仅从当前数据库准确恢复历史授权。V79 保留已执行迁移校验和，新增 authorization_mode，并将既有未撤销连接暂停为 REVIEW_REQUIRED。**升级会暂停历史接入，必须安排管理员复核窗口。** 不要先部署新后端再假定历史连接仍可调用。

1. 升级前备份数据库与授权记录，保存现有 Agent 与平台权限。
2. 执行 V79 并部署匹配的后端、前端。
3. 管理员在「Agent 接入」核对每个历史连接，优先恢复原有平台与 Agent 权限交集；只有明确需要共享权限时才选择 SERVICE_SCOPE，并填写依据。
4. 确认不会自动启用。核对自检、主体及公钥后单独启用，测试两个 Agent 的 REST/MCP 调用。
5. 检查审计中的平台 Key 名称、Agent UUID/主体、kid、会话与调用标识。

未经复核的连接即便被误设 ACTIVE 也不能通过认证；REVIEW_REQUIRED 不能通过普通启用接口绕过。已撤销连接不会因迁移复活。重复执行不会清空复核结果。

若不能找到升级前记录，请保持暂停，不要猜测授权模式。V78 已删除的 V1 记录不能由本次迁移重建，需要从备份调查。不要编辑已执行的 Flyway SQL 或手工改校验和。

新连接默认只读取指定知识库的无权限标签内容。简化模式同连接下 Agent 共享平台权限；不同可读范围可以创建不同连接，历史交集模式继续应用现有 Agent 授权。

密钥生成复用 Octop/tools/agent-keygen 的独立本地工具，支持离线 CLI、Node API 和平台 SDK；Octop 页面宿主生成只返回公钥，不返回私钥。OpenCode 等平台可使用工具初始化身份，无需依赖 Octop 页面。

## 联调验证命令

在两个仓库的开发机上先构建 Octop SDK，然后从 akb/backend 运行：

```sh
mvn -Pintegration-test -Dit.test=AgentIdentityHttpIT \
  -Dagent.sdk.bridge=/absolute/Octop/integrations/agent-identity-sdk/dist/bridge.js verify
```

测试会创建隔离 MySQL、启动真实 akb HTTP 服务，通过实际 Node 工具生成两名 Agent 的独立密钥，完成 REST/MCP 知识库列表访问、区分审计和撤销拒绝。未指定 bridge 时该跨项目测试跳过；它不需要真实 LLM，但也不验证 AI 检索内容。历史升级与新装数据库测试：`mvn -Pintegration-test -Dit.test=AgentAuthorizationMigrationIT,DatabaseMigrationIT verify`。
