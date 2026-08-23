package com.testagent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;

/**
 * v5.2: Redis 运行态存储（多实例）。Redis 不可用时自动降级到内存，保证单机开发可用。
 */
public class RedisRuntimeStore implements RuntimeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRuntimeStore.class);
    private static final Duration TTL = Duration.ofHours(24);

    // v7.12(E15): ZSET 租约信号量——member=permitId(=executionId)，score=授予/续租时刻。
    // 取代计数器+EXPIRE(600) 模型：旧模型在长执行（>10min，Agent 模式常见）下键过期
    // → 计数清零 → 超发；且释放无持有者语义，重复释放/跨存储释放会偷走他任务槽位。
    // 租约 5 分钟 + 执行器步骤心跳续租：活跃执行租约不过期，JVM 崩溃后 5 分钟自愈。
    private static final long PERMIT_LEASE_MS = 5 * 60_000L;

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', tonumber(ARGV[2]) - tonumber(ARGV[3])) "
                    + "if redis.call('ZCARD', KEYS[1]) < tonumber(ARGV[1]) then "
                    + "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) + 60000) return 1 end return 0",
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            // 幂等：重复释放/释放未持有的 permitId 均为无害 no-op
            "return redis.call('ZREM', KEYS[1], ARGV[1])",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('ZSCORE', KEYS[1], ARGV[1]) then "
                    + "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) + 60000) return 1 end return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final MemoryRuntimeStore memoryFallback = new MemoryRuntimeStore();

    /**
     * v7.12(E15): acquire 降级内存时登记 permitId，release 按授予来源路由——
     * 修复双向漂移（旧实现：acquire 走内存、Redis 恢复后 release 扣减 Redis 偷槽位，
     * 且内存信号量永久泄漏）。
     */
    private final java.util.Set<String> memoryGrantedPermits = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public RedisRuntimeStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isFlagSet(String key) {
        try {
            return "1".equals(redis.opsForValue().get("rt:flag:" + key));
        } catch (Exception e) {
            return memoryFallback.isFlagSet(key);
        }
    }

    @Override
    public void setFlag(String key, boolean value) {
        try {
            String k = "rt:flag:" + key;
            if (value) {
                redis.opsForValue().set(k, "1", TTL);
            } else {
                redis.delete(k);
            }
        } catch (Exception e) {
            memoryFallback.setFlag(key, value);
        }
    }

    @Override
    public void clearFlag(String key) {
        try {
            redis.delete("rt:flag:" + key);
        } catch (Exception e) {
            memoryFallback.clearFlag(key);
        }
    }

    @Override
    public void putSession(String executionId, String sessionId) {
        try {
            redis.opsForValue().set("rt:session:" + executionId, sessionId, TTL);
        } catch (Exception e) {
            memoryFallback.putSession(executionId, sessionId);
        }
    }

    @Override
    public String getSession(String executionId) {
        try {
            return redis.opsForValue().get("rt:session:" + executionId);
        } catch (Exception e) {
            return memoryFallback.getSession(executionId);
        }
    }

    @Override
    public void removeSession(String executionId) {
        try {
            redis.delete("rt:session:" + executionId);
        } catch (Exception e) {
            memoryFallback.removeSession(executionId);
        }
    }

    @Override
    public void putHeartbeat(String executionId, long value) {
        try {
            redis.opsForValue().set("rt:heartbeat:" + executionId, String.valueOf(value), TTL);
        } catch (Exception e) {
            memoryFallback.putHeartbeat(executionId, value);
        }
    }

    @Override
    public long getHeartbeat(String executionId) {
        try {
            String v = redis.opsForValue().get("rt:heartbeat:" + executionId);
            return v == null ? -1L : Long.parseLong(v);
        } catch (Exception e) {
            return memoryFallback.getHeartbeat(executionId);
        }
    }

    @Override
    public void removeHeartbeat(String executionId) {
        try {
            redis.delete("rt:heartbeat:" + executionId);
        } catch (Exception e) {
            memoryFallback.removeHeartbeat(executionId);
        }
    }

    @Override
    public int incrementLoginAttempts(String username) {
        try {
            String key = "rt:login:" + username;
            Long v = redis.opsForHash().increment(key, "attempts", 1);
            redis.expire(key, TTL);
            return v == null ? 0 : v.intValue();
        } catch (Exception e) {
            log.warn("Redis login increment failed for {}, fallback to memory: {}", username, e.getMessage());
            return memoryFallback.incrementLoginAttempts(username);
        }
    }

    @Override
    public int getLoginAttempts(String username) {
        try {
            Object v = redis.opsForHash().get("rt:login:" + username, "attempts");
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return memoryFallback.getLoginAttempts(username);
        }
    }

    @Override
    public void resetLoginAttempts(String username) {
        clearLogin(username);
    }

    @Override
    public void setLockUntil(String username, long timestamp) {
        try {
            String key = "rt:login:" + username;
            redis.opsForHash().put(key, "lock_until", String.valueOf(timestamp));
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("Redis lock set failed for {}, fallback to memory: {}", username, e.getMessage());
            memoryFallback.setLockUntil(username, timestamp);
        }
    }

    @Override
    public long getLockUntil(String username) {
        try {
            Object v = redis.opsForHash().get("rt:login:" + username, "lock_until");
            return v == null ? -1L : Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return memoryFallback.getLockUntil(username);
        }
    }

    @Override
    public void clearLogin(String username) {
        try {
            redis.delete("rt:login:" + username);
        } catch (Exception e) {
            memoryFallback.clearLogin(username);
        }
    }

    @Override
    public void acquireProjectPermit(String projectId, int maxPermits, String permitId) {
        String key = "rt:sema:" + projectId;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (acquireLease(key, maxPermits, permitId)) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("执行调度被中断");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("执行调度被中断", e);
        } catch (Exception e) {
            log.warn("Redis semaphore unavailable, fallback to memory: {}", e.getMessage());
            memoryGrantedPermits.add(permitId);
            memoryFallback.acquireProjectPermit(projectId, maxPermits, permitId);
        }
    }

    /** v7.9(E7): 带超时的配额获取——自旋加 deadline，超时返回 false 而非永久阻塞 */
    @Override
    public boolean tryAcquireProjectPermit(String projectId, int maxPermits, long timeoutMs, String permitId) {
        String key = "rt:sema:" + projectId;
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (acquireLease(key, maxPermits, permitId)) {
                    return true;
                }
                if (System.currentTimeMillis() >= deadline) {
                    return false;
                }
                Thread.sleep(100);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Redis semaphore unavailable, fallback to memory: {}", e.getMessage());
            memoryGrantedPermits.add(permitId);
            return memoryFallback.tryAcquireProjectPermit(projectId, maxPermits, timeoutMs, permitId);
        }
    }

    @Override
    public void releaseProjectPermit(String projectId, String permitId) {
        // v7.12(E15): 按授予来源路由——内存授予还内存；Redis 授予走 ZREM（幂等）。
        // Redis 释放失败仅告警，依赖租约 ≤5 分钟自愈，不再错误降级扣减内存
        if (memoryGrantedPermits.remove(permitId)) {
            memoryFallback.releaseProjectPermit(projectId, permitId);
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, List.of("rt:sema:" + projectId), permitId);
        } catch (Exception e) {
            log.warn("Redis semaphore release failed (lease will expire): {}", e.getMessage());
        }
    }

    @Override
    public void renewProjectPermit(String projectId, String permitId) {
        if (memoryGrantedPermits.contains(permitId)) {
            return;   // 内存 Semaphore 无租约概念
        }
        try {
            long now = System.currentTimeMillis();
            redis.execute(RENEW_SCRIPT, List.of("rt:sema:" + projectId),
                    permitId, String.valueOf(now), String.valueOf(PERMIT_LEASE_MS));
        } catch (Exception e) {
            log.debug("Redis semaphore renew failed: {}", e.getMessage());
        }
    }

    private boolean acquireLease(String key, int maxPermits, String permitId) {
        long now = System.currentTimeMillis();
        Long ok = redis.execute(ACQUIRE_SCRIPT, List.of(key),
                String.valueOf(Math.max(1, maxPermits)),
                String.valueOf(now),
                String.valueOf(PERMIT_LEASE_MS),
                permitId);
        return ok != null && ok == 1L;
    }
}
