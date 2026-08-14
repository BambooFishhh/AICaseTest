-- v5.7: 高频查询复合索引
CREATE INDEX idx_exec_project_time ON execution_record (project_id, start_time DESC);
CREATE INDEX idx_exec_batch_status ON execution_record (batch_id, status);
CREATE INDEX idx_testcases_project_module ON test_cases (project_id, module);
CREATE INDEX idx_versions_testcase_no ON test_case_versions (test_case_id, version_no);
CREATE INDEX idx_analysis_project_created ON code_analysis (project_id, created_at);
CREATE INDEX idx_mindmap_project_created ON mindmaps (project_id, created_at);
