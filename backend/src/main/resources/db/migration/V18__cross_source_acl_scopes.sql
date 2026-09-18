-- Phase 11: preserve source-scoped ACL contributions during entity resolution.
CREATE TABLE IF NOT EXISTS t_entity_acl_scope (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 knowledge_base_id BIGINT NOT NULL,
 entity_code VARCHAR(255) NOT NULL,
 source_id BIGINT NULL,
 document_id BIGINT NULL,
 permission_tags JSON NULL,
 scope_hash CHAR(64) NOT NULL,
 evidence_origin VARCHAR(255) NULL,
 created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_entity_acl_scope(entity_code,source_id,scope_hash),
 KEY idx_entity_acl_scope_kb_entity(knowledge_base_id,entity_code)
);
CREATE TABLE IF NOT EXISTS t_relation_acl_scope (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 knowledge_base_id BIGINT NOT NULL,
 relation_code VARCHAR(255) NOT NULL,
 source_id BIGINT NULL,
 document_id BIGINT NULL,
 permission_tags JSON NULL,
 scope_hash CHAR(64) NOT NULL,
 evidence_origin VARCHAR(255) NULL,
 created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_relation_acl_scope(relation_code,source_id,scope_hash),
 KEY idx_relation_acl_scope_kb_relation(knowledge_base_id,relation_code)
);
ALTER TABLE t_entity ADD COLUMN acl_merge_policy VARCHAR(32) NOT NULL DEFAULT 'SINGLE_SCOPE' AFTER acl_inherited_from;
ALTER TABLE t_relation ADD COLUMN acl_merge_policy VARCHAR(32) NOT NULL DEFAULT 'SINGLE_SCOPE' AFTER acl_inherited_from;
