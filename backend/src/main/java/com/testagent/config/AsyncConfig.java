package com.testagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * v4.0: 异步线程池。
 * v4.2: 参数化（application.yml）+ 新增独立 executionExecutor（批量执行不再挤占分析池）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${app.executor.analysis.core:2}")
    private int analysisCore;

    @Value("${app.executor.analysis.max:4}")
    private int analysisMax;

    @Value("${app.executor.analysis.queue:20}")
    private int analysisQueue;

    @Value("${app.executor.generation.core:2}")
    private int generationCore;

    @Value("${app.executor.generation.max:4}")
    private int generationMax;

    @Value("${app.executor.generation.queue:20}")
    private int generationQueue;

    @Value("${app.executor.execution.core:2}")
    private int executionCore;

    @Value("${app.executor.execution.max:8}")
    private int executionMax;

    @Value("${app.executor.execution.queue:200}")
    private int executionQueue;

    @Bean(name = "analysisExecutor")
    public ThreadPoolTaskExecutor analysisExecutor() {
        return buildExecutor("analysis-", analysisCore, analysisMax, analysisQueue);
    }

    @Bean(name = "generationExecutor")
    public ThreadPoolTaskExecutor generationExecutor() {
        return buildExecutor("generation-", generationCore, generationMax, generationQueue);
    }

    @Bean(name = "executionExecutor")
    public ThreadPoolTaskExecutor executionExecutor() {
        return buildExecutor("execution-", executionCore, executionMax, executionQueue);
    }

    private ThreadPoolTaskExecutor buildExecutor(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
