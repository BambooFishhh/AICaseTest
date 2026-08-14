package com.testagent.service;

import com.testagent.queue.TaskQueueStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v5.3: 任务队列服务。维护生成/执行队列状态并输出统计。
 */
@Service
public class TaskQueueService {

    public static final String GENERATION_QUEUE = "generation";
    public static final String EXECUTION_QUEUE = "execution";

    @Autowired
    private TaskQueueStore taskQueueStore;

    public void enqueue(String queue, String taskId) {
        taskQueueStore.enqueue(queue, taskId);
    }

    public void markRunning(String queue, String taskId) {
        taskQueueStore.markRunning(queue, taskId);
    }

    public void markDone(String queue, String taskId) {
        taskQueueStore.markDone(queue, taskId);
    }

    /**
     * vP2: 服务重启后清理残留队列状态，防止统计与配额被旧实例污染。
     */
    public void recoverStaleTasks() {
        taskQueueStore.clearQueue(GENERATION_QUEUE);
        taskQueueStore.clearQueue(EXECUTION_QUEUE);
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generation", queueStats(GENERATION_QUEUE));
        result.put("execution", queueStats(EXECUTION_QUEUE));
        return result;
    }

    private Map<String, Long> queueStats(String queue) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("queued", taskQueueStore.queuedCount(queue));
        m.put("running", taskQueueStore.runningCount(queue));
        return m;
    }
}
