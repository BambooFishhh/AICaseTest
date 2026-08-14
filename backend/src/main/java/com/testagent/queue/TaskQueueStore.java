package com.testagent.queue;

/**
 * v5.3: 任务队列存储抽象。Redis 开启时使用 Redis Set 计数，否则使用内存。
 */
public interface TaskQueueStore {

    void enqueue(String queue, String taskId);

    void markRunning(String queue, String taskId);

    void markDone(String queue, String taskId);

    long queuedCount(String queue);

    long runningCount(String queue);

    /**
     * vP2: 清空指定队列的 queued/running 状态，供服务重启后恢复使用。
     */
    void clearQueue(String queue);
}
