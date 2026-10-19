-- 知识台账：来源的「身份证」四要素（责任人 / 生效日 / 失效日 / 适用范围）。
--
-- 知识库里最贵的问题不是“没有知识”，而是“不知道这份知识还作不作数”：
-- 员工引用了过期制度却以为错的是自己。给来源挂上维护人与有效期，台账才答得出
-- 「谁负责、现在还有效吗、适用于谁」，后续到期复核也才有依据。
--
-- 四列全部可空：老来源不受影响，为空表示「未登记」而不是「已失效」，
-- 盘点时会被台账的「无责任人 / 无有效期」筛选挑出来，逐步补齐。
ALTER TABLE t_source
  ADD COLUMN owner_user_id BIGINT NULL AFTER authority_score,
  ADD COLUMN effective_at DATETIME NULL AFTER owner_user_id,
  ADD COLUMN expire_at DATETIME NULL AFTER effective_at,
  ADD COLUMN scope VARCHAR(255) NULL AFTER expire_at;
