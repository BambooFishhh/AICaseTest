package com.testagent.service;

import com.testagent.runtime.RuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * v4.2: 项目级执行并发配额——同一项目同时执行数不超过上限，超出排队等待。
 * v5.2: 配额状态迁至 RuntimeStore（Redis 支持多实例，内存支持单实例）。
 */
@Service
public class ProjectExecutionLimiter {

    private final int maxConcurrent;

    @Autowired
    private RuntimeStore runtimeStore;

    public ProjectExecutionLimiter(
            @Value("${app.executor.project-execution-max:3}") int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    /**
     * v7.12(E15): acquire/release 携带 permitId（=executionId）——Redis 租约模型按持有者
     * 精确释放（幂等），取代无持有者语义的计数增减。
     */
    public void acquire(String projectId, String permitId) {
        runtimeStore.acquireProjectPermit(projectId, maxConcurrent, permitId);
    }

    /**
     * v7.9(E7): 带超时的配额获取——超时返回 false（调用方将任务记失败），不再无限阻塞。
     */
    public boolean tryAcquire(String projectId, long timeoutMs, String permitId) {
        return runtimeStore.tryAcquireProjectPermit(projectId, maxConcurrent, timeoutMs, permitId);
    }

    public void release(String projectId, String permitId) {
        runtimeStore.releaseProjectPermit(projectId, permitId);
    }

    /**
     * v7.12(E15): 续租配额（Redis 租约模型）。执行器在步骤心跳处调用，防止长执行租约过期。
     * 内存实现为 no-op，实现内部吞异常。
     */
    public void renew(String projectId, String permitId) {
        runtimeStore.renewProjectPermit(projectId, permitId);
    }
}
