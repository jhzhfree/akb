-- Opt-in only: existing V1/V2 policies retain their application AND Agent grants.
ALTER TABLE t_workload_policy ADD COLUMN simple_mode BOOLEAN NOT NULL DEFAULT FALSE;
