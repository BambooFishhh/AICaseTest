-- v13.17(证据权威判定): 冲突持久化 + 人工裁决闭环
-- 背景：C2 证据链对账产出的冲突此前只注入 prompt，人看不到也无法裁决（断链）。
-- 本表让冲突落库可见、可裁决，裁决结果回写为规则供后续生成自动套用。
CREATE TABLE evidence_conflicts (
  id VARCHAR(32) NOT NULL PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  conflict_key VARCHAR(32) NOT NULL,
  dimension VARCHAR(32) NOT NULL,
  anchor VARCHAR(512) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  prd_side TEXT NULL,
  code_side TEXT NULL,
  requirement_state VARCHAR(16) NOT NULL,
  stale TINYINT(1) NOT NULL DEFAULT 0,
  authority VARCHAR(16) NOT NULL,
  manual_required TINYINT(1) NOT NULL DEFAULT 0,
  reason TEXT NULL,
  verdict VARCHAR(24) NOT NULL DEFAULT 'pending',
  verdict_note VARCHAR(512) NULL,
  verdict_by VARCHAR(64) NULL,
  verdict_at DATETIME(6) NULL,
  created_at DATETIME(6) NULL,
  updated_at DATETIME(6) NULL
);

CREATE INDEX idx_evidence_conflicts_project ON evidence_conflicts(project_id, created_at);
CREATE INDEX idx_evidence_conflicts_pending ON evidence_conflicts(project_id, verdict);
