package com.testagent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * v4.2: 项目级执行并发配额——同一项目同时执行数不超过上限，超出排队等待。
 */
@Service
public class ProjectExecutionLimiter {

    private final int maxConcurrent;
    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public ProjectExecutionLimiter(
            @Value("${app.executor.project-execution-max:3}") int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    public void acquire(String projectId) {
        try {
            semaphore(projectId).acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("执行调度被中断", e);
        }
    }

    public void release(String projectId) {
        semaphore(projectId).release();
    }

    private Semaphore semaphore(String projectId) {
        return semaphores.computeIfAbsent(projectId, k -> new Semaphore(maxConcurrent));
    }
}
