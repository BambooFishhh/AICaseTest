# v5.2 PRD：Redis 运行态接入

## 1. 迭代背景与痛点

- 当前取消标志、执行心跳、浏览器会话、项目级并发配额、登录防爆破全部存放在 JVM 内存 Map 中，重启即丢，无法支撑多实例部署。
- v5.0/v5.1 已完成 MySQL 数据层，但"运行态"仍与进程绑定，多实例下取消/配额/锁定会互相不感知。
- 需要引入 Redis 作为统一运行态存储，同时保留无 Redis 时的内存降级，保证本地开发不受影响。

## 2. 范围（In / Out of scope）

### In scope

- 新增 `RuntimeStore` 抽象：内存实现 + Redis 实现，按 `APP_REDIS_ENABLED` 自动选择。
- 用例生成取消标志迁入 RuntimeStore。
- 执行取消标志、浏览器会话、心跳迁入 RuntimeStore。
- 项目级执行并发配额迁入 Redis（Lua 原子计数信号量）。
- 登录失败次数与锁定时间迁入 Redis Hash。
- docker-compose 新增 `aicasetest-redis`。

### Out of scope

- 缓存（设置/参数/分析结果）与任务队列（v5.3）。
- Milvus 语义层（v5.4）。
- 正式切换默认运行态（v5.5，当前仍默认内存）。

## 3. 功能详情

### 3.1 RuntimeStore 抽象

```text
RuntimeStore
├── 取消标志：setFlag / isFlagSet / clearFlag
├── 执行会话：putSession / getSession / removeSession
├── 心跳：putHeartbeat / getHeartbeat / removeHeartbeat
├── 登录防爆破：incrementLoginAttempts / getLoginAttempts / setLockUntil / getLockUntil / clearLogin
└── 并发配额：acquireProjectPermit / releaseProjectPermit
```

### 3.2 Redis key 设计

| 用途 | Key | 说明 |
|---|---|---|
| 生成取消 | `rt:flag:gen:cancel:{projectId}` | 字符串 1/0，24h TTL |
| 执行取消 | `rt:flag:exec:cancel:{executionId}` | 同上 |
| 浏览器会话 | `rt:session:{executionId}` | 24h TTL |
| 心跳 | `rt:heartbeat:{executionId}` | 毫秒时间戳 |
| 防爆破 | `rt:login:{username}` | Hash：attempts / lock_until |
| 并发配额 | `rt:sema:{projectId}` | Lua INCR/DECR 计数 |

### 3.3 降级策略

- `APP_REDIS_ENABLED=false`（默认）：使用 `MemoryRuntimeStore`，行为与 v4.2 一致。
- `APP_REDIS_ENABLED=true`：使用 `RedisRuntimeStore`；Redis 调用异常时单操作降级到内存，避免本地开发/临时抖动导致功能不可用。

## 4. 验收标准

1. `mvn compile` / `mvn test` 通过。
2. 默认内存模式全部原有流程可编译、可运行。
3. `APP_REDIS_ENABLED=true` 且 Redis 可用时，取消/心跳/防爆破/配额读写落在 Redis。
4. Redis 不可用时服务不崩，自动降级内存。
5. 前端 `npm run build` 成功。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| Redis 依赖导致本地开发失败 | 默认关闭，异常降级 |
| 取消标志多实例不一致 | RuntimeFlag 同时检查本地与存储层 |
| Lua 计数与内存计数混用 | Redis 失败才降级，单实例语义一致 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- runtime 包（RuntimeStore / Memory / Redis / Flag / Config）
- TestCaseService / ExecutionService / ProjectExecutionLimiter / LoginAttemptService 改造
- compose Redis 服务
