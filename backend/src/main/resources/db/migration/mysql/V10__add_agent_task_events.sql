-- v6.9: 任务 timeline 回放事件表
CREATE TABLE agent_task_events (
    id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    phase VARCHAR(64),
    status VARCHAR(32),
    attempt INT,
    error_code VARCHAR(32),
    error_message TEXT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_task_events_task (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
