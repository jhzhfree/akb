CREATE INDEX idx_sv_source_hash ON t_source_version(source_id,source_hash);
CREATE INDEX idx_cu_content_hash ON t_content_unit(content_hash);
CREATE INDEX idx_evidence_source_status ON t_evidence(source_id,status);
CREATE INDEX idx_evidence_kb_status ON t_evidence(knowledge_base_id,status);
CREATE INDEX idx_claim_lookup ON t_claim(knowledge_base_id,subject(128),predicate,status);
CREATE INDEX idx_card_updated ON t_knowledge_card(knowledge_base_id,status,updated_time);
CREATE INDEX idx_conflict_objects ON t_knowledge_conflict(knowledge_base_id,object_a_code,object_b_code,status);
CREATE INDEX idx_assessment_latest ON t_adjudication_assessment(knowledge_base_id,object_type,object_code,created_time);
