-- Phase 12: source/evidence-scoped graph facets. Canonical identity stays separate from protected facts.
CREATE TABLE IF NOT EXISTS t_entity_facet (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, knowledge_base_id BIGINT NOT NULL, entity_code VARCHAR(255) NOT NULL,
 facet_code VARCHAR(255) NOT NULL, source_id BIGINT NULL, document_id BIGINT NULL, entity_type VARCHAR(128) NULL,
 facet_name VARCHAR(500) NULL, facet_description TEXT NULL, permission_tags JSON NULL, scope_hash CHAR(64) NOT NULL,
 evidence_origin VARCHAR(255) NULL, status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
 created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_entity_facet(facet_code), KEY idx_entity_facet_kb_entity(knowledge_base_id,entity_code), KEY idx_entity_facet_scope(scope_hash)
);
CREATE TABLE IF NOT EXISTS t_relation_facet (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, knowledge_base_id BIGINT NOT NULL, relation_code VARCHAR(255) NOT NULL,
 facet_code VARCHAR(255) NOT NULL, source_id BIGINT NULL, document_id BIGINT NULL, source_entity_code VARCHAR(255) NOT NULL,
 target_entity_code VARCHAR(255) NOT NULL, relation_type VARCHAR(128) NULL, permission_tags JSON NULL, scope_hash CHAR(64) NOT NULL,
 evidence_origin VARCHAR(255) NULL, status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
 created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_relation_facet(facet_code), KEY idx_relation_facet_kb_relation(knowledge_base_id,relation_code), KEY idx_relation_facet_scope(scope_hash)
);
CREATE TABLE IF NOT EXISTS t_index_graph_facet_manifest (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, release_id VARCHAR(64) NOT NULL, knowledge_base_id BIGINT NOT NULL, generation_id VARCHAR(128) NOT NULL,
 manifest_hash CHAR(64) NOT NULL, entity_facet_count BIGINT NOT NULL DEFAULT 0, relation_facet_count BIGINT NOT NULL DEFAULT 0,
 created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE KEY uk_graph_facet_manifest_release(release_id)
);
