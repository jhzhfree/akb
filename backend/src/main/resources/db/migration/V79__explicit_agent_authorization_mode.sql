-- V78 erased the previous simple_mode value. Do not guess historical authority.
-- Pause existing non-revoked connections until an administrator reviews their scope.
ALTER TABLE t_workload_policy
  ADD COLUMN authorization_mode VARCHAR(32) NOT NULL DEFAULT 'REVIEW_REQUIRED',
  ADD COLUMN platform_type VARCHAR(32) NOT NULL DEFAULT 'custom';
UPDATE t_workload_policy SET status='SUSPENDED' WHERE status <> 'REVOKED';
