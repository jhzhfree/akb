-- Task centre and alerting (design §7: 任务告警 = 任务 / DLQ / 告警规则).
--
-- Before this change every asynchronous job kept its own book: ingestion runs in
-- t_ingestion_run, index delivery in t_index_outbox, ACL rebuilds in t_acl_rebuild_job.
-- Operations therefore had to know which table to open before they could answer
-- "what is failing right now". t_task_run is the single ledger: every long running
-- job registers here with the same status vocabulary, the same retry semantics and
-- the same idempotency surface, while the specialised tables keep their own detail.
CREATE TABLE IF NOT EXISTS t_task_run(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  task_type VARCHAR(32) NOT NULL,
  task_name VARCHAR(200),
  target_type VARCHAR(32),
  target_code VARCHAR(120),
  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  stage VARCHAR(64),
  progress INT NOT NULL DEFAULT 0,
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
  triggered_by VARCHAR(64),
  retry_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 3,
  payload_json JSON,
  result_json JSON,
  error_message TEXT,
  next_attempt_at DATETIME,
  started_time DATETIME,
  finished_time DATETIME,
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_task_run_code(task_code),
  KEY idx_task_run_kb_status(knowledge_base_id,status,created_time),
  KEY idx_task_run_type(task_type,status),
  KEY idx_task_run_due(status,next_attempt_at)
);

-- Alert rules decide what "abnormal" means for one knowledge base; without them the
-- platform can only report raw counters and every operator invents their own threshold.
CREATE TABLE IF NOT EXISTS t_alert_rule(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT NULL,
  name VARCHAR(120) NOT NULL,
  metric VARCHAR(48) NOT NULL,
  severity VARCHAR(16) NOT NULL DEFAULT 'WARNING',
  threshold DOUBLE NOT NULL DEFAULT 1,
  window_minutes INT NOT NULL DEFAULT 60,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  channels VARCHAR(120) NOT NULL DEFAULT 'IN_APP',
  created_by VARCHAR(64),
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_alert_rule_code(rule_code),
  KEY idx_alert_rule_scope(knowledge_base_id,enabled)
);

-- One row per firing condition, not per occurrence: a rule that keeps firing raises
-- occur_count on the open alert instead of flooding the console. dedup_key makes that
-- idempotent across evaluator runs.
CREATE TABLE IF NOT EXISTS t_alert_event(
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  alert_code VARCHAR(64) NOT NULL,
  rule_code VARCHAR(64) NOT NULL,
  knowledge_base_id BIGINT,
  severity VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  title VARCHAR(240) NOT NULL,
  detail_json JSON,
  metric_value DOUBLE,
  dedup_key VARCHAR(160) NOT NULL,
  occur_count INT NOT NULL DEFAULT 1,
  first_seen DATETIME,
  last_seen DATETIME,
  acked_by VARCHAR(64),
  acked_time DATETIME,
  resolved_by VARCHAR(64),
  resolved_time DATETIME,
  resolve_note VARCHAR(500),
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_alert_event_code(alert_code),
  UNIQUE KEY uk_alert_event_dedup(dedup_key),
  KEY idx_alert_event_status(status,last_seen)
);

-- Out of the box rules: a deployment should warn about failing jobs and dead letters
-- before anyone has configured anything.
INSERT INTO t_alert_rule(rule_code,knowledge_base_id,name,metric,severity,threshold,window_minutes,created_by)
VALUES('AR_TASK_FAILURE',NULL,'任务失败数','TASK_FAILURE_COUNT','WARNING',1,60,'system'),
      ('AR_TASK_FAILURE_RATE',NULL,'任务失败率','TASK_FAILURE_RATE','CRITICAL',30,60,'system'),
      ('AR_DLQ_BACKLOG',NULL,'索引投递死信','DLQ_BACKLOG','CRITICAL',1,60,'system'),
      ('AR_DOCUMENT_FAILED',NULL,'文档处理失败','DOCUMENT_FAILED','WARNING',1,120,'system'),
      ('AR_DERIVED_STALE',NULL,'衍生知识失效','DERIVED_STALE','INFO',1,1440,'system');
