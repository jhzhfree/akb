-- Connector item catalog (design §6.1, second step: keep what a connector listed).
--
-- Reading a Jira project returns typed knowledge — a "缺陷" carries 状态 / 优先级 / 经办人, a "需求"
-- carries 模块 / 版本 — and the console has to browse it by those attributes instead of showing one
-- flat title list. The type and the attributes therefore live with the item: item_type is a column
-- (it drives filtering and counts), attributes_json stays schemaless because every external system
-- invents its own fields and no fixed column set survives the second connector.
--
-- Deliberately separate from t_knowledge_source / t_knowledge_document / t_knowledge_card: this is
-- what the external system says right now, not knowledge the platform has ingested and made
-- authoritative. Nothing here is referenced by the existing knowledge tables, so documents, cards,
-- assertions and their retrieval keep working exactly as before.
CREATE TABLE t_connector_item
(
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  connector_id      BIGINT        NOT NULL COMMENT '所属连接器',
  item_type         VARCHAR(64)   NOT NULL DEFAULT 'ITEM' COMMENT '外部系统的类型：Jira 问题类型、Confluence 内容类型、REST 自定义',
  external_id       VARCHAR(200)  NOT NULL COMMENT '外部系统主键：Jira key、Confluence id、REST 映射到的标识',
  title             VARCHAR(500)  DEFAULT NULL,
  item_url          VARCHAR(1000) DEFAULT NULL COMMENT '回到外部系统查看该条目的链接',
  excerpt           TEXT          COMMENT '摘要/正文片段，供列表与详情预览',
  attributes_json   JSON          COMMENT '类型专属属性（状态、优先级、经办人…），按类型动态展示与筛选',
  raw_json          MEDIUMTEXT    COMMENT '外部系统原始条目，详情溯源与后续入库留痕',
  remote_updated_at DATETIME      DEFAULT NULL COMMENT '外部系统侧的更新时间',
  synced_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本平台最近一次同步到该条目的时间',
  created_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_connector_item (connector_id, external_id),
  KEY idx_connector_item_type (connector_id, item_type),
  KEY idx_connector_item_remote (connector_id, remote_updated_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='连接器同步下来的外部知识条目（未进入知识库主结构）';

-- 同步结果记在连接器上：列表页要告诉用户这批数据有多新、有多少条。
ALTER TABLE t_connector_source
  ADD COLUMN last_sync_time  DATETIME DEFAULT NULL COMMENT '最近一次把外部知识同步落库的时间' AFTER last_test_time,
  ADD COLUMN last_sync_count INT      DEFAULT NULL COMMENT '最近一次同步落库的条目数' AFTER last_sync_time;
