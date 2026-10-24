-- Durable extraction-event retries look up the existing event by its stable idempotency key.
CREATE INDEX idx_platform_event_idempotency_key ON t_platform_event(idempotency_key);
