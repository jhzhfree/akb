-- Website embed widget support.
--
-- An embedded chat widget runs in somebody else's browser: whatever credential it carries is
-- public by definition. The only practical defence is to bind a key to the sites that are
-- allowed to use it, so a leaked key cannot be replayed from an unrelated origin.
ALTER TABLE t_api_key ADD COLUMN allowed_origins VARCHAR(2000) NULL COMMENT '允许嵌入该凭证的站点来源（Origin），逗号分隔，为空表示不限制' AFTER updated_time;
