-- V1.1 retrieval engine migration: OpenSearch/Qdrant -> Elasticsearch/Milvus.
ALTER TABLE t_index_schema_release
  CHANGE COLUMN opensearch_target elasticsearch_target VARCHAR(128) NULL,
  CHANGE COLUMN qdrant_target milvus_target VARCHAR(128) NULL,
  CHANGE COLUMN opensearch_count elasticsearch_count BIGINT NULL,
  CHANGE COLUMN qdrant_count milvus_count BIGINT NULL;
