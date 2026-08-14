# 后端技术评审 vP3：可观测与告警

> 版本 vP3，一旦确定尽量不要轻易改动。

## 1. 变更点

### 1.1 ObservabilityFilter

```java
@Component
@Order(-50)
public class ObservabilityFilter extends OncePerRequestFilter {
    // 设置/透传 X-Trace-Id，写 access log，上报 aicasetest.http.requests 指标
}
```

### 1.2 队列指标

```java
Gauge.builder("aicasetest.queue.queued", service, TaskQueueService::queuedTotal)
        .register(meterRegistry);
Gauge.builder("aicasetest.queue.running", service, TaskQueueService::runningTotal)
        .register(meterRegistry);
```

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| observability/ObservabilityFilter.java | 新增：traceId/access log/SLO |
| observability/QueueMetrics.java | 新增：队列 Gauge |
| service/TaskQueueService.java | 新增 queuedTotal/runningTotal |
| config/WebConfig.java | CORS 暴露 X-Trace-Id |
| test/observability/ObservabilityFilterTest.java | 新增 2 个测试 |

## 3. API 契约变化

- 无新增/删除端点。
- 所有响应新增 `X-Trace-Id` 响应头，CORS 暴露该头。

## 4. 向后兼容性

- 新增响应头不影响既有客户端。
- 未配置 Prometheus 时 Micrometer 仍可在内存/简单注册表工作。
- 队列指标仅在应用内注册，不改变队列行为。

## 5. 测试验证方案

- `ObservabilityFilterTest`：traceId 生成/透传 + 指标计数。
- `mvn compile`。
- `docker compose config`。
