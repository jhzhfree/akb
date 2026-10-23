-- Preserve committed extraction results and rule-specific diagnostics independently of lifecycle status.
ALTER TABLE t_document ADD COLUMN extraction_summary_json JSON NULL;
ALTER TABLE t_derivation_run ADD COLUMN execution_summary_json JSON NULL;
