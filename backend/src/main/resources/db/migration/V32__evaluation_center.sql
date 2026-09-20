-- V32: evaluation center (design §14).
--
-- Retrieval quality was asserted, never measured: every change to chunking, recall weights or the
-- reranker was judged by eyeballing a handful of questions, and a regression was indistinguishable
-- from a prompt change. The design asks for a dataset / case / run / result model so the same
-- questions can be replayed against the same pipeline and compared run against run.
CREATE TABLE t_evaluation_dataset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dataset_code VARCHAR(64) NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_eval_dataset_code (dataset_code),
    KEY idx_eval_dataset_kb (knowledge_base_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A case is the unit of replay: the question plus the knowledge objects an answer must be able to
-- reach. Expected evidence and expected answer stay optional so a retrieval-only dataset can still
-- be graded on hit rate before any gold answer exists.
CREATE TABLE t_evaluation_case (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    case_code VARCHAR(64) NOT NULL,
    dataset_id BIGINT NOT NULL,
    question TEXT NOT NULL,
    expected_object_codes_json JSON NULL,
    expected_evidence_codes_json JSON NULL,
    expected_answer TEXT,
    tags_json JSON NULL,
    difficulty VARCHAR(32),
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_eval_case_code (case_code),
    KEY idx_eval_case_dataset (dataset_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Runs are comparable snapshots: the metrics, the policy version that produced them and the run
-- they are measured against. Without the baseline columns a "regression" is just a number.
CREATE TABLE t_evaluation_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_code VARCHAR(64) NOT NULL,
    dataset_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    -- RETRIEVAL grades recall only; FULL also grades citations and faithfulness through the answer.
    mode VARCHAR(32) NOT NULL DEFAULT 'RETRIEVAL',
    top_k INT NOT NULL DEFAULT 8,
    policy_version VARCHAR(32),
    baseline_run_code VARCHAR(64),
    case_count INT NOT NULL DEFAULT 0,
    hit_at1_rate DECIMAL(10,4) NULL,
    hit_at3_rate DECIMAL(10,4) NULL,
    hit_at5_rate DECIMAL(10,4) NULL,
    recall_at5 DECIMAL(10,4) NULL,
    mrr DECIMAL(10,4) NULL,
    ndcg_at10 DECIMAL(10,4) NULL,
    latency_p50_ms INT NULL,
    latency_p95_ms INT NULL,
    citation_accuracy DECIMAL(10,4) NULL,
    faithfulness DECIMAL(10,4) NULL,
    regression_verdict VARCHAR(32) NULL,
    baseline_delta_json JSON NULL,
    gaps_json JSON NULL,
    error_message VARCHAR(1000),
    started_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_time DATETIME NULL,
    UNIQUE KEY uk_eval_run_code (run_code),
    KEY idx_eval_run_kb (knowledge_base_id, started_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per-case results: the run's metrics are averages, and an average cannot answer "which question
-- broke". Retrieved codes are stored so a miss can be inspected without re-running the pipeline.
CREATE TABLE t_evaluation_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    case_code VARCHAR(64) NOT NULL,
    query_id VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    expected_codes_json JSON NULL,
    retrieved_codes_json JSON NULL,
    hit_at1 TINYINT NOT NULL DEFAULT 0,
    hit_at3 TINYINT NOT NULL DEFAULT 0,
    hit_at5 TINYINT NOT NULL DEFAULT 0,
    reciprocal_rank DECIMAL(10,4) NULL,
    recall_at5 DECIMAL(10,4) NULL,
    ndcg_at10 DECIMAL(10,4) NULL,
    latency_ms INT NULL,
    citation_total INT NULL,
    citation_hit INT NULL,
    citation_accuracy DECIMAL(10,4) NULL,
    faithfulness DECIMAL(10,4) NULL,
    error_message VARCHAR(1000),
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_eval_result_run (run_id),
    KEY idx_eval_result_case (case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
