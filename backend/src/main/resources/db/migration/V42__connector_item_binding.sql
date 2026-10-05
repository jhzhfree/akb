-- 连接器条目 ↔ 知识库文档的绑定。
--
-- 条目入库后就是一篇普通文档：同一个 t_source / t_document / t_content_unit / t_knowledge_card，
-- 同一条抽取与索引链路。这张表只记「它是从哪条外部条目来的、当时内容是什么」，用来做幂等
-- 与增量重抽——条目在源系统改了才值得再抽一次。
--
-- 主表一行都不改：文件上传产生的知识不经过这里，结构与检索行为完全不变。
CREATE TABLE IF NOT EXISTS t_connector_item_binding(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 connector_id BIGINT NOT NULL,
 connector_item_id BIGINT NOT NULL,
 knowledge_base_id BIGINT NOT NULL,
 source_id BIGINT,
 document_id BIGINT,
 -- 入库时的内容指纹：与当前条目一致说明源端没变，重抽只是白花一次模型调用。
 content_hash VARCHAR(128),
 remote_updated_at DATETIME,
 -- INGESTED 已入库 / STALE 源端已变更待重抽 / FAILED 入库失败 / ARCHIVED 源端已删除
 ingest_status VARCHAR(32) DEFAULT 'INGESTED',
 last_ingest_time DATETIME,
 last_ingest_message VARCHAR(500),
 created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
 updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_cib_item(connector_item_id),
 KEY idx_cib_connector(connector_id),
 KEY idx_cib_kb_status(knowledge_base_id, ingest_status)
);
