package com.testagent.observability;

import com.testagent.service.TaskQueueService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * vP3: 将任务队列 queued/running 暴露为 Prometheus Gauge。
 */
@Component
public class QueueMetrics {

    public QueueMetrics(MeterRegistry meterRegistry, TaskQueueService taskQueueService) {
        Gauge.builder("aicasetest.queue.queued", taskQueueService, TaskQueueService::queuedTotal)
                .description("Generation/execution queued tasks")
                .register(meterRegistry);
        Gauge.builder("aicasetest.queue.running", taskQueueService, TaskQueueService::runningTotal)
                .description("Generation/execution running tasks")
                .register(meterRegistry);
    }
}
