-- v8.1: Scope-Aware 范围感知基础 —— 范围定义与范围内元素
CREATE TABLE IF NOT EXISTS scope_definition (
    id VARCHAR(8) PRIMARY KEY,
    project_id VARCHAR(8) NOT NULL,
    name VARCHAR(128) NOT NULL,
    baseline_ref VARCHAR(256) NOT NULL,
    head_ref VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    changed_files TEXT,
    created_at DATETIME,
    updated_at DATETIME,
    KEY idx_scope_def_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS scope_item (
    id VARCHAR(8) PRIMARY KEY,
    definition_id VARCHAR(8) NOT NULL,
    item_type VARCHAR(20) NOT NULL,
    item_ref VARCHAR(512) NOT NULL,
    change_kind VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    note TEXT,
    created_at DATETIME,
    KEY idx_scope_item_def (definition_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
