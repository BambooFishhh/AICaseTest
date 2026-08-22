package com.testagent.service;

import com.testagent.entity.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * v6.6: 高可用任务调度器，统一承担租约恢复、TTL 过期与 QUEUED 分发。
 */
@Component
public class HaTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(HaTaskScheduler.class);

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private TaskRetryDispatcher taskRetryDispatcher;

    @Autowired
    private TaskEventStreamService taskEventStreamService;

    @Scheduled(cron = "${app.ha.recovery-cron:0 */5 * * * *}")
    public void recoverStaleTasks() {
        agentTaskService.recoverStaleTasks();
    }

    @Scheduled(cron = "${app.ha.ttl-cron:0 */10 * * * *}")
    public void expireTasksByTtl() {
        agentTaskService.expireTasksByTtl();
    }

    /**
     * 排队任务兜底分发：HTTP 入队失败或重启遗留的 QUEUED 任务由此重新驱动。
     */
    @Scheduled(fixedDelayString = "${app.ha.dispatch-delay-ms:15000}")
    public void dispatchQueuedTasks() {
        for (String taskId : taskEventStreamService.consume(20)) {
            dispatchQuietly(taskId);
        }
        List<AgentTask> queued = agentTaskService.findQueued();
        for (AgentTask task : queued) {
            dispatchQuietly(task.getId());
        }
    }

    private void dispatchQuietly(String taskId) {
        try {
            taskRetryDispatcher.dispatchQueued(taskId);
        } catch (Exception e) {
            log.warn("Failed to dispatch queued task {}: {}", taskId, e.getMessage());
        }
    }
}
