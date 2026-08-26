package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.observability.MetricsFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * v8.9.2(12.2): LLM 入口实例级并发限流——防止多链路聚合并发击穿供应商 RPM
 * （CR §9.3 C2）。通道信号量模型：
 *   text       = 主通道同步 chat（默认 6，与生成池 max 对齐）
 *   stream     = 主通道流式（默认 6）
 *   embedding  = embedding 调用（默认 4，覆盖 dedup 并行与 RAG 检索聚合）
 *   fallback-text = 降级供应商独立配额（默认 4，通常低于主配额）
 * 多实例部署时聚合口径 = 实例配额 × 实例数 ≤ 供应商 RPM（运维约束，见水平扩容指南）。
 */
@Component
public class LlmRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LlmRateLimiter.class);

    public static final String CHANNEL_TEXT = "text";
    public static final String CHANNEL_STREAM = "stream";
    public static final String CHANNEL_EMBEDDING = "embedding";
    public static final String CHANNEL_FALLBACK_TEXT = "fallback-text";

    @Value("${llm.rate-limit.text-concurrency:6}")
    private int textConcurrency = 6;

    @Value("${llm.rate-limit.stream-concurrency:6}")
    private int streamConcurrency = 6;

    @Value("${llm.rate-limit.embedding-concurrency:4}")
    private int embeddingConcurrency = 4;

    @Value("${llm.rate-limit.fallback-text-concurrency:4}")
    private int fallbackTextConcurrency = 4;

    // 等待上限：超时抛 50300 可重试异常（上游繁忙语义）
    @Value("${llm.rate-limit.wait-timeout-ms:120000}")
    private long waitTimeoutMs = 120000;

    // v8.7.1: 指标门面——no-op 兜底
    private MetricsFacade metrics = new MetricsFacade();

    @Autowired(required = false)
    void setMetrics(MetricsFacade metrics) {
        this.metrics = metrics;
    }

    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        // v8.7.1 口径：启动零值预注册
        for (String ch : new String[]{CHANNEL_TEXT, CHANNEL_STREAM, CHANNEL_EMBEDDING, CHANNEL_FALLBACK_TEXT}) {
            metrics.registerCounter("llm_rate_limit_wait_total", "channel", ch);
            metrics.registerCounter("llm_rate_limit_rejected_total", "channel", ch);
        }
    }

    // 包级私有：测试钉值
    void setChannelConcurrency(String channel, int permits) {
        semaphores.put(channel, new Semaphore(permits));
    }

    /**
     * 在通道并发配额内执行 body；等待超 waitTimeoutMs 抛 50300（可重试）。
     * release 保证于 finally。
     */
    public <T> T execute(String channel, Supplier<T> body) {
        Semaphore semaphore = semaphoreFor(channel);
        long start = System.currentTimeMillis();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(waitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50300, "LLM 并发配额等待被中断", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!acquired) {
            metrics.increment("llm_rate_limit_rejected_total", "channel", channel);
            log.warn("LLM 限流拒绝 (channel={}, 等待超过 {}ms)", channel, waitTimeoutMs);
            throw new BusinessException(50300,
                    "LLM 并发配额已满(" + channel + ")，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        long waited = System.currentTimeMillis() - start;
        if (waited > 5000) {
            // 等待超阈值进指标——配额吃紧的先行信号（告警可基于 rate）
            metrics.increment("llm_rate_limit_wait_total", "channel", channel);
        }
        try {
            return body.get();
        } finally {
            semaphore.release();
        }
    }

    private Semaphore semaphoreFor(String channel) {
        return semaphores.computeIfAbsent(channel, k -> new Semaphore(permitsFor(channel)));
    }

    private int permitsFor(String channel) {
        return switch (channel) {
            case CHANNEL_TEXT -> textConcurrency;
            case CHANNEL_STREAM -> streamConcurrency;
            case CHANNEL_EMBEDDING -> embeddingConcurrency;
            case CHANNEL_FALLBACK_TEXT -> fallbackTextConcurrency;
            default -> textConcurrency;
        };
    }
}
