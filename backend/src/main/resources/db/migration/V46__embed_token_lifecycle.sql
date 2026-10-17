-- Embed token lifecycle: a token can be revoked on its own, and a call can be traced back to the
-- exact token and site that made it.
--
-- Until now revoking one leaked embed token meant revoking the API key behind it, which took every
-- page minted from that key down with it. Giving each token an id (jti) makes the blast radius one
-- token again — and recording that id on every call is what lets an operator find the token to
-- revoke in the first place, instead of guessing from a key and a timestamp.
ALTER TABLE t_api_call_audit
  ADD COLUMN token_jti VARCHAR(64) NULL AFTER key_code,
  ADD COLUMN origin VARCHAR(255) NULL AFTER token_jti,
  ADD KEY idx_api_audit_jti(token_jti, created_time);

CREATE TABLE IF NOT EXISTS t_embed_token_revocation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  jti VARCHAR(64) NOT NULL,
  key_id BIGINT NULL,
  key_code VARCHAR(64),
  knowledge_base_id BIGINT NULL,
  origin VARCHAR(255),
  reason VARCHAR(500),
  operator_id BIGINT NULL,
  revoked_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_embed_token_revocation(jti),
  KEY idx_embed_revocation_key(key_id),
  KEY idx_embed_revocation_time(revoked_time)
);
