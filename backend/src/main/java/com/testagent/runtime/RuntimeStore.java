package com.testagent.runtime;

/**
 * v5.2: 运行态存储抽象。
 * 支持内存实现（单实例默认）与 Redis 实现（多实例），覆盖取消标志、执行会话/心跳、
 * 登录防爆破计数与项目级并发配额。
 */
public interface RuntimeStore {

    boolean isFlagSet(String key);

    void setFlag(String key, boolean value);

    void clearFlag(String key);

    void putSession(String executionId, String sessionId);

    String getSession(String executionId);

    void removeSession(String executionId);

    void putHeartbeat(String executionId, long value);

    long getHeartbeat(String executionId);

    void removeHeartbeat(String executionId);

    int incrementLoginAttempts(String username);

    int getLoginAttempts(String username);

    void resetLoginAttempts(String username);

    void setLockUntil(String username, long timestamp);

    long getLockUntil(String username);

    void clearLogin(String username);

    void acquireProjectPermit(String projectId, int maxPermits);

    void releaseProjectPermit(String projectId);

    default RuntimeFlag newFlag(String key) {
        return new RuntimeFlag(key, this);
    }
}
