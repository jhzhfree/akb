-- Authoritative ACL persistence. Empty JSON array means public.
ALTER TABLE t_source ADD COLUMN permission_tags JSON NULL AFTER status;
ALTER TABLE t_knowledge_card ADD COLUMN permission_tags JSON NULL AFTER status;
ALTER TABLE t_claim ADD COLUMN permission_tags JSON NULL AFTER status;
CREATE INDEX idx_source_kb_status ON t_source(knowledge_base_id,status);
