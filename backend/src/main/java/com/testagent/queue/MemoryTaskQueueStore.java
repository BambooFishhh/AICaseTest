package com.testagent.queue;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v5.3: 内存任务队列实现（单实例默认）。
 */
public class MemoryTaskQueueStore implements TaskQueueStore {

    private final Map<String, Set<String>> queued = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> running = new ConcurrentHashMap<>();

    @Override
    public void enqueue(String queue, String taskId) {
        queued.computeIfAbsent(queue, k -> ConcurrentHashMap.newKeySet()).add(taskId);
    }

    @Override
    public void markRunning(String queue, String taskId) {
        Set<String> q = queued.get(queue);
        if (q != null) q.remove(taskId);
        running.computeIfAbsent(queue, k -> ConcurrentHashMap.newKeySet()).add(taskId);
    }

    @Override
    public void markDone(String queue, String taskId) {
        Set<String> q = queued.get(queue);
        if (q != null) q.remove(taskId);
        Set<String> r = running.get(queue);
        if (r != null) r.remove(taskId);
    }

    @Override
    public long queuedCount(String queue) {
        Set<String> q = queued.get(queue);
        return q == null ? 0 : q.size();
    }

    @Override
    public long runningCount(String queue) {
        Set<String> r = running.get(queue);
        return r == null ? 0 : r.size();
    }

    @Override
    public void clearQueue(String queue) {
        Set<String> q = queued.get(queue);
        if (q != null) q.clear();
        Set<String> r = running.get(queue);
        if (r != null) r.clear();
    }
}
