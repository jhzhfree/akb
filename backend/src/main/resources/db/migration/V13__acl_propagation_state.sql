ALTER TABLE t_acl_change
  ADD COLUMN propagation_result JSON NULL,
  ADD COLUMN last_error VARCHAR(4000) NULL,
  ADD KEY idx_acl_change_state_time(propagation_state,created_time);
