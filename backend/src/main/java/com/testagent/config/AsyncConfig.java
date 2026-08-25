package com.testagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * v4.0: 异步线程池。
 * v4.2: 参数化（application.yml）+ 新增独立 executionExecutor（批量执行不再挤占分析池）。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

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

    @Value("${app.executor.semantic.core:1}")
    private int semanticCore;

    @Value("${app.executor.semantic.max:2}")
    private int semanticMax;

    @Value("${app.executor.semantic.queue:100}")
    private int semanticQueue;

    @Value("${app.executor.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Value("${app.executor.await-termination-seconds:30}")
    private int awaitTerminationSeconds;

    @Bean(name = "analysisExecutor")
    public ThreadPoolTaskExecutor analysisExecutor() {
        return buildExecutor("analysis-", analysisCore, analysisMax, analysisQueue, true);
    }

    @Bean(name = "generationExecutor")
    public ThreadPoolTaskExecutor generationExecutor() {
        return buildExecutor("generation-", generationCore, generationMax, generationQueue, true);
    }

    @Bean(name = "executionExecutor")
    public ThreadPoolTaskExecutor executionExecutor() {
        return buildExecutor("execution-", executionCore, executionMax, executionQueue, false);
    }

    @Bean(name = "semanticExecutor")
    public ThreadPoolTaskExecutor semanticExecutor() {
        return buildExecutor("semantic-", semanticCore, semanticMax, semanticQueue, false);
    }

    private ThreadPoolTaskExecutor buildExecutor(String prefix, int core, int max, int queue, boolean rejectFast) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        // v8.4fix: 长耗时池（分析/生成）队列满时快速拒绝，由调用方转为"服务繁忙"返回；
        // 旧的统一 CallerRunsPolicy 会让分钟级 AI 任务回落 HTTP 线程执行，阻塞全部普通接口。
        // 短任务池（执行/语义索引）保留 CallerRuns 降低失败率。
        if (rejectFast) {
            executor.setRejectedExecutionHandler((r, ex) -> {
                log.error("线程池 {} 已达上限（队列满），拒绝新任务，请稍后重试", prefix);
                throw new java.util.concurrent.RejectedExecutionException("线程池 " + prefix + " 已满，请稍后重试");
            });
        } else {
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        }
        executor.setKeepAliveSeconds(keepAliveSeconds);
        // vP2/vP5: 优雅停机时等待已提交任务完成，超时可配置
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
