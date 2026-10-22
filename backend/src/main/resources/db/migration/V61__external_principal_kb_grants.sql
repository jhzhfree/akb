-- 外部调用主体与多知识库授权。既有 Key 转成独立主体，并将原单库绑定迁移为等价授权。
CREATE TABLE IF NOT EXISTS t_principal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  principal_code VARCHAR(128) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_principal_code(principal_code)
);

CREATE TABLE IF NOT EXISTS t_principal_kb_grant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  principal_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  reason VARCHAR(500) NULL,
  created_by BIGINT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NULL,
  UNIQUE KEY uk_principal_kb(principal_id,knowledge_base_id),
  KEY idx_principal_grant_active(principal_id,status,expires_at),
  KEY idx_principal_grant_kb(knowledge_base_id),
  CONSTRAINT fk_principal_grant_principal FOREIGN KEY (principal_id) REFERENCES t_principal(id) ON DELETE CASCADE,
  CONSTRAINT fk_principal_grant_kb FOREIGN KEY (knowledge_base_id) REFERENCES t_knowledge_base(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS t_principal_kb_grant_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  principal_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  action VARCHAR(16) NOT NULL,
  reason VARCHAR(500) NULL,
  operator_id BIGINT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_principal_grant_audit(principal_id,created_time),
  KEY idx_principal_grant_audit_kb(knowledge_base_id,created_time)
);

ALTER TABLE t_api_key ADD COLUMN principal_id BIGINT NULL AFTER knowledge_base_id;

INSERT INTO t_principal(principal_code,name,status,created_by)
SELECT CONCAT('MIG_',key_code),name,'ACTIVE',created_by FROM t_api_key;

UPDATE t_api_key k JOIN t_principal p ON p.principal_code=CONCAT('MIG_',k.key_code)
SET k.principal_id=p.id;

-- 只迁移"库还在"的绑定。
-- t_api_key 与 t_knowledge_base 之间没有外键，历史上删除知识库时没有连带清理 Key，
-- 于是残留了一批 knowledge_base_id 指向已删除库的记录；直接插入会撞
-- fk_principal_grant_kb（MySQL 1452）导致整次迁移失败。
-- 这类绑定本身已无意义（库都没了），跳过即可：对应主体照常创建，只是没有任何库授权。
INSERT INTO t_principal_kb_grant(principal_id,knowledge_base_id,status,reason,created_by)
SELECT principal_id,knowledge_base_id,'ACTIVE','由既有 API Key 单库绑定迁移',created_by
FROM t_api_key
WHERE knowledge_base_id IS NOT NULL
  AND knowledge_base_id IN (SELECT id FROM t_knowledge_base);

ALTER TABLE t_api_key ADD KEY idx_api_key_principal(principal_id),
  ADD CONSTRAINT fk_api_key_principal FOREIGN KEY (principal_id) REFERENCES t_principal(id);
