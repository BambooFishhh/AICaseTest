-- v8.6.1(9.3): Milvus↔MySQL 周期对账报告
CREATE TABLE reconciliation_reports (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  db_count BIGINT NOT NULL,
  vec_count BIGINT NOT NULL,
  drift_ratio DOUBLE PRECISION NOT NULL,
  repaired_added INT NOT NULL DEFAULT 0,
  repaired_removed INT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  message TEXT NULL,
  created_at DATETIME(6) NULL
);

CREATE INDEX idx_reconciliation_reports_project ON reconciliation_reports(project_id, created_at);
