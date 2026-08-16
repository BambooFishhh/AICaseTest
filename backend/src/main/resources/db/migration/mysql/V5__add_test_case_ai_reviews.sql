-- v5.12: AI 评审历史独立表（生成与单条重评都写入审计记录）
CREATE TABLE test_case_ai_reviews (
    id VARCHAR(64) NOT NULL,
    project_id VARCHAR(255),
    test_case_id VARCHAR(255),
    status VARCHAR(32),
    issues LONGTEXT,
    suggested_changes LONGTEXT,
    coverage_refs LONGTEXT,
    confidence DOUBLE,
    source VARCHAR(32) DEFAULT 'generation',
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_ai_review_project (project_id),
    KEY idx_ai_review_testcase (test_case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
