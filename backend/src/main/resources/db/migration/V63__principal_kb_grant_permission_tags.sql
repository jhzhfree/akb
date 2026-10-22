-- Store a knowledge-base-specific retrieval tag ceiling. NULL inherits the API Key policy;
-- an empty JSON array permits public resources only.
ALTER TABLE t_principal_kb_grant
  ADD COLUMN permission_tags JSON NULL COMMENT '知识库级标签授权：NULL=继承 API Key，空数组=仅公开资料' AFTER expires_at;

ALTER TABLE t_principal_kb_grant_audit
  ADD COLUMN permission_tags JSON NULL COMMENT '该次授权变更对应的标签范围快照' AFTER reason;
