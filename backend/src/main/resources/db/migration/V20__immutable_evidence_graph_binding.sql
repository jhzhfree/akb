-- Phase 13: immutable evidence/source binding for citation-safe GraphRAG.
CREATE TABLE IF NOT EXISTS t_entity_facet_evidence (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, facet_code VARCHAR(128) NOT NULL, evidence_id BIGINT NOT NULL,
 evidence_hash VARCHAR(64) NOT NULL, created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_entity_facet_evidence(facet_code,evidence_id), KEY idx_efe_evidence(evidence_id)
);
CREATE TABLE IF NOT EXISTS t_relation_facet_evidence (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, facet_code VARCHAR(128) NOT NULL, evidence_id BIGINT NOT NULL,
 evidence_hash VARCHAR(64) NOT NULL, created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_relation_facet_evidence(facet_code,evidence_id), KEY idx_rfe_evidence(evidence_id)
);
CREATE TABLE IF NOT EXISTS t_index_graph_evidence_manifest (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, release_id VARCHAR(64) NOT NULL, knowledge_base_id BIGINT NOT NULL,
 generation_id VARCHAR(128) NOT NULL, manifest_hash VARCHAR(64) NOT NULL, evidence_count BIGINT NOT NULL DEFAULT 0,
 binding_count BIGINT NOT NULL DEFAULT 0, created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_graph_evidence_manifest_release(release_id)
);
