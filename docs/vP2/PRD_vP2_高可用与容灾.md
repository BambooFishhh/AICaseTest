# vP2 PRD：高可用与容灾

> 版本 vP2，一旦确定尽量不要轻易改动。迭代范围：MySQL 备份调度 + 恢复演练、任务恢复、资源限制、优雅停机。

## 1. 迭代背景与痛点

- 现有 `backup-v5.ps1` 仅做一次性手动备份，没有独立 MySQL 调度与恢复演练入口。
- 服务重启后分析/生成中的项目会卡在 `analyzing/generating`，任务队列计数残留。
- Docker 服务未声明资源上限，单个容器可能打满宿主机。
- 后端默认非优雅停机，重启可能截断进行中的 LLM/SSE/执行任务。

## 2. 范围（In / Out of scope）

### In scope

- `mysql-backup.ps1`：Docker 内 mysqldump 全量备份 + 保留天数轮转。
- `schedule-backup.ps1`：Windows 计划任务每日调度。
- `restore-drill.ps1`：恢复演练，恢复到临时库校验表数量后清理。
- 启动任务恢复：清空残留队列，恢复卡死的项目/执行状态。
- Docker Compose 资源限制与 `stop_grace_period`。
- Spring Boot 优雅停机与异步线程池等待。

### Out of scope

- 跨机房容灾、binlog/PITR、异地复制。
- Kubernetes 原生探针与自动重调度。

## 3. 功能详情

### 3.1 MySQL 备份调度

- `scripts/mysql-backup.ps1` 通过 `docker exec aicasetest-mysql mysqldump` 生成 `backups/mysql/aicasetest_yyyyMMdd_HHmmss.sql`，默认保留 14 天。
- `scripts/schedule-backup.ps1` 用 `schtasks` 注册每日 03:00 备份任务。

### 3.2 恢复演练

- `scripts/restore-drill.ps1` 取最新备份，恢复到 `aicasetest_drill` 临时库。
- 校验 `information_schema.tables` 表数量 > 0，通过后删除临时库；失败也会清理，不影响生产。

### 3.3 任务恢复

- `TaskQueueStore.clearQueue`：内存/Redis 队列清空 queued/running。
- `TaskQueueService.recoverStaleTasks`：启动时清理 generation/execution 残留计数。
- `DataInitializer`：重启后项目 `analyzing/generating` 状态恢复——已有用例置为 `completed`，否则置为 `failed` 并写错误信息；执行记录 `running/pending` 标记中断/取消。

### 3.4 资源限制

- compose 各服务增加 `deploy.resources.limits`：MySQL 1g/2 CPU、Redis 256m/0.5、etcd 512m/0.5、MinIO 1g/1、Milvus 2g/2、backend 2g/2、frontend 128m/0.5。

### 3.5 优雅停机

- `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`。
- `AsyncConfig` 线程池 `waitForTasksToCompleteOnShutdown(true)` + 等待 30s。
- compose 设置 `stop_grace_period`：backend 60s，frontend 20s，MySQL 30s 等。

## 4. 验收标准

1. `mvn compile` BUILD SUCCESS，`MemoryTaskQueueStoreTest` 通过。
2. `npm run build` 成功（无前端源码变更，回归验证）。
3. `docker compose config` 校验通过（含 deploy 资源限制）。
4. 备份脚本可生成 `.sql` 并按保留天数轮转。
5. 恢复演练可恢复到临时库并校验表数量。
6. 重启后不再出现残留 `analyzing/generating` 项目或队列计数。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 恢复演练误操作生产库 | 固定使用独立临时库，finally 中强制清理 |
| 自动备份占用磁盘 | 保留天数轮转 + 可配置 BackupRoot/KeepDays |
| 资源限制过紧导致 OOM | 限制值按服务基线设置，JVM 已限制 2g |
| 自动重跑任务产生副作用 | 恢复只重置状态，不自动重跑，由用户手动重试 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- 三个 PowerShell 脚本（备份/调度/恢复演练）
- 任务队列恢复与项目状态恢复
- 资源限制、优雅停机配置
