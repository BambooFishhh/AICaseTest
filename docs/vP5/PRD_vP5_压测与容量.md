# vP5 PRD：压测与容量

> 版本 vP5，一旦确定尽量不要轻易改动。迭代范围：k6 压测基线、线程池/队列参数调优、大数据量分页与索引验证。

## 1. 迭代背景与痛点

- 缺少可重复执行的压测基线，版本性能变化无法量化。
- 线程池/队列参数虽可配置，但默认值偏保守且 keep-alive/停机等待不可配。
- `GET /testcases` 分页在 Java 内存中完成，大数据量项目会全量载入并过滤。
- 筛选字段只有 project_id 索引，组合筛选缺少复合索引支撑。

## 2. 范围（In / Out of scope）

### In scope

- k6 smoke/load 脚本与阈值基线。
- 线程池 keep-alive/等待停机参数化，默认值按 2CPU 容器调优。
- 用例列表分页下推数据库（JPA Specification + PageRequest）。
- MySQL 复合索引（type/review_status/execution_status/title × project_id）。
- `pagination-baseline.ps1`：EXPLAIN 验证分页与筛选索引。

### Out of scope

- 全链路压测（Playwright/LLM 真实调用）。
- 自动弹性伸缩与容量预算建模。

## 3. 功能详情

### 3.1 k6 压测基线

- `loadtest/k6/smoke.js`：1 VU × 10 次，P95 < 500ms，失败率 < 1%。
- `loadtest/k6/load.js`：1m 爬坡 → 2m 保持 → 1m 下降，默认 20 VU，P95 < 2s，失败率 < 5%。
- 场景：登录获取 token → `/api/health`、`/api/projects`、`/api/tasks/stats`。

### 3.2 线程池/队列调优

- 默认参数：analysis/generation core=2 max=6 queue=50；execution core=4 max=12 queue=500；project-execution-max=5。
- 新增 `EXECUTOR_KEEP_ALIVE_SECONDS`、`EXECUTOR_AWAIT_TERMINATION_SECONDS`。
- 全部参数通过 `.env.example` 暴露，可按容量调整。

### 3.3 大数据量分页与索引

- `TestCaseRepository` 增加 `JpaSpecificationExecutor`。
- `listTestCases` 使用 `Specification + PageRequest` 分页下推 SQL，筛选在数据库完成；覆盖率仍基于完整匹配集聚合。
- 新增 `V3__add_testcase_pagination_indexes.sql` 复合索引。
- `scripts/pagination-baseline.ps1` 输出 EXPLAIN 计划供人工/CI 校验索引命中。

## 4. 验收标准

1. `mvn test` 全部通过（含既有回归）。
2. `npm run build` 通过。
3. `docker compose config` 通过。
4. k6 脚本可运行，阈值明确。
5. 分页 EXPLAIN 脚本语法正确，索引迁移存在。
6. 线程池参数可被环境变量覆盖。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 分页改造破坏筛选语义 | Specification 保持原有 coalesce 规则；全量测试回归 |
| 覆盖率聚合仍全量载入 | 本轮先保功能，后续可改为 SQL 聚合 |
| 线程池调大挤占资源 | 与 vP2 资源限制配套，参数全部可调 |
| k6 默认账号弱密码 | 通过 `K6_ADMIN_*` 环境变量传入 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- k6 smoke/load 脚本与 README
- JPA Specification 分页 + V3 索引迁移
- 线程池参数调优与 `pagination-baseline.ps1`
