# v6.8 PRD：高可用 P3 - 队列与多实例

> 版本 v6.8，一旦确定尽量不要轻易改动。基线 v6.7：断点续跑、降级标记、LLM 熔断、前端任务中心已完成。

## 1. 迭代背景与痛点

- 调度仍以 DB 轮询为主，缺少低延迟的 Redis Streams 事件驱动。
- QUEUED 领取没有 CAS，多 worker/手动重试并发时可能双执行。
- `AnalysisService.analysisRunning` / `ExecutionService.executionCancellations` 仍是本地 Map，多实例不自洽。
- DLQ 只有指标计数，没有告警规则。

## 2. 范围（In / Out of scope）

### In scope

- Redis Streams 任务事件总线：任务创建时发布事件，调度器消费后触发分发。
- DB CAS 抢占 QUEUED：`UPDATE ... WHERE status='QUEUED'`，多 worker 幂等领取。
- 本地状态迁 Redis：分析运行互斥、执行取消标志均以 RuntimeStore（Redis/内存降级）为准。
- Prometheus 告警：DLQ > 0、任务失败率过高。

### Out of scope

- Redis Streams 完整 consumer group 确认/重试/死信协议（当前 DB 为事实源，事件丢失由 DB 轮询兜底）。
- worker 自动扩缩（可选 v6.10）。
- 执行步骤断点自动续跑（保留人工重跑）。

## 3. 功能详情

### 3.1 事件总线

- `TaskEventStreamService`：stream `aicasetest:task:events`，group `ha-workers`。
- `AgentTaskService` 创建任务后 XADD 事件；`HaTaskScheduler` XREADGROUP 消费并立即 ACK。
- 事件仅作快速触发；DB `agent_task` 仍是唯一事实源，Redis 不可用时 DB 轮询正常分发。

### 3.2 CAS 抢占

- `AgentTaskRepository.claimQueued`：条件 `status='QUEUED'` 更新为 RUNNING + lease。
- `TaskRetryDispatcher.dispatchQueued` 先 CAS 抢占，失败返回"已被其他 worker 抢占"。

### 3.3 本地状态迁 Redis

- 分析并发互斥：`analysis:running:{projectId}` RuntimeStore flag。
- 执行取消：`exec:cancel:{executionId}` RuntimeStore flag，`isExecutionCancelled` 直接读 Redis。

### 3.4 告警

`monitoring/prometheus/alerts.yml` 新增：

- `AICaseTestDlqNonEmpty`：`aicasetest_task_dlq_total > 0`
- `AICaseTestTaskFailureRateHigh`：近 10 分钟任务失败率 > 20%

## 4. 验收标准

1. 创建 agent_task 后事件写入 Redis Stream，调度器可在 1 秒内消费。
2. 两个 worker 同时领取同一 QUEUED 任务，只有一个成功。
3. 重启/多实例下分析互斥与执行取消不再依赖本地 Map。
4. Prometheus 配置校验通过，DLQ/失败率告警存在。
5. `mvn verify`、`npm run build` 通过。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| Redis Streams API 兼容性问题 | 事件只是触发信号，失败仅告警，DB 轮询兜底 |
| CAS 把任务从 QUEUED 置 RUNNING 但分发失败 | 分发失败标 NEEDS_REVIEW；TTL 恢复兜底 |
| 取消标志迁移改变现有行为 | RuntimeStore 内存/Redis 双实现，语义一致 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- Redis Streams 事件总线
- CAS 抢占
- 本地状态迁 Redis
- DLQ/失败率告警
