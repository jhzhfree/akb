-- 卡片与断言的「幂等键」与「等价指纹」。
--
-- 两个键解决的是两件不同的事，不要混用：
--   dedup_key      抽取幂等键。知识库 + 文档 + 抽取版本 + 内容指纹，唯一约束保证
--                  同一篇文档重跑抽取不会把同样的知识再写一份；并发下的重复也由
--                  数据库约束兜底，而不是靠调用侧先查后写。
--   *_fingerprint  知识等价指纹。完整规范化内容 + 关键语义字段，用于判断「不同来源
--                  是否表达了同一条知识」。命中不等于合并：只记录一条重复关系交人工
--                  裁决，两侧资产各自保留、状态与权限标签都不动。
--
-- 两列都允许为空：迁移前已存在的对象没有指纹，不能因此插入失败；空值不参与唯一约束。

ALTER TABLE t_knowledge_card
    ADD COLUMN content_fingerprint VARCHAR(64) NULL,
    ADD COLUMN dedup_key VARCHAR(200) NULL,
    ADD KEY idx_card_fingerprint(knowledge_base_id, content_fingerprint),
    ADD UNIQUE KEY uk_card_dedup(dedup_key);

-- 断言的指纹必须包含极性、条件与有效期：「支持 MySQL」与「不支持 MySQL」只差极性，
-- 同一主张在不同有效期下也可能并不等价，只按三元组比对会把它们错误地当成同一条。
ALTER TABLE t_claim
    ADD COLUMN claim_fingerprint VARCHAR(64) NULL,
    ADD COLUMN dedup_key VARCHAR(200) NULL,
    ADD KEY idx_claim_fingerprint(knowledge_base_id, claim_fingerprint),
    ADD UNIQUE KEY uk_claim_dedup(dedup_key);
