# 后端技术评审 vP2：高可用与容灾

> 版本 vP2，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 任务队列恢复

`TaskQueueStore` 新增 `clearQueue(queue)`：

```java
void clearQueue(String queue);
```

内存实现清空 `queued/running` Map；Redis 实现删除 `rt:queue:{queue}:queued/running`。

### 1.2 启动状态恢复

`DataInitializer` 增加 vP2 恢复段：

```java
taskQueueService.recoverStaleTasks();
// analyzing/generating → 有旧用例 completed，否则 failed + errorMessage
```

### 1.3 优雅停机

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

`AsyncConfig.buildExecutor` 增加 `waitForTasksToCompleteOnShutdown(true)` 与 `awaitTerminationSeconds(30)`。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| queue/TaskQueueStore.java | 新增 clearQueue |
| queue/MemoryTaskQueueStore.java | clearQueue 实现 |
| queue/RedisTaskQueueStore.java | clearQueue 实现 |
| service/TaskQueueService.java | recoverStaleTasks |
| config/DataInitializer.java | 队列清理 + 项目状态恢复 |
| config/AsyncConfig.java | 线程池优雅停机 |
| resources/application.yml | graceful shutdown 配置 |
| test/queue/MemoryTaskQueueStoreTest.java | clearQueue 用例 |

## 3. API 契约变化

无。

## 4. 向后兼容性

- `clearQueue` 为接口新增方法，两个实现同步更新。
- 启动恢复为增量行为，不改变正常路径。
- 优雅停机对开发环境无影响。

## 5. 测试验证方案

- `mvn -Dtest=MemoryTaskQueueStoreTest test`
- `mvn compile`
- `docker compose config`
