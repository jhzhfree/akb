-- Keep scoped/status/expiry filters in the same recall query while allowing MySQL
-- to use an ngram full-text index for Chinese and other CJK memory content.
ALTER TABLE t_agent_memory
    ADD FULLTEXT INDEX ft_agent_memory_content (content) WITH PARSER ngram;
