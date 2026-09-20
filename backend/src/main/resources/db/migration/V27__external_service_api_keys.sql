-- 接口服务层：对外 RESTful / MCP Server / SSE 的统一凭证与调用审计。
-- API Key 只保存哈希与前缀，明文在创建时一次性返回，之后无法再次读取。

CREATE TABLE IF NOT EXISTS t_api_key (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  key_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  secret_hash VARCHAR(128) NOT NULL,
  key_prefix VARCHAR(24) NOT NULL,
  knowledge_base_id BIGINT NULL,
  scopes VARCHAR(255) NOT NULL DEFAULT 'READ',
  rate_limit_per_minute INT NOT NULL DEFAULT 60,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  expires_at TIMESTAMP NULL,
  last_used_at TIMESTAMP NULL,
  created_by BIGINT NULL,
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_api_key_code(key_code),
  UNIQUE KEY uk_api_key_hash(secret_hash),
  KEY idx_api_key_status(status),
  KEY idx_api_key_kb(knowledge_base_id)
);

-- 调用审计：RESTful / MCP / SSE 三种渠道共用，支撑对外服务的计量与追溯。
CREATE TABLE IF NOT EXISTS t_api_call_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  key_id BIGINT NULL,
  key_code VARCHAR(64),
  channel VARCHAR(16) NOT NULL,
  method VARCHAR(128) NOT NULL,
  knowledge_base_id BIGINT NULL,
  status_code INT,
  duration_ms INT,
  trace_id VARCHAR(64),
  message VARCHAR(500),
  created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_api_audit_key_time(key_id, created_time),
  KEY idx_api_audit_time(created_time)
);
