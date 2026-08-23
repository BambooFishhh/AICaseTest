-- v7.15(2a): 用例双编号制——id 保持全局唯一 TC-xxx，新增项目内展示序号
ALTER TABLE test_cases ADD COLUMN project_seq INT NULL;

-- 存量数据回填：按项目内创建顺序（created_at, id 稳定排序）从 1 编号
-- （派生表物化规避 MySQL 1093 同表更新限制；ROW_NUMBER 为 MySQL 8 语法，
--   本迁移仅在 mysql profile 的 Flyway 下执行，H2 dev 由 JPA ddl-auto 建列）
UPDATE test_cases t
JOIN (
    SELECT t2.id,
           ROW_NUMBER() OVER (PARTITION BY t2.project_id ORDER BY t2.created_at, t2.id) AS rn
    FROM test_cases t2
) s ON s.id = t.id
SET t.project_seq = s.rn;

CREATE INDEX idx_testcases_project_seq ON test_cases (project_id, project_seq);
