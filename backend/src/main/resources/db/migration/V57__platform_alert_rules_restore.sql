-- 补回平台内置的告警规则（knowledge_base_id=NULL 表示平台级，对所有知识库生效）。
--
-- 与 V56 同源的问题：V34 已成功执行过，但库里这 5 条平台级规则整批不存在（库被恢复/重建过），
-- Flyway 已把 V34 标记为已执行不会重跑，缺的数据只能靠新迁移补。
-- 唯一键 uk_alert_rule_code(rule_code) 是单列，ON DUPLICATE KEY UPDATE 可安全幂等：
-- 已存在的环境只刷新阈值与名称，缺失的环境整批补回。

INSERT INTO t_alert_rule(rule_code,knowledge_base_id,name,metric,severity,threshold,window_minutes,created_by)
VALUES('AR_TASK_FAILURE',NULL,'任务失败数','TASK_FAILURE_COUNT','WARNING',1,60,'system'),
      ('AR_TASK_FAILURE_RATE',NULL,'任务失败率','TASK_FAILURE_RATE','CRITICAL',30,60,'system'),
      ('AR_DLQ_BACKLOG',NULL,'索引投递死信','DLQ_BACKLOG','CRITICAL',1,60,'system'),
      ('AR_DOCUMENT_FAILED',NULL,'文档处理失败','DOCUMENT_FAILED','WARNING',1,120,'system'),
      ('AR_DERIVED_STALE',NULL,'衍生知识失效','DERIVED_STALE','INFO',1,1440,'system')
ON DUPLICATE KEY UPDATE name=VALUES(name),metric=VALUES(metric),severity=VALUES(severity),
  threshold=VALUES(threshold),window_minutes=VALUES(window_minutes);
