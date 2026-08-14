# v5.3 PRD：缓存与任务队列

## 1. 迭代背景与痛点

- 系统设置、项目生成参数、代码分析/状态机结果在每次请求时都重新查库/重新解析，读多写少却无缓存。
- 生成/执行任务只有线程池队列，没有可观测的排队与运行计数，多实例下无法统一查看任务压力。
- 需要引入 Spring Cache（Redis 开启时）与轻量任务队列统计层，为 v5.5 正式切换打底。

## 2. 范围（In / Out of scope）

### In scope

- Spring Cache：系统设置、默认生成参数、项目生成参数、分析结果、状态机结果。
- 缓存失效策略：更新设置/参数/重新分析后自动 evict。
- 任务队列：`TaskQueueStore`（内存/Redis Set），记录生成/执行任务的 queued/running。
- 统计 API `GET /api/tasks/stats`。
- 前端仪表盘展示任务队列计数。

### Out of scope

- Milvus 语义层（v5.4）。
- 正式切换默认数据源/运行态（v5.5）。

## 3. 功能详情

### 3.1 缓存

| 缓存名 | Key | 写入 | 失效 |
|---|---|---|---|
| settings | llm / generationParams | 系统设置读取 | 设置/默认参数更新 |
| projectParams | projectId | 项目生成参数读取 | 参数/执行环境更新 |
| analysis | projectId | 分析结果读取 | 重新分析 |
| stateMachines | projectId | 状态机读取 | 重新分析 |

- `APP_REDIS_ENABLED=true`：`RedisCacheManager`（10 分钟 TTL）。
- 默认关闭：`ConcurrentMapCacheManager`，本地零依赖。

### 3.2 任务队列

- `TaskQueueStore`：enqueue / markRunning / markDone / queuedCount / runningCount。
- Redis 实现使用 Set：`rt:queue:{queue}:queued`、`rt:queue:{queue}:running`。
- 生成任务：SSE 端点 enqueue → 生成线程 markRunning → finally markDone。
- 执行任务：`ExecutionService.execute` enqueue → worker 启动 markRunning → 收尾 markDone。
- 统计端点返回 `{generation:{queued,running}, execution:{queued,running}}`。

## 4. 验收标准

1. 后端编译/测试通过。
2. 默认内存缓存下设置/参数/分析接口行为不变。
3. 更新参数或重新分析后缓存失效，前端能读到新值。
4. `/api/tasks/stats` 返回合法计数。
5. 前端仪表盘显示任务队列卡片，构建成功。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 缓存脏读 | 写路径全部 evict；分析完成后主动 evict |
| Redis 开启时本地无 Redis | 默认内存缓存，Redis 仅显式开启 |
| 队列计数漂移 | markDone 幂等（Set remove） |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- CacheConfig、queue 包、TaskQueueService、TaskController
- SettingsService / ProjectService / AnalysisService 缓存注解
- Dashboard 队列卡片
