# v6.5 PRD：高可用 P0 - 任务状态持久化与中断恢复

> 版本 v6.5，一旦确定尽量不要轻易改动。依据《高可用优化分析方案》P0 项落地：任务持久化、租约心跳、启动恢复、管理端重试与 LLM 重试分类。

## 1. 迭代背景与痛点

- 现有 `TaskQueueService` 只有内存/Redis 计数，重启即清空且没有任务记录；`DataInitializer` 把卡死项目一律按"是否已有用例"复位，无法区分"已完成/部分完成/中断"。
- 分析/生成失败时没有错误码、attempt、phase、checkpoint，无法支持人工恢复与后续续跑。
- `LlmService` 对所有异常固定重试 3 次，4xx/模型拒绝也会白白等待；重试间隔无抖动，瞬时恢复场景容易再次撞限流。
- 缺少管理端任务入口，运维无法看到卡死任务及其错误详情。

## 2. 范围（In / Out of scope）

### In scope

- 新增 `agent_task` 表（MySQL Flyway V8）+ JPA 实体/仓储。
- 新增 `AgentTaskService`：创建、启动、checkpoint、心跳续租、成功/失败/取消/人工复核/DLQ、租约恢复、查询与状态统计。
- 分析（含 SSE 流式）与生成（普通/SSE/追加）接入任务生命周期。
- 启动恢复：stale RUNNING 任务标记 NEEDS_REVIEW，项目状态保留可重试信息；不再"只清空队列"。
- 管理端 API：任务列表/详情/重试；`/api/tasks/stats` 增加 agentTask 状态统计。
- LLM 重试分类：非可重试错误立即失败；重试延迟加抖动；`llm.retry.max-attempts` 可配置。
- 单元测试 + 文档更新。

### Out of scope

- Redis Streams / consumer group 多实例 worker。
- 执行任务租约接入（ExecutionRecord 沿用现有恢复逻辑，后续版本接入）。
- 从 checkpoint 自动续跑（v6.5 只持久化 + 人工重试，续跑放 P1）。
- 前端任务中心页面（仅扩展现有统计接口，页面放 P1）。
- 断路器、动态重试策略、任务 timeline 回放。

## 3. 功能详情

### 3.1 agent_task 任务状态机

- 状态：CREATED / QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED / NEEDS_REVIEW / DLQ。
- 关键字段：request_id（防重复创建）、task_type、project_id、phase、attempts/max_attempts、input_json、checkpoint_json、error_code/message、degraded、lease_owner/lease_expire_at/heartbeat_at。
- 生命周期：
  1. 进入异步方法后创建 QUEUED，校验通过后 `start`（RUNNING + lease + attempts+1）。
  2. 各阶段边界 `checkpoint` 写入 phase + heartbeat + 续租。
  3. 成功 `succeed`；失败 `fail`；用户取消 `cancel`。
  4. 启动/定时恢复将 lease 过期任务标记 `NEEDS_REVIEW`（人工确认后重试）。

### 3.2 接入点

- `AnalysisService.runAnalysisWithProgress`：scan/parse/state_machine/index 四阶段 checkpoint；成功/失败收尾。
- `TestCaseService.runGenerate`：validate/generate/persist/index 阶段 checkpoint。
- `TestCaseService.runGenerateStream`：同上，取消时 `cancel`。
- `TestCaseService.runGenerateStreamAppend`：类型 `APPEND_GENERATION`，取消/失败同样收尾；因需要 SSE emitter，管理端不自动重跑，标记 NEEDS_REVIEW。
- `DataInitializer`：先 `agentTaskService.recoverStaleTasks()` 再复位项目状态，恢复结果写入日志。

### 3.3 管理端任务 API

- `GET /api/admin/tasks?taskType=&status=&projectId=&page=&size=`：分页列表。
- `GET /api/admin/tasks/{id}`：详情（含 checkpoint/error/lease）。
- `POST /api/admin/tasks/{id}/retry`：将 FAILED/DLQ/NEEDS_REVIEW 任务重新入队并立即分发；`APPEND_GENERATION` 返回"请在前端重新触发"。
- `GET /api/tasks/stats`：原有队列统计追加 `agentTasks` 状态计数。

### 3.4 LLM 重试分类

- 可重试：超时/连接重置/网络 IO/HTTP 408/409/425/429/5xx。
- 不可重试：用户取消、其他 4xx、无匹配特征的异常（默认不重试，避免模型拒绝反复耗时）。
- 重试延迟：1s/2s/4s 基数 + 20% 随机抖动；次数由 `LLM_RETRY_MAX_ATTEMPTS` 控制。

## 4. 验收标准

1. `mvn compile` BUILD SUCCESS；新增 `AgentTaskServiceTest`、`LlmRetryPolicyTest` 通过。
2. `npm run build` 成功（无前端源码变更，回归验证）。
3. 手动/流式/追加生成与分析均写入 `agent_task`，成功为 SUCCEEDED，失败为 FAILED，取消为 CANCELLED。
4. 模拟断开的 RUNNING 任务（lease 过期）在调用恢复后变为 NEEDS_REVIEW，且项目状态可继续重试。
5. 管理端列表/详情/重试 API 返回正确；非管理员访问 403。
6. LLM 4xx 不再盲目重试 3 次；可重试错误按带抖动间隔重试。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 自动重跑触发重复生成/覆盖人工用例 | v6.5 恢复只标记 NEEDS_REVIEW，不自动重跑；重试由管理员确认后触发 |
| 追加生成因 SSE emitter 无法后台重放 | 管理端对其返回"请在前端重新触发"，不做自动分发 |
| Flyway 迁移影响存量 MySQL | 独立新表，无存量列变更；H2 dev 由 JPA `ddl-auto` 自动建表 |
| 新增锁/恢复逻辑影响正常任务 | 所有租约操作幂等；checkpoint 失败仅告警不阻断主流程 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审 / 方案存档
- `agent_task` 表与实体/仓储
- `AgentTaskService` + `TaskRetryDispatcher`
- 分析/生成链路接入与启动恢复改造
- 管理端任务 API + 统计扩展
- LLM 重试分类与配置
- 单元测试、CHANGELOG/README 更新
