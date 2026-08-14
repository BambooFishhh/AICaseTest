package com.testagent.queue;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * v5.3: Redis 任务队列实现。queued/running 使用 Set 存储任务 ID，支持多实例计数。
 */
public class RedisTaskQueueStore implements TaskQueueStore {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "rt:queue:";

    private final StringRedisTemplate redis;
    private final MemoryTaskQueueStore memoryFallback = new MemoryTaskQueueStore();

    public RedisTaskQueueStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void enqueue(String queue, String taskId) {
        try {
            String key = PREFIX + queue + ":queued";
            redis.opsForSet().add(key, taskId);
            redis.expire(key, TTL);
        } catch (Exception e) {
            memoryFallback.enqueue(queue, taskId);
        }
    }

    @Override
    public void markRunning(String queue, String taskId) {
        try {
            String q = PREFIX + queue + ":queued";
            String r = PREFIX + queue + ":running";
            redis.opsForSet().remove(q, taskId);
            redis.opsForSet().add(r, taskId);
            redis.expire(r, TTL);
        } catch (Exception e) {
            memoryFallback.markRunning(queue, taskId);
        }
    }

    @Override
    public void markDone(String queue, String taskId) {
        try {
            redis.opsForSet().remove(PREFIX + queue + ":queued", taskId);
            redis.opsForSet().remove(PREFIX + queue + ":running", taskId);
        } catch (Exception e) {
            memoryFallback.markDone(queue, taskId);
        }
    }

    @Override
    public long queuedCount(String queue) {
        try {
            Long n = redis.opsForSet().size(PREFIX + queue + ":queued");
            return n == null ? 0 : n;
        } catch (Exception e) {
            return memoryFallback.queuedCount(queue);
        }
    }

    @Override
    public long runningCount(String queue) {
        try {
            Long n = redis.opsForSet().size(PREFIX + queue + ":running");
            return n == null ? 0 : n;
        } catch (Exception e) {
            return memoryFallback.runningCount(queue);
        }
    }
}
