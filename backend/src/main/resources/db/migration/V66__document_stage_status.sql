-- 三个阶段的成败分开记：解析成功但抽取为空、抽取有产出但索引没跟上，是三种不同的故障，
-- 处理方式也不同（前者要重跑抽取，后者要补索引）。挤在一个 status 里只能看到“最后那次
-- 写入的结果”，前面阶段发生过什么就看不出来了，于是排查只能靠重新导入一遍来试。
ALTER TABLE t_document ADD COLUMN extraction_status varchar(32) NULL AFTER parse_status;
ALTER TABLE t_document ADD COLUMN index_status varchar(32) NULL AFTER extraction_status;

-- 运维最常问的是“哪些文档抽取为空”，没有索引就得全表扫。
ALTER TABLE t_document ADD INDEX idx_document_extraction_status (extraction_status);
