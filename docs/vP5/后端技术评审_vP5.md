# 后端技术评审 vP5：压测与容量

> 版本 vP5，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 SQL 分页

```java
Specification<TestCase> spec = buildTestCaseSpec(projectId, type, module, keyword, reviewStatus, executionStatus);
Page<TestCase> pageResult = testCaseRepository.findAll(spec,
        PageRequest.of(Math.max(0, page - 1), Math.max(1, pageSize)));
```

`TestCaseRepository` 扩展 `JpaSpecificationExecutor<TestCase>`。

### 1.2 索引迁移

```sql
CREATE INDEX idx_testcases_project_type ON test_cases (project_id, type);
CREATE INDEX idx_testcases_project_review_status ON test_cases (project_id, review_status);
CREATE INDEX idx_testcases_project_execution_status ON test_cases (project_id, execution_status);
CREATE INDEX idx_testcases_project_title ON test_cases (project_id, title);
```

### 1.3 线程池

- `app.executor.keep-alive-seconds` / `await-termination-seconds` 参数化。
- 默认：analysis/generation max=6 queue=50；execution core=4 max=12 queue=500；project-execution-max=5。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| repository/TestCaseRepository.java | 增加 JpaSpecificationExecutor |
| service/TestCaseService.java | Specification 分页 + buildTestCaseSpec |
| resources/db/migration/mysql/V3__add_testcase_pagination_indexes.sql | 新增 |
| config/AsyncConfig.java | keep-alive/await 参数化 |
| resources/application.yml | 线程池默认调优 |

## 3. API 契约变化

无；`GET /api/projects/{id}/testcases?page=&pageSize=` 语义不变。

## 4. 向后兼容性

- 原有内存过滤规则（coalesce null → not_executed/draft）在 Specification 中保持一致。
- 新索引不影响旧数据。
- 线程池默认值变化只影响并发容量，不改变接口行为。

## 5. 测试验证方案

- `mvn test` 全量回归（41 个测试，5 个环境跳过）。
- `mvn compile`。
- `scripts/pagination-baseline.ps1` 语法检查。
