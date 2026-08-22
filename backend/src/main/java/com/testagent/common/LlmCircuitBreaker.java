package com.testagent.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * v6.7: LLM provider 简易熔断。连续失败达到阈值后短时间放行前直接拒绝，
 * 成功即重置计数，避免模型故障风暴拖垮整个生成链路。
 */
@Component
public class LlmCircuitBreaker {

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedUntil = 0;

    @Value("${llm.circuit.failure-threshold:5}")
    private int failureThreshold = 5;

    @Value("${llm.circuit.open-seconds:30}")
    private long openSeconds = 30;

    public boolean isOpen() {
        return System.currentTimeMillis() < openedUntil;
    }

    public boolean allowRequest() {
        return !isOpen();
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        openedUntil = 0;
    }

    public void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedUntil = System.currentTimeMillis() + openSeconds * 1000;
        }
    }
}
