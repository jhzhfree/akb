-- Call log detail: who called, with what, and what came back.
--
-- Until now an audit row said only that some credential invoked some method: enough to meter the
-- channel, not enough to answer the question an operator actually has — which integration sent
-- this payload, and what did our answer look like. Without those columns, investigating a bad call
-- means asking the caller to reproduce it.
ALTER TABLE t_api_call_audit
  ADD COLUMN caller_name VARCHAR(128) NULL AFTER key_code,
  ADD COLUMN request_params TEXT NULL AFTER knowledge_base_id,
  ADD COLUMN response_data TEXT NULL AFTER status_code;
