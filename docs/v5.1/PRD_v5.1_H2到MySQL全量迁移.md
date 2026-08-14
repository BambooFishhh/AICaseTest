# v5.1 PRD：H2 → MySQL 全量迁移工具

## 1. 迭代背景与痛点

- v5.0 已建立 MySQL 基建，但存量业务数据仍停留在 H2 文件库，需要可重复执行的全量迁移工具。
- 迁移涉及 13 张表、多种列类型（TEXT/JSON 字符串、DATETIME、DOUBLE、BOOLEAN），需要通用的 JDBC 复制逻辑而非逐表手写。
- 迁移必须可审计、可回滚：迁移前备份 H2 文件，失败时清理 MySQL 半成品，避免脏数据。
- 方言差异（H2 vs MySQL）需要回归验证：列名保留字（`action`）、布尔/位类型、时间精度等。

## 2. 范围（In / Out of scope）

### In scope

- 通用 H2 → MySQL 全量迁移器（启动开关控制，默认关闭）。
- 迁移前 H2 文件备份；迁移失败时反向清理 MySQL 已写入表。
- 迁移后逐表行数校验 + 摘要文件。
- `migrate` profile 与文档化运行/回滚流程。
- 方言回归：编译 + 单测 + MySQL 实库冒烟验证方案。

### Out of scope

- Redis / Milvus（v5.2 ~ v5.4）。
- 正式切换默认数据源（v5.5）。
- 前端改动。

## 3. 功能详情

### 3.1 迁移器

- 新组件 `com.testagent.migration.H2ToMysqlMigrator`，实现 `CommandLineRunner`，仅当 `app.migration.enabled=true` 时执行。
- 数据源：H2 文件库（手工 JDBC 连接，默认 `./data/appdb`）；MySQL 复用 Spring 主数据源。
- 表顺序：users → project_groups → group_members → projects → test_cases → test_case_versions → state_machines → code_analysis → mindmaps → test_suites → execution_record → execution_step → system_settings。
- 通过 JDBC metadata 动态读取源表列，生成 `INSERT INTO \`table\` (...) VALUES (...)`，避免逐列硬编码。
- 迁移前将 `data/appdb*.mv.db` 等文件复制到 `backups/h2-backup-{时间戳}/`。
- 任一步失败：记录日志并清空本次已迁移的 MySQL 表（反向顺序），保证可重跑。

### 3.2 校验与摘要

- 每张表迁移后比较 H2/MySQL 行数。
- 结束后写 `backups/migration-{时间戳}.json`，包含逐表行数与结果。

### 3.3 回滚

- `app.migration.rollback=true` 时执行回滚：找到最近一次备份目录，将 H2 文件恢复回 `data/`。
- 回滚只恢复 H2 文件；MySQL 侧由失败清理或人工清库处理。

### 3.4 运行方式

```powershell
cd backend
$env:APP_MIGRATION_ENABLED="true"
mvn spring-boot:run "-Dspring-boot.run.profiles=migrate" -Dmaven.repo.local=../.m2-repo
```

## 4. 验收标准

1. 新增依赖/代码后 `mvn compile` BUILD SUCCESS。
2. 默认启动不触发迁移（enabled 默认 false）。
3. 空 H2 / 空 MySQL 场景迁移器安全跳过。
4. 有数据 H2 迁移到空 MySQL 后逐表行数一致。
5. 迁移前生成 H2 备份目录；失败场景 MySQL 表被清理。
6. 前端 `npm run build` 成功（无前端改动）。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| H2 文件被占用 | 迁移用独立进程运行；备份失败时告警并中止 |
| MySQL 部分写入后失败 | 失败反向清空本次涉及表 |
| 方言差异 | 动态读取 metadata + 双引号/反引号按库适配；回归验证保留字列 |
| 误删 MySQL 已有数据 | 文档明确要求目标为空库或人工确认 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审三份文档
- `H2ToMysqlMigrator` + `application-migrate.yml`
- CHANGELOG / README 更新
