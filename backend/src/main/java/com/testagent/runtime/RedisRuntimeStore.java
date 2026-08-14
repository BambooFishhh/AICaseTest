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

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local current = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "if current < tonumber(ARGV[1]) then "
                    + "redis.call('INCR', KEYS[1]) redis.call('EXPIRE', KEYS[1], 600) return 1 end return 0",
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "local current = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "if current > 0 then redis.call('DECR', KEYS[1]) end return current",
            Long.class);

    private final StringRedisTemplate redis;
    private final MemoryRuntimeStore memoryFallback = new MemoryRuntimeStore();

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
    public void acquireProjectPermit(String projectId, int maxPermits) {
        String key = "rt:sema:" + projectId;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Long ok = redis.execute(ACQUIRE_SCRIPT, List.of(key), String.valueOf(Math.max(1, maxPermits)));
                if (ok != null && ok == 1L) {
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
            memoryFallback.acquireProjectPermit(projectId, maxPermits);
        }
    }

    @Override
    public void releaseProjectPermit(String projectId) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of("rt:sema:" + projectId));
        } catch (Exception e) {
            log.warn("Redis semaphore release failed, fallback to memory: {}", e.getMessage());
            memoryFallback.releaseProjectPermit(projectId);
        }
    }
}
