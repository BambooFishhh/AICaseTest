-- v13.18(证据权威判定): 用例侧待裁决溯源标记
-- 背景：冲突已进入待裁决清单，但人还需知道"哪些用例是在存在待裁决冲突时生成的"，
-- 以便裁决后批量筛选复核 / 重生成。
-- 标记语义为生成期快照，粒度为项目级：仅当本轮对账存在 authority=human 的冲突时写入，
-- 重生成时若冲突已裁决则不再写入 → 标记自然清除，无需额外的清理任务。
ALTER TABLE test_cases ADD COLUMN verdict VARCHAR(24) NULL;
ALTER TABLE test_cases ADD COLUMN conflict_ref VARCHAR(512) NULL;
ALTER TABLE test_cases ADD COLUMN dimension VARCHAR(64) NULL;

CREATE INDEX idx_test_cases_verdict ON test_cases(project_id, verdict);
