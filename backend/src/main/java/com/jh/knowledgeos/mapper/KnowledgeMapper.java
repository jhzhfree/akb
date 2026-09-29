package com.jh.knowledgeos.mapper;

/**
 * Compatibility composition mapper. New services should inject the focused mapper that owns their domain.
 * Kept temporarily to avoid a flag-day constructor migration.
 */
public interface KnowledgeMapper extends AclPersistenceMapper, KnowledgeBaseMapper, IngestionMapper, KnowledgeObjectMapper, GraphReadMapper, PurgeMapper, ContentUnitMapper, EvaluationMapper, TaskMapper, AlertMapper, KnowledgeCoreMapper, DerivationMapper, SchemaMapper {
}
