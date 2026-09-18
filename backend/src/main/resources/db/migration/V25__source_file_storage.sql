-- Source file preview: ingestion keeps the original upload so the console can show
-- what was actually imported, not only the parsed text derived from it.
ALTER TABLE t_source ADD COLUMN storage_path VARCHAR(1000) NULL;
