# v5.8 PRD：数据治理与可观测

## 1. 迭代背景与痛点

- 执行记录、录屏视频、证据 Markdown 无限增长，缺少保留策略与自动清理。
- 数据健康没有观测入口，孤儿数据、表规模、Milvus 向量规模无法快速查看。
- 迁移工具只能正式执行，无法先 dry-run 预览源表规模。

## 2. 范围（In / Out of scope）

### In scope

- 执行数据保留策略：`app.retention.execution-days` + 定时清理任务。
- 数据健康检查 API：`GET /api/admin/data/health`（表计数、孤儿数、Milvus 状态/计数）。
- 迁移 dry-run 模式：`app.migration.dry-run=true` 仅统计不写库。
- 安全：`/api/admin/**` 仅 ADMIN。

### Out of scope

- 新业务功能。

## 3. 功能详情

### 3.1 保留策略

```yaml
app:
  retention:
    execution-days: ${RETENTION_EXECUTION_DAYS:0}   # 0=关闭
    cron: ${RETENTION_CRON:0 0 3 * * *}             # 每天 03:00
```

- 清理 endTime 早于 cutoff 且状态为 passed/failed/cancelled 的执行记录。
- 同步删除执行步骤、录屏视频文件与证据文件。

### 3.2 数据健康 API

`GET /api/admin/data/health` 返回：

```json
{
  "tableCounts": {"projects": 1, "test_cases": 10, ...},
  "orphans": {"executionRecords": 0, "executionSteps": 0, "testCaseVersions": 0, "testSuites": 0},
  "milvus": {"enabled": false, "cases": -1, "contexts": -1, "failures": -1}
}
```

### 3.3 迁移 dry-run

- `APP_MIGRATION_ENABLED=true` + `APP_MIGRATION_DRY_RUN=true`：只输出各源表行数，不备份写入。

## 4. 验收标准

1. `mvn compile` / `mvn test` 通过。
2. 保留策略开启后定时清理执行数据与文件。
3. 数据健康 API 返回表计数与孤儿数；非 ADMIN 访问 403。
4. dry-run 模式不写 MySQL。
5. `npm run build` 成功。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 误删执行历史 | 默认 days=0 关闭；只删终态记录 |
| 健康检查全表扫描 | 仅 ADMIN 可调，数据量可控 |
| dry-run 误写 | 明确分支跳过所有 insert/backup |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- DataRetentionService / DataHealthService / DataHealthController
- SecurityConfig / application.yml / H2ToMysqlMigrator 增强
