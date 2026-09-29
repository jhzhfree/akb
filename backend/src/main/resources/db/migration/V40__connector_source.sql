-- Connector sources (design §6.1): a saved, reusable description of "where the knowledge lives"
-- when it is not a file someone drags into the console — Jira projects, Confluence spaces, or any
-- RESTful endpoint that can list its own documents.
--
-- This first step only stores the connection and reads item lists from it; turning items into
-- documents is a later step, which is why nothing here references t_knowledge_source yet.
-- The credential columns are deliberately plain VARCHAR: the platform has no key-management
-- service to hand the secret to, so the console masks the value on read and only ever writes it
-- from the form. Treat database access as the trust boundary until that service exists.
CREATE TABLE t_connector_source
(
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  connector_code    VARCHAR(64)  NOT NULL,
  knowledge_base_id BIGINT       NOT NULL,
  name              VARCHAR(200) NOT NULL,
  connector_type    VARCHAR(32)  NOT NULL COMMENT 'JIRA | CONFLUENCE | REST',
  base_url          VARCHAR(500) NOT NULL COMMENT '站点根地址，如 https://jira.example.com',
  auth_type         VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE | BASIC | BEARER',
  username          VARCHAR(200) DEFAULT NULL COMMENT 'BASIC 用；BEARER 时为空',
  secret            VARCHAR(500) DEFAULT NULL COMMENT 'BASIC 的密码 / API Token，BEARER 的令牌',
  config_json       TEXT         COMMENT '类型专属配置：JQL、spaceKey、REST 路径与字段映射',
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE | DISABLED',
  last_test_status  VARCHAR(16)  DEFAULT NULL COMMENT 'OK | FAILED',
  last_test_message VARCHAR(500) DEFAULT NULL,
  last_test_time    DATETIME     DEFAULT NULL,
  created_by        VARCHAR(100) DEFAULT NULL,
  created_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_connector_source_code (connector_code),
  KEY idx_connector_source_kb (knowledge_base_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='外部数据源连接器配置';
