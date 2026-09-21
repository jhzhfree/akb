-- Knowledge schema (design §7: 知识 Schema = 知识类型 / Entity / Relation / Ontology).
--
-- Entity and relation types used to be whatever the extraction model happened to emit
-- on that day, because the only vocabulary lived inside a Python tuple. The dictionary
-- now belongs to the platform: it is editable per knowledge base, validated before it
-- is published, and every publication keeps the snapshot that was in force so a later
-- derivation can say which schema it was derived under.
-- knowledge_base_id = 0 is the platform default that every knowledge base inherits.
CREATE TABLE IF NOT EXISTS t_schema_type(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  knowledge_base_id BIGINT NOT NULL DEFAULT 0,
  kind VARCHAR(24) NOT NULL,
  type_code VARCHAR(64) NOT NULL,
  label VARCHAR(120),
  description VARCHAR(500),
  properties_json JSON,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  built_in TINYINT(1) NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 1,
  updated_by VARCHAR(64),
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_schema_type(kind,type_code,knowledge_base_id),
  KEY idx_schema_type_kb(knowledge_base_id,kind,status)
);

CREATE TABLE IF NOT EXISTS t_schema_release(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  release_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  version VARCHAR(32) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  definition_json JSON NOT NULL,
  validation_json JSON,
  checksum VARCHAR(64) NOT NULL,
  published_by VARCHAR(64),
  published_time DATETIME,
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_schema_release_code(release_code),
  KEY idx_schema_release_kb(knowledge_base_id,state,created_time)
);

-- Platform default dictionary. It mirrors the vocabulary the extractor already used,
-- so publishing the default schema changes nothing about existing data — it only makes
-- the previously implicit contract explicit and editable.
INSERT INTO t_schema_type(knowledge_base_id,kind,type_code,label,description,status,built_in,updated_by)
VALUES(0,'KNOWLEDGE_TYPE','FACT','事实','可直接引用的客观事实与数据','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','PROCEDURE','流程','按步骤完成一项工作的操作序列','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','RULE','规则','约束、规范与判定标准','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','PROBLEM_SOLUTION','问题方案','问题现象与对应解决办法','ACTIVE',1,'system'),
      (0,'KNOWLEDGE_TYPE','CONCEPT','概念','术语、定义与原理说明','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','TERM','术语','领域词汇与专有名词','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','PRODUCT','产品','产品、模块或服务','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','SYSTEM','系统','平台、子系统或组件','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','METRIC','指标','可度量的业务或技术指标','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','ORG','组织','部门、团队或外部机构','ACTIVE',1,'system'),
      (0,'ENTITY_TYPE','ROLE','角色','岗位、职责或使用者','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','RELATED_TO','相关','通用关联','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','PART_OF','组成部分','整体与部分','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','DEPENDS_ON','依赖','功能或运行依赖','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','USES','使用','使用某项能力或资源','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','CAUSES','导致','因果关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','BELONGS_TO','归属','归属关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','LOCATED_IN','位于','位置或部署关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','DESCRIBES','描述','实体与描述性知识的关系','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','CONTAINS','包含','容器与内容','ACTIVE',1,'system'),
      (0,'RELATION_TYPE','MANAGES','管理','管理或负责关系','ACTIVE',1,'system');
