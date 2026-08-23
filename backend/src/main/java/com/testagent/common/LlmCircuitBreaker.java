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
 * v7.12(L15): 补半开态——开启期过后不再全量放行（LLM 单调用 40-120s，几十个 doomed
 * 请求会在首个失败重新打开熔断之前全部涌入），改为单探测租约：仅一个请求试探
 * provider 是否恢复，成功才全量恢复；探测租约超时自愈（探测走"不计数错误"路径
 * 无回调时不会卡死半开）。
 */
@Component
public class LlmCircuitBreaker {

    public static final String CHANNEL_TEXT = "text";
    public static final String CHANNEL_MULTIMODAL = "multimodal";

    private static class CircuitState {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long openedUntil = 0;
        // v7.12(L15): 半开探测租约到期时刻；0 = 无在途探测。
        volatile long probeLeaseUntil = 0;
    }

    private final ConcurrentHashMap<String, CircuitState> channels = new ConcurrentHashMap<>();

    @Value("${llm.circuit.failure-threshold:5}")
    private int failureThreshold = 5;

    @Value("${llm.circuit.open-seconds:30}")
    private long openSeconds = 30;

    /** v7.12(L15): 探测租约时长，覆盖最长 LLM 调用；探测无回调时到期自动放行下个探测 */
    @Value("${llm.circuit.probe-lease-seconds:120}")
    private long probeLeaseSeconds = 120;

    private CircuitState state(String channel) {
        String key = (channel == null || channel.isBlank()) ? CHANNEL_TEXT : channel;
        return channels.computeIfAbsent(key, k -> new CircuitState());
    }

    public boolean isOpen(String channel) {
        return System.currentTimeMillis() < state(channel).openedUntil;
    }

    /**
     * v7.12(L15): OPEN 全拒；开启期过后进入 HALF_OPEN——仅当无在途探测时
     * 放行当前调用者并授予探测租约，其余照旧快速失败；CLOSED 全放行。
     * synchronized 状态对象：LLM 调用 40s+，锁开销可忽略，消除租约授予竞态。
     */
    public boolean allowRequest(String channel) {
        CircuitState s = state(channel);
        synchronized (s) {
            long now = System.currentTimeMillis();
            if (now < s.openedUntil) {
                return false;               // OPEN：全拒
            }
            if (s.openedUntil > 0) {        // HALF_OPEN：单探测租约
                if (now < s.probeLeaseUntil) {
                    return false;           // 已有探测在途
                }
                s.probeLeaseUntil = now + probeLeaseSeconds * 1000;
                return true;                // 当前调用者成为探测器
            }
            return true;                    // CLOSED
        }
    }

    public void onSuccess(String channel) {
        CircuitState s = state(channel);
        synchronized (s) {
            s.consecutiveFailures.set(0);
            s.openedUntil = 0;
            s.probeLeaseUntil = 0;
        }
    }

    public void onFailure(String channel) {
        CircuitState s = state(channel);
        synchronized (s) {
            if (s.consecutiveFailures.incrementAndGet() >= failureThreshold) {
                s.openedUntil = System.currentTimeMillis() + openSeconds * 1000;
                s.probeLeaseUntil = 0;      // 探测失败重新打开：清租约，下轮开启期过后可再探测
            }
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
