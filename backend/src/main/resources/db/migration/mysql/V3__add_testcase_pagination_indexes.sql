-- vP5: 大数据量分页与筛选索引
CREATE INDEX idx_testcases_project_type ON test_cases (project_id, type);
CREATE INDEX idx_testcases_project_review_status ON test_cases (project_id, review_status);
CREATE INDEX idx_testcases_project_execution_status ON test_cases (project_id, execution_status);
CREATE INDEX idx_testcases_project_title ON test_cases (project_id, title);
