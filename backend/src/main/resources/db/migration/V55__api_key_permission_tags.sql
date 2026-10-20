-- 检索授权：把「资料标签」从客户端自声明改为服务端授权。
--
-- 背景：外部查询接口（REST / MCP / SSE）此前把调用方请求里的 permissionTags 原样送进检索链路，
-- ai-service 直接用它作为 Elasticsearch / BM25 / Milvus / Neo4j 的过滤条件，
-- 于是调用方只要自填标签就能扩大召回范围——标签成了客户端可自我授权的能力。
--
-- 这里给凭证加上服务端管理的标签授权范围：
--   NULL  = 未配置（兼容模式，沿用旧行为，由 knowledge.acl.external-tag-mode 决定是否收紧）
--   []    = 已配置但一个标签都没有：默认拒绝，只能检索无标签（公开）资料
--   非空  = 该凭证被授权的资料标签集合；调用方请求标签只能在此基础上收窄，不能扩大
--
-- 注意：本列只描述「资料范围」，不替代知识库绑定（t_api_key.knowledge_base_id）与 scopes 校验。
-- 主体（Principal）与多库授权（t_principal / t_principal_kb_grant）在后续阶段引入。
ALTER TABLE t_api_key
  ADD COLUMN permission_tags JSON NULL COMMENT '该凭证的资料标签授权范围（服务端管理）：NULL=未配置，空数组=仅公开资料' AFTER scopes;
