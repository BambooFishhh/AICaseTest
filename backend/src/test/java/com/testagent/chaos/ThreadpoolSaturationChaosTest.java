package com.testagent.chaos;

import com.testagent.observability.MetricsFacade;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v8.8.2(10.6): 混沌演练③——线程池打满。
 * 快速拒绝 handler 触发 RejectedExecutionException（调用方转 503"服务繁忙"+ 状态回滚），
 * 并计入 executor_rejected_total{pool}。@Tag("chaos") 不阻塞日常构建。
 */
@Tag("chaos")
class ThreadpoolSaturationChaosTest {

    @Test
    void saturatedFastRejectPoolThrowsAndCounts() throws Exception {
        MetricsFacade facade = new MetricsFacade();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        org.springframework.test.util.ReflectionTestUtils.setField(facade, "registry", registry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("chaos-");
        executor.setRejectedExecutionHandler((r, ex) -> {
            facade.increment("executor_rejected_total", "pool", "analysis");
            throw new RejectedExecutionException("线程池 analysis 已满");
        });
        executor.initialize();

        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        // 占满 worker + 队列
        executor.execute(() -> {
            running.countDown();
            try {
                blocker.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS));
        executor.execute(() -> { });

        // 第三个任务必被拒绝
        org.junit.jupiter.api.Assertions.assertThrows(TaskRejectedException.class,
                () -> executor.execute(() -> { }));

        blocker.countDown();
        executor.shutdown();

        assertEquals(1.0, registry.get("executor_rejected_total").tag("pool", "analysis").counter().count());
    }

    private static void assertEquals(double expected, double actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, 0.0001);
    }
}
