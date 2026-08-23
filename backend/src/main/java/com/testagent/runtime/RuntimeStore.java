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

    /**
     * v7.12(E15): 配额获取/释放携带 permitId（=executionId）。
     * Redis 实现为 ZSET 租约模型——member=permitId，按持有者精确释放（幂等），
     * 步骤心跳续租防长执行租约过期；计数器+TTL 模型在长执行下键过期导致超发。
     * 内存实现忽略 permitId（Semaphore 语义）。
     */
    void acquireProjectPermit(String projectId, int maxPermits, String permitId);

    /**
     * v7.9(E7): 带超时的项目并发配额获取。
     * 默认实现退化为无限等待（旧语义，内存实现外的调用方不感知变化）。
     * @return true=获得配额；false=超时未获得（调用方应将任务记失败而非永久阻塞）
     */
    default boolean tryAcquireProjectPermit(String projectId, int maxPermits, long timeoutMs, String permitId) {
        acquireProjectPermit(projectId, maxPermits, permitId);
        return true;
    }

    void releaseProjectPermit(String projectId, String permitId);

    /**
     * v7.12(E15): 续租项目并发配额（Redis 租约模型）。活跃执行在步骤心跳处调用，
     * 防止租约在长执行期间过期。内存实现为 no-op。实现内部吞异常——续租失败不得影响执行主流程。
     */
    default void renewProjectPermit(String projectId, String permitId) {
        // no-op
    }

    default RuntimeFlag newFlag(String key) {
        return new RuntimeFlag(key, this);
    }
}
