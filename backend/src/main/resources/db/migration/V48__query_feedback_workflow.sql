-- Capture explicit feedback on a delivered answer and route it through a governed review queue.
-- Feedback is a signal about the answer, not authoritative knowledge: it must be reviewed before
-- any suggested correction is promoted into cards, claims, or source documents.
ALTER TABLE t_query_trace
    ADD COLUMN actor_user_id BIGINT NULL AFTER knowledge_base_id,
    ADD KEY idx_trace_actor_kb_time (actor_user_id, knowledge_base_id, created_time);

CREATE TABLE t_query_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    feedback_code VARCHAR(64) NOT NULL,
    query_id VARCHAR(64) NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    rating VARCHAR(16) NOT NULL,
    reason_code VARCHAR(32) NULL,
    comment VARCHAR(1000) NULL,
    suggested_answer TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    reviewer_user_id BIGINT NULL,
    review_note VARCHAR(1000) NULL,
    reviewed_time DATETIME NULL,
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_query_feedback_code (feedback_code),
    UNIQUE KEY uk_query_feedback_actor (query_id, actor_user_id),
    KEY idx_query_feedback_queue (knowledge_base_id, status, created_time),
    KEY idx_query_feedback_actor (actor_user_id, knowledge_base_id, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
