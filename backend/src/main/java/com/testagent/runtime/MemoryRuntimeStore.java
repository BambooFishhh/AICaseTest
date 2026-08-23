package com.testagent.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v5.2: 内存运行态存储（单实例默认实现，行为与 v4.2 一致）。
 */
public class MemoryRuntimeStore implements RuntimeStore {

    private final Map<String, Boolean> flags = new ConcurrentHashMap<>();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    @Override
    public boolean isFlagSet(String key) {
        return Boolean.TRUE.equals(flags.get(key));
    }

    @Override
    public void setFlag(String key, boolean value) {
        flags.put(key, value);
    }

    @Override
    public void clearFlag(String key) {
        flags.remove(key);
    }

    @Override
    public void putSession(String executionId, String sessionId) {
        sessions.put(executionId, sessionId);
    }

    @Override
    public String getSession(String executionId) {
        return sessions.get(executionId);
    }

    @Override
    public void removeSession(String executionId) {
        sessions.remove(executionId);
    }

    @Override
    public void putHeartbeat(String executionId, long value) {
        heartbeats.put(executionId, value);
    }

    @Override
    public long getHeartbeat(String executionId) {
        Long v = heartbeats.get(executionId);
        return v == null ? -1L : v;
    }

    @Override
    public void removeHeartbeat(String executionId) {
        heartbeats.remove(executionId);
    }

    @Override
    public int incrementLoginAttempts(String username) {
        return loginAttempts.computeIfAbsent(username, k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public int getLoginAttempts(String username) {
        AtomicInteger v = loginAttempts.get(username);
        return v == null ? 0 : v.get();
    }

    @Override
    public void resetLoginAttempts(String username) {
        loginAttempts.remove(username);
    }

    @Override
    public void setLockUntil(String username, long timestamp) {
        lockUntil.put(username, timestamp);
    }

    @Override
    public long getLockUntil(String username) {
        Long v = lockUntil.get(username);
        return v == null ? -1L : v;
    }

    @Override
    public void clearLogin(String username) {
        loginAttempts.remove(username);
        lockUntil.remove(username);
    }

    // v7.12(E15): permitId 仅 Redis 租约模型需要——内存 Semaphore 按获取/释放配对即可
    @Override
    public void acquireProjectPermit(String projectId, int maxPermits, String permitId) {
        try {
            semaphore(projectId, maxPermits).acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("执行调度被中断", e);
        }
    }

    /** v7.9(E7): 带超时的配额获取——超时返回 false 而非永久阻塞 */
    @Override
    public boolean tryAcquireProjectPermit(String projectId, int maxPermits, long timeoutMs, String permitId) {
        try {
            return semaphore(projectId, maxPermits).tryAcquire(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void releaseProjectPermit(String projectId, String permitId) {
        semaphores.computeIfAbsent(projectId, k -> new Semaphore(1)).release();
    }

    private Semaphore semaphore(String projectId, int maxPermits) {
        return semaphores.computeIfAbsent(projectId, k -> new Semaphore(Math.max(1, maxPermits)));
    }
}
