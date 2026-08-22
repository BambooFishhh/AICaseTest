package com.testagent.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v6.7: LLM provider 简易熔断。连续失败达到阈值后短时间放行前直接拒绝，
 * 成功即重置计数，避免模型故障风暴拖垮整个生成链路。
 * v7.3(L2): 按通道拆分状态——文本(Spring AI)与多模态(MCP llm_chat_with_image)互不连坐；
 * 不可重试错误(4xx 配置类)由调用方判定后不计入熔断计数。
 */
@Component
public class LlmCircuitBreaker {

    public static final String CHANNEL_TEXT = "text";
    public static final String CHANNEL_MULTIMODAL = "multimodal";

    private static class CircuitState {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long openedUntil = 0;
    }

    private final ConcurrentHashMap<String, CircuitState> channels = new ConcurrentHashMap<>();

    @Value("${llm.circuit.failure-threshold:5}")
    private int failureThreshold = 5;

    @Value("${llm.circuit.open-seconds:30}")
    private long openSeconds = 30;

    private CircuitState state(String channel) {
        String key = (channel == null || channel.isBlank()) ? CHANNEL_TEXT : channel;
        return channels.computeIfAbsent(key, k -> new CircuitState());
    }

    public boolean isOpen(String channel) {
        return System.currentTimeMillis() < state(channel).openedUntil;
    }

    public boolean allowRequest(String channel) {
        return !isOpen(channel);
    }

    public void onSuccess(String channel) {
        CircuitState s = state(channel);
        s.consecutiveFailures.set(0);
        s.openedUntil = 0;
    }

    public void onFailure(String channel) {
        CircuitState s = state(channel);
        if (s.consecutiveFailures.incrementAndGet() >= failureThreshold) {
            s.openedUntil = System.currentTimeMillis() + openSeconds * 1000;
        }
    }

    // v7.3(L2): 兼容旧签名（默认 text 通道）
    public boolean isOpen() {
        return isOpen(CHANNEL_TEXT);
    }

    public boolean allowRequest() {
        return allowRequest(CHANNEL_TEXT);
    }

    public void onSuccess() {
        onSuccess(CHANNEL_TEXT);
    }

    public void onFailure() {
        onFailure(CHANNEL_TEXT);
    }
}
