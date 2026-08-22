# v6.7 PRD：高可用 P2 - 断点续跑与降级闭环

> 版本 v6.7，一旦确定尽量不要轻易改动。基线 v6.6：MCP 工具超时/幂等重试、执行任务接入 agent_task、任务 TTL 与 QUEUED 调度已完成。

## 1. 迭代背景与痛点

- 分析重试仍是全量重跑，已有 completed 分析结果时浪费扫描/解析成本。
- 生成走规则兜底后没有降级标记，管理端无法区分"LLM 正常/降级产出"。
- LLM provider 连续故障时仍逐任务重试，缺少快速短路能力。
- 任务埋点没有关联 agent_task 的 task_id/attempt，无法做单任务耗时/重试追溯。
- 管理端只有 API，缺少任务中心页面，人工兜底不直观。

## 2. 范围（In / Out of scope）

### In scope

- 分析断点续跑：已有 completed 分析结果时跳过扫描/解析，重建语义索引与缺失状态机。
- 降级闭环：规则兜底生成的 agent_task 标记 `degraded=true`，指标计数。
- LLM provider 熔断：连续失败阈值短路，成功重置，可配置。
- Telemetry 关联：`task_telemetry` 增加 `task_id` / `attempt`，分析与生成埋点回填。
- 前端任务中心：列表/筛选/分页/详情/重试，仅 ADMIN。

### Out of scope

- 生成模块级 checkpoint 续跑（保留既有"分模块规则兜底"的部分结果语义，续跑队列化放 v6.8）。
- 执行步骤断点自动续跑（步骤已逐条落库，Retry UI 仍为重新执行）。
- Redis Streams / 多实例 worker（v6.8）。

## 3. 功能详情

### 3.1 分析断点续跑

- `AnalysisService.runAnalysisResume(projectId, sourcePath, taskId)`：
  - 有 completed 且含前后端结果：跳过扫描/解析，重建语义上下文与组件索引，缺失状态机时重新提取。
  - 无可用结果：复用同一 agent_task 全量重跑。
- `TaskRetryDispatcher` 分析重试统一走 `runAnalysisResume`。

### 3.2 降级标记

- `AgentTaskService.markDegraded(taskId)`：置 `degraded=true` 并计数 `aicasetest.task.degraded_total`。
- 生成/流式/追加生成结果中出现 `source=rule_based` 用例时调用。

### 3.3 LLM 熔断

- `LlmCircuitBreaker`：连续失败 >= `llm.circuit.failure-threshold`（默认 5）后打开 `llm.circuit.open-seconds`（默认 30s）。
- 打开期间非首次 LLM 调用直接返回 503 BusinessException；成功调用重置计数。

### 3.4 Telemetry 关联

- Flyway V9：`task_telemetry` 增加 `task_id`、`attempt`。
- `TelemetryService.start(taskType, projectId, taskId, attempt)` + ThreadLocal 任务上下文，由分析/生成异步线程回填。

### 3.5 前端任务中心

- `GET /api/admin/tasks` 分页列表（taskType/status/projectId 筛选）。
- 详情抽屉展示 phase/attempt/degraded/error/lease/checkpoint。
- FAILED/DLQ/NEEDS_REVIEW 支持重试按钮。

## 4. 验收标准

1. 已有 completed 分析结果时重试不重复扫描，分析任务可续跑至 analyzed。
2. 规则兜底生成的 agent_task 为 `degraded=true` 且有指标计数。
3. LLM 连续失败 5 次后熔断打开，成功一次后恢复。
4. `task_telemetry` 新分析与生成记录回填 task_id/attempt。
5. 任务中心页面可查询/详情/重试；非 ADMIN 访问返回 403。
6. `mvn verify`、`npm test`、`npm run build` 通过。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 续跑复用旧分析结果导致与代码不一致 | 续跑只在旧结果 completed 时跳扫描；代码变化仍可全量重分析 |
| 熔断误伤正常请求 | 阈值可配，成功立即重置；打开期只短路 LLM 调用，任务转 NEEDS_REVIEW |
| 前端任务中心越权 | 后端 `/api/admin/tasks` 已有 ADMIN 校验，前端仅管理员可见 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- 分析断点续跑
- degraded 标记与指标
- LLM 熔断器
- Telemetry task_id/attempt
- 前端任务中心
- 单测与文档更新
