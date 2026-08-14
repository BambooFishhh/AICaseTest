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

    public void acquire(String projectId) {
        runtimeStore.acquireProjectPermit(projectId, maxConcurrent);
    }

    public void release(String projectId) {
        runtimeStore.releaseProjectPermit(projectId);
    }
}
