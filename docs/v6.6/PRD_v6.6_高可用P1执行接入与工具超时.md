# v6.6 PRD：高可用 P1 - 执行接入与工具超时

> 版本 v6.6，一旦确定尽量不要轻易改动。基线 v6.5：agent_task 持久化/租约恢复/管理端重试/LLM 重试分类已完成。

## 1. 迭代背景与痛点

- `McpConnection.sendRequest` 使用 `future.get()` 无超时，Playwright/tools 子进程挂起时任务永久卡 RUNNING。
- 执行任务仍只依赖 `execution_record` 与内存心跳，未接入 `agent_task`，重启后无法区分"已提交/运行中/中断"。
- 任务没有 TTL，进程活着但 LLM/工具长时间无响应时无法兜底转人工。
- 任务与工具失败没有 Prometheus 计数，无法触发告警。

## 2. 范围（In / Out of scope）

### In scope

- MCP 工具调用超时（可配置秒数），覆盖普通与流式调用。
- 工具错误分类与幂等工具自动重试 1 次，非幂等工具不自动重试。
- 执行任务接入 `agent_task`：创建、start、checkpoint（browser_launch/step{N}）、succeed/fail/cancel。
- 任务 TTL：RUNNING 超过上限标记 NEEDS_REVIEW。
- QUEUED 任务调度器：定时补齐因重启/异常遗留的排队任务。
- 任务与工具 Prometheus 指标：started/completed/failed/lease_expired/dlq、tool.failures。

### Out of scope

- checkpoint 断点续跑（v6.7）。
- Redis Streams consumer groups 与多实例 worker（v6.8）。
- 前端任务中心（v6.7）。
- 熔断器（v6.7）。

## 3. 功能详情

### 3.1 MCP 工具超时

- 新增配置 `app.mcp.request-timeout-seconds`，默认 60s。
- `McpConnection.sendRequest` / `callToolStreamingWithMeta` 改为 `future.get(timeout, SECONDS)`，超时抛 `TOOL_TIMEOUT` 并清理 pending。
- `McpClientManager` 对超时/进程退出错误做分类，幂等工具（截图/状态/滚动/只读查询）重试 1 次，非幂等工具直接失败。

### 3.2 工具错误分类与幂等策略

| 错误码 | 说明 | 自动重试 |
|---|---|---|
| TOOL_TIMEOUT | 请求超时 | 幂等工具重试 1 次 |
| TOOL_UNAVAILABLE | 子进程未启动/退出 | 幂等工具重试 1 次并尝试重启 |
| TOOL_ERROR | 工具返回 error/isError | 不重试 |
| TOOL_NON_IDEMPOTENT | 点击/输入/提交等副作用工具超时 | 不重试，转步骤失败 |

### 3.3 执行任务接入

- `ExecutionService.execute` 创建 `execution` 类型 `agent_task`，taskId = executionId。
- `runAgentAsync` / `runAsync`：limiter 领取后 `start`，浏览器启动后 `checkpoint("browser_launch")`，每个步骤后 `checkpoint("step_{N}")`。
- 结束：cancelled → `cancel`；failed/error → `fail("EXECUTION_FAILED")`；passed → `succeed`。
- `cancelExecution` / `cancelBatch` / `finalizeCancelled` 同步 `cancel`。

### 3.4 任务 TTL 与 QUEUED 调度

- 新增 `HaTaskScheduler`：
  - 启动/定时恢复租约过期 RUNNING 任务（沿用 v6.5 语义）。
  - 每 10 分钟检查 RUNNING 超过 `app.ha.task-ttl-minutes` 的任务，标记 `TTL_EXCEEDED` + NEEDS_REVIEW。
  - 每 15 秒分发 QUEUED 任务（analysis/generation 支持自动分发，append_generation 保留前端重试）。

### 3.5 指标

- `aicasetest.task.started/completed/failed/lease_expired/dlq`
- `aicasetest.tool.failures_total`（tags: server/tool/error_code）

## 4. 验收标准

1. 工具调用超过配置秒数后失败并记录 `TOOL_TIMEOUT`，不再无限等待。
2. 幂等工具超时自动重试一次；点击/输入不自动重试。
3. 单条/批量/复制执行均写入 `agent_task`，终态与 `execution_record` 一致。
4. 取消执行同步将 agent_task 置 CANCELLED。
5. RUNNING 超过 TTL 的任务被标记 NEEDS_REVIEW。
6. QUEUED 任务由调度器在 15s 内被分发。
7. `mvn verify` 与 `npm run build` 通过，指标端点可见新计数。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 给流式 LLM 加超时误杀长推理 | 超时默认 60s，LLM 视觉调用通过 mcp 超时配置单独放宽；流式超时后任务转 NEEDS_REVIEW 不自动覆盖旧结果 |
| 幂等工具误判导致重复副作用 | 重试名单保守，只含截图/状态/滚动/只读调用 |
| 执行任务与 ExecutionRecord 状态不一致 | taskId=executionId，终态统一在收尾阶段写入 |
| 新增调度器与手动重试竞争 | QUEUED 分发沿用 retry 的 CAS/状态检查，只处理 QUEUED |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- MCP 超时与工具重试
- 执行任务接入 agent_task
- 任务 TTL 与 QUEUED 调度
- 任务/工具指标
- 单测与文档更新
