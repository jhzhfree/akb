-- 定时同步：连接器自己记住周期与下次触发时间，调度器只看到期的行。
-- 没有这列时同步只能靠人点，外部系统每天都在变，目录很快就不再是源端的样子。
ALTER TABLE t_connector_source
  ADD COLUMN sync_cron varchar(64) NULL COMMENT '五段 cron（分 时 日 月 周），空表示只手动同步' AFTER last_sync_count,
  ADD COLUMN next_fire_at datetime NULL COMMENT '下次到点时间' AFTER sync_cron,
  ADD COLUMN sync_status varchar(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE / RUNNING / FAILED' AFTER next_fire_at,
  ADD COLUMN last_error varchar(500) NULL COMMENT '最近一次定时同步的失败原因' AFTER sync_status;
