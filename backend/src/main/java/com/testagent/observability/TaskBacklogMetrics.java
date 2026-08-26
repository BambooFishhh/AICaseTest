package com.testagent.observability;

import com.testagent.repository.AgentTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v8.8.2(10.5): 任务积压可观测——agent_task QUEUED/RUNNING 等状态计数进 Gauge。
 * 只读刷新，多实例并发执行无副作用（无需 ShedLock）；
 * RUNNING 超阈值告警由 Prometheus 规则承担（alerts.yml AICaseTestRunningBacklogHigh）。
 */
@Component
public class TaskBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(TaskBacklogMetrics.class);

    @Autowired
    private AgentTaskRepository agentTaskRepository;

    // v8.7.1: 指标门面——no-op 兜底
    private MetricsFacade metrics = new MetricsFacade();

    @Autowired(required = false)
    void setMetrics(MetricsFacade metrics) {
        this.metrics = metrics;
    }

    // 各状态计数缓存——Gauge 强引用载体，刷新时原位更新
    private final Map<String, AtomicLong> statusGauges = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void registerGauges() {
        for (String status : new String[]{"QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "NEEDS_REVIEW", "DLQ"}) {
            statusGauges.computeIfAbsent(status, s -> {
                AtomicLong ref = new AtomicLong(0);
                metrics.gaugeRaw("agent_task_" + s.toLowerCase(), ref);
                return ref;
            });
        }
    }

    @Scheduled(fixedDelayString = "${app.backlog.refresh-ms:30000}", initialDelayString = "${app.backlog.refresh-initial-ms:15000}")
    public void refresh() {
        try {
            for (Map.Entry<String, AtomicLong> entry : statusGauges.entrySet()) {
                entry.getValue().set(0);
            }
            for (Object[] row : agentTaskRepository.countGroupByStatus()) {
                String status = String.valueOf(row[0]);
                long count = ((Number) row[1]).longValue();
                statusGauges.computeIfAbsent(status, s -> {
                    AtomicLong ref = new AtomicLong(0);
                    metrics.gaugeRaw("agent_task_" + s.toLowerCase(), ref);
                    return ref;
                }).set(count);
            }
        } catch (Exception e) {
            log.warn("任务积压 Gauge 刷新失败: {}", e.getMessage());
        }
    }
}
