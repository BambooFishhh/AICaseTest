-- v5.14: 分析/生成/AI 评审耗时与 token 埋点
CREATE TABLE task_telemetry (
    id VARCHAR(64) NOT NULL,
    project_id VARCHAR(64),
    task_type VARCHAR(32),
    phase VARCHAR(64),
    status VARCHAR(32),
    duration_ms BIGINT,
    first_token_ms BIGINT,
    prompt_tokens INT,
    completion_tokens INT,
    total_tokens INT,
    metadata TEXT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_telemetry_task (task_type, created_at),
    KEY idx_telemetry_project (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
