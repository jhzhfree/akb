-- V31: persist the retrieval stage timeline next to the trace (design §12.5).
--
-- The query trace stored the full response blob, which made the pipeline observable only after a
-- second parse and made per-stage latency impossible to aggregate across queries. The design asks
-- for a timeline where every stage reports input/output counts, latency, model or index version and
-- errors, so the stage array is promoted to its own column while the blob stays for forensics.
ALTER TABLE t_query_trace ADD COLUMN pipeline_json JSON NULL AFTER trace_json;
