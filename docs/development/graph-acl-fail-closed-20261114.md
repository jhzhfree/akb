# 图谱读取 ACL 失败关闭切片（2026-11-14）

## 入口与安全语义

- 外部 REST `GET /graph/{code}` 和 MCP `graph_neighbors` 都从 `ExternalKnowledgeFacade.tagsFor` 得到授权标签，再进入 `KnowledgeReadService.graph`。
- 控制台图谱经 `AclContextResolver` 解析标签；知识详情也调用同一个 graph 方法作为附图。
- Java 三态保持不变：`null` 是显式无约束内部/管理员兼容；`[]` 是仅公开；非空集合为公开加命中授权标签的对象。外部 facade 将 null 归一为 []。

## 修复

1. 受限请求（所有非 null 标签，包括 []）遇到 Neo4j 无边、null 或异常时，抛出 `SecurityException`，错误标识 `ACL_FILTERED_GRAPH_UNAVAILABLE`。不调用没有 ACL 入参的 `AuthoritativeGraphNeighborhoodService.neighborhood`，不返回其中的节点、边或元数据。这里有意牺牲空图可用性，不推断“无边”等价于“可以绕过权限回源”。
2. 仅 null 标签保留原 authoritative fallback。受限请求已有的非空过滤图仍可返回；知识详情现有异常处理会省略附图，返回空 graph，而不是无约束附图。
3. Python Neo4j 普通及 generation neighborhood 都在 LIMIT/结果输出前检查路径的**每个节点**（包括中间节点）属于请求 KB 且满足 ACL；generation 路径还要求每个节点属于 active generation。保留每条边 ACL 和 generation 限制。零标签只匹配公开节点/边。

## 离线验证

- `KnowledgeGraphAclFallbackTest`：11 个 mock 单元测试通过。覆盖 public-only/指定标签的空图及异常、null provider、null 无约束兼容、合法非空图、详情不附无约束图。
- `tests/test_graph_acl.py`：11 个测试通过，其中新增五个路径 Cypher 合约测试，各覆盖 [] 与指定标签；断言全节点 ACL/KB 在输出前执行，且原边 ACL 保留，generation 约束覆盖所有节点。
- 测试没有启动数据库、Neo4j、模型服务或外部网络。Python 测试捕获查询而非真实 Cypher 执行，因此不能替代 Neo4j 集成验收。

## 验收限制与后续

这不是所有知识读取接口的完整隔离认证。其他详情字段、trace、overview 需要独立审计；本切片不改变它们。无约束 null fallback 依然需要上游严格守住管理员/内部信任边界。部署前应在隔离 Neo4j 测试库验证公开端点 + 私密中间节点、跨 KB 中间节点、跨 generation 节点、私密边全部不返回；并验证合法授权多跳路径仍可返回。

## 同轮追加：标签传输与其他可达多跳

- 旧 GET 参数通过逗号分隔并 trim，不能无损表示任意标签。Java 在请求前拒绝 null/空字符串标签、Unicode 空白/控制字符（包含 NBSP、NEL、全角空格等 Python strip 可去除字符）以及 `, & # % ? + ; =`，返回 `ACL_GRAPH_TAG_TRANSPORT_UNSAFE`，不调用 provider、不拆分或重写授权集。标签列表本身为空 `[]` 仍是合法 public-only；整个列表为 null 的兼容语义不变。这会让原本使用上述字符的标签无法查询图谱，须改为无歧义 JSON 数组契约后才能恢复，不能仅 URL encode 再 split。
- Python `/multihop` 可达 generation 路径增加所有节点 KB 检查；OPTIONAL evidence 的 KnowledgeObject 本身也必须满足 ACL，不能仅依据关联边授权泄露正文。
- `/facet-multihop` 对完整路径所有节点和边施加 ACL/active-generation，所有节点须属于请求 KB。
- `/citation-safe-multihop` 为 evidence/source/目标实体加 KB 与 active-generation；源、目标实体及四类关联边也须满足 ACL，各关联边限定 active-generation。缺少生成标记的旧投影因此可能不再召回，属于失败关闭行为，需重建合规投影。
- 上述新增三类多跳均使用 mock 查询合约测试覆盖 [] 和显式标签；尚未连接真实 Neo4j 解析/执行查询。

## 内部控制台权威投影入口追加封口

`GET /api/knowledge/graph/{kb}/overview` 和 `GET /api/knowledge/entities/{kb}/{entityCode}` 受 KB 访问控制，但角色拦截器未禁止普通用户读取这两类内容。其后端 overview 全图计数/关系及 entity detail 关联信息目前无逐项 ACL，因此控制器现在统一解析当前身份标签后，仅允许 AuthContext 中当前具备非空持久用户 ID、ACTIVE、角色精确为平台 ADMIN 且解析结果为 null 的上下文继续；[] 和任何显式标签集合均抛 SecurityException（标准 403）。错误分别标记 `ACL_GRAPH_OVERVIEW_UNAVAILABLE` / `ACL_ENTITY_DETAIL_UNAVAILABLE`，不会进入服务读取未过滤投影。

兼容影响：受限用户暂不可使用工作台图谱总览和实体聚合详情，即使其中部分数据公开；实体列表及具备 ACL 的邻域查询不受此项变更影响。当前 ACTIVE 平台 ADMIN 的 null 无约束上下文继续原行为，匿名、普通账号、KNOWLEDGE_ADMIN 及不活动账号即使解析结果为 null 也拒绝（包括 identity DB 关闭产生的 null），仍受既有 KB/身份边界约束。未来只有在整个投影（含计数、关联、支撑对象）实现 ACL 后才能开放，不能通过把 [] 转成 null 恢复访问。

定向测试 `AuthoritativeReadBoundaryTest` 覆盖两入口的 []、非空授权标签与 null，以及匿名、普通用户、KNOWLEDGE_ADMIN、不活动/无状态 ADMIN、identity DB 关闭的负向场景，及无持久用户 ID 的 ADMIN 负例，共 13 个测试；受限场景断言服务零交互。测试不启动真实服务。
