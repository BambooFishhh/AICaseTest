# v6.9 PRD：高可用收口 - 故障演练与容量

> 版本 v6.9，一旦确定尽量不要轻易改动。基线 v6.8：Redis Streams 事件总线、CAS 抢占、状态迁 Redis、告警已就绪。

## 1. 迭代背景与痛点

- 任务只有最终状态与单值 checkpoint，缺少可回放的 phase/status timeline。
- 故障恢复没有可执行演练脚本，人工验证成本高。
- 容量与阈值没有简单入口，无法快速确认队列/TTL/熔断基线。

## 2. 范围（In / Out of scope）

### In scope

- `agent_task_events` timeline 表 + 管理端回放接口。
- 前端任务中心详情抽屉展示任务回放。
- 故障演练脚本 `scripts/ha-fault-drill.ps1`。
- 容量/阈值演练脚本 `scripts/ha-capacity-drill.ps1`。
- 运维手册补高可用章节。

### Out of scope

- worker 自动扩缩（可选 v6.10）。
- 生产环境真实压测执行（由脚本引导 CI/运维执行）。

## 3. 功能详情

### 3.1 任务 timeline

- Flyway V10 新增 `agent_task_events`。
- `AgentTaskService.update` 每次状态/phase/checkpoint 变更自动记录事件。
- `GET /api/admin/tasks/{id}/timeline` 返回按时间正序回放。
- 前端任务中心详情抽屉展示 timeline。

### 3.2 演练脚本

- `ha-fault-drill.ps1`：健康检查 + 故障注入清单（LLM 异常/工具挂起/kill -9/Redis 宕机/取消）。
- `ha-capacity-drill.ps1`：输出任务租约/TTL/MCP 超时配置，可选拉取 stats，给出阈值基线。

## 4. 验收标准

1. 每次 agent_task 状态变更产生 timeline 事件。
2. 管理端 timeline 接口与前端回放可用。
3. 两个演练脚本可运行且包含明确阈值。
4. 运维手册含 HA 章节。
5. `mvn verify`、`npm run build` 通过。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| timeline 写库增加高频写入 | 事件表独立、异步场景可后续转缓冲；失败仅告警不阻断任务 |
| 演练脚本误操作 | 脚本保持只读，故障注入命令以清单形式输出 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- timeline 表/接口/前端回放
- 故障演练与容量演练脚本
- 运维手册更新
