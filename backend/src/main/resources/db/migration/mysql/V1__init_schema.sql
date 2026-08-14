-- v5.0: AICaseTest MySQL 基线 schema
-- 对应 JPA 实体：Project / TestCase / TestCaseVersion / ExecutionRecord / ExecutionStep /
-- StateMachine / CodeAnalysis / MindMap / TestSuite / User / ProjectGroup / GroupMember / SystemSetting

CREATE TABLE projects (
    id VARCHAR(8) NOT NULL,
    name VARCHAR(255),
    source_type VARCHAR(255) DEFAULT 'local_path',
    source_path VARCHAR(255),
    user_id VARCHAR(64),
    group_id VARCHAR(64),
    tech_stack TEXT,
    status VARCHAR(255) DEFAULT 'created',
    settings TEXT,
    prd_content TEXT,
    prd_source_type VARCHAR(32),
    prd_source_ref VARCHAR(512),
    error_message TEXT,
    progress VARCHAR(255),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_projects_user (user_id),
    KEY idx_projects_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE test_cases (
    id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255),
    title VARCHAR(255),
    module VARCHAR(255),
    type VARCHAR(255),
    priority VARCHAR(255),
    preconditions TEXT,
    steps TEXT,
    expected_results TEXT,
    state_machine_ref TEXT,
    source VARCHAR(255),
    confidence DOUBLE,
    structured_steps TEXT,
    api_endpoints TEXT,
    test_data TEXT,
    execution_hints TEXT,
    execution_status VARCHAR(255),
    review_status VARCHAR(255),
    quality_score INT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_testcases_project (project_id),
    KEY idx_testcases_exec_status (execution_status),
    KEY idx_testcases_review_status (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE test_case_versions (
    id VARCHAR(255) NOT NULL,
    test_case_id VARCHAR(255),
    project_id VARCHAR(255),
    version_no INT,
    snapshot TEXT,
    `action` VARCHAR(255),
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_versions_testcase (test_case_id),
    KEY idx_versions_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE execution_record (
    id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255),
    test_case_id VARCHAR(255),
    test_case_title VARCHAR(255),
    status VARCHAR(255),
    start_time DATETIME(6),
    end_time DATETIME(6),
    summary VARCHAR(255),
    error_message VARCHAR(255),
    batch_id VARCHAR(255),
    mode VARCHAR(255),
    recording_frames VARCHAR(4096) DEFAULT '[]',
    recording_video_path VARCHAR(255),
    test_case_snapshot TEXT,
    operator VARCHAR(255),
    write_back BIT(1) DEFAULT b'1',
    PRIMARY KEY (id),
    KEY idx_exec_project (project_id),
    KEY idx_exec_testcase (test_case_id),
    KEY idx_exec_batch (batch_id),
    KEY idx_exec_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE execution_step (
    id VARCHAR(255) NOT NULL,
    execution_id VARCHAR(255),
    step_index INT,
    `action` VARCHAR(255),
    target VARCHAR(255),
    strategy VARCHAR(255),
    result VARCHAR(255),
    screenshot_before VARCHAR(255),
    screenshot_after VARCHAR(255),
    coordinates VARCHAR(255),
    error VARCHAR(255),
    PRIMARY KEY (id),
    KEY idx_step_execution (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE state_machines (
    id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255),
    name VARCHAR(255),
    description TEXT,
    states TEXT,
    transitions TEXT,
    forbidden_transitions TEXT,
    confidence DOUBLE,
    sources TEXT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_statemachine_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE code_analysis (
    id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255),
    frontend_result TEXT,
    backend_result TEXT,
    status VARCHAR(255),
    error_message TEXT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_analysis_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE mindmaps (
    id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255),
    title VARCHAR(255),
    file_path VARCHAR(255),
    statistics TEXT,
    status VARCHAR(255),
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_mindmap_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE test_suites (
    id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255),
    name VARCHAR(255),
    case_ids TEXT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_suite_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE users (
    id VARCHAR(255) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64),
    role VARCHAR(255) DEFAULT 'USER',
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE project_groups (
    id VARCHAR(255) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    owner_id VARCHAR(64) NOT NULL,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_groups_owner (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE group_members (
    id VARCHAR(255) NOT NULL,
    group_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'VIEWER',
    created_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_member (group_id, user_id),
    KEY idx_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE system_settings (
    setting_key VARCHAR(255) NOT NULL,
    setting_value TEXT,
    updated_at DATETIME(6),
    PRIMARY KEY (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
