-- Derivation rule presets (design §4.5): a rule remembers which preset it came from.
--
-- The console ships with the derivation rules almost every knowledge base wants — deployment
-- chains, dependency impact, ownership, cause chains — because a console that answers "what
-- now?" with an empty form is not usable. Keeping the preset code on the rule is what makes
-- that safe later: seeding stays idempotent, a rule edited by hand is still recognisable as
-- the preset it started from, and "put it back the way it was" has something to go back to.
-- Rules created from scratch simply carry no preset code.
ALTER TABLE t_derivation_rule
  ADD COLUMN preset_code VARCHAR(64) DEFAULT NULL AFTER rule_code;

ALTER TABLE t_derivation_rule
  ADD KEY idx_derivation_rule_preset(knowledge_base_id, preset_code);
