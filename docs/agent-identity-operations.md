# Agent 身份认证与运行维护

Agent UUID 是公开标识，服务 Key 是应用接入凭证，Agent 私钥证明请求方持有已登记密钥。终端名称、会话和调用标识是签名方声明的诊断信息，不构成硬件或人员身份认证，也不参与独立授权。

## 认证与授权

普通服务凭证按服务权限直接执行；Agent 凭证仅允许在没有 Agent 签名时初始化 MCP 和发现工具，业务工具必须验签。签名覆盖实际方法、路径、正文、服务凭证摘要及策略坐标。服务端登记的绑定和公钥决定身份，工具参数不能指定身份。

简化接入默认 `SERVICE_SCOPE`：所有已登记 Agent 共享应用获准的知识库范围；若要求逐 Agent 隔离，应使用 `AGENT_INTERSECTION`，并配置 Agent 自身授权。页面明确展示当前模式，不把“登记 Agent”表述成已完成独立授权。

服务端自检仅检查配置，包括生效公钥和未过期服务 Key；不能证明客户端私钥正确，也不能代替真实检索。客户端应分别验证服务连接、Agent 身份和具体知识库。

## 配额与防重放

有效服务 Key 的滑动窗口预算在昂贵验签前扣除，无效签名也计入，避免持续消耗 RSA 与数据库资源。验签成功后再扣除独立 Agent 配额；Agent 桶按策略和 Agent 主体计算，轮换服务 Key 不重置它。默认每 Agent 每分钟 30 次，可通过 `knowledge.external.agent-rate-limit-per-minute` 调整（非正数关闭该附加预算）。服务 Key 原有总额度仍生效，独立配额不增加服务总额度，也不是公平调度的保证。

配额目前按应用实例内存计算，多实例部署应在网关集中限流；防重放记录则存数据库、跨实例共享。不要将本地配额当作全局配额。

防重放消费写入失败仍拒绝请求；清理移到后台，每轮最多 10 批、每批 1000 条，默认间隔 60 秒。可配置 `knowledge.external.replay-cleanup-interval-ms`。清理故障不会使已认证业务失败；积压时应告警、缩短间隔或调整部署吞吐。

Prometheus 指标：`akb_workload_replay_expired_backlog`、`akb_workload_replay_cleanup_failures_total`。

## 诊断与审计

对客户端保持统一身份证明错误；内部日志 `agent_proof_rejected` 记录 Key ID、策略 ID、受限关联 ID 和服务端原因码：PROOF_FORMAT、AGENT_BINDING、KEY_REGISTRATION、SIGNATURE、PROOF_EXPIRY、KEY_STATUS、POLICY_BINDING、CREDENTIAL_BINDING、REQUEST_BINDING、INVOCATION_FORMAT、REPLAY。不记录服务 Key、完整断言、私钥、请求正文或未验证的声明。

关联 ID 来自 `X-Trace-Id` 的安全格式值（最多 128 个字母、数字、下划线或连字符），不合格式显示 unavailable。MCP 审计会保留已经验证有效的服务 Key 归属，即使后续 Agent 验签失败；该归属不代表 Agent 身份可信。限流与认证存储故障分别记为 429 和 503。

## 本轮验收

2026-10-31：Maven 全量测试 253 项通过，0 失败、0 错误；包括验签前配额、Agent 独立配额、后台清理积压与故障指标、服务 Key 到期自检，以及 MCP 401/429/503 审计归属。新增页面通过 ESLint、Vue 类型检查及生产构建。以上使用合成测试凭证，不代表完成生产凭证联调。
