# 外部调用方上下文

MCP 与 `/api/v1/external/**` 使用 `CallerContextFilter` 拥有请求范围。`ExternalAccessService` 完成服务凭证认证、逐请求 Agent V2 验签后更新不可变 `VerifiedCaller` 快照。

工具可调用 `CallerContext.current()` 或需要 Agent 时调用 `requireAgent()`。不从工具参数接收身份，不持有服务 Key、私钥或完整 proof。普通服务凭证、仅完成发现握手的 Agent 凭证都不会得到虚构的 Agent 身份。

现有 scope、知识库范围、SERVICE_SCOPE / AGENT_INTERSECTION 策略保持原样。调用上下文不是授权替代物，业务代码仍须执行原授权方法。审计响应中的 `callerContext` 分别记录身份读取次数和实际 scope/资源授权决定，原工具响应不变。

SSE 工作通过 `CallerContext.wrap(Runnable)` 显式传递，工作完成或异常时清理线程。其他线程池任务不自动继承；有需要时显式包装。异步工作不能依赖请求对象在请求结束后继续存活。

公共过期测试向量 `backend/src/test/resources/akb-v2-vector.json` 与 opencode-identity、Octop 同源，用于检验 RSA 签名、路径前缀、UTF-8 请求字节、服务凭证摘要。测试不会放宽线上有效期与重放规则。无需数据库迁移或重新登记已有 V2 Agent。
