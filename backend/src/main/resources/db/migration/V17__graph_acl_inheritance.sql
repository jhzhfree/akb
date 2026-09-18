-- Phase 10: persist graph ACL provenance and evidence-bound graph paths.
ALTER TABLE t_entity ADD COLUMN acl_inherited_from VARCHAR(255) NULL AFTER permission_tags;
ALTER TABLE t_relation ADD COLUMN acl_inherited_from VARCHAR(255) NULL AFTER permission_tags;
ALTER TABLE t_claim_entity ADD COLUMN permission_tags JSON NULL AFTER relation_role;
ALTER TABLE t_card_entity ADD COLUMN permission_tags JSON NULL AFTER relation_role;
