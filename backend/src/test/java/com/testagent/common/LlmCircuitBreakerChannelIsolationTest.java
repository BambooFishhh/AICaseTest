package com.testagent.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.8.1(10.3): 通道隔离——embedding 熔断不拖垮 text；text:fallback 与 text 相互独立
class LlmCircuitBreakerChannelIsolationTest {

    @Test
    void embeddingOpenDoesNotBlockText() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();
        for (int i = 0; i < 10; i++) {
            breaker.onFailure("embedding");
        }

        assertTrue(breaker.isOpen("embedding"));
        // text 主通道与 fallback 通道不受影响
        assertTrue(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_TEXT));
        assertTrue(breaker.allowRequest("text:fallback"));
        assertTrue(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_MULTIMODAL));
    }

    @Test
    void textFallbackChannelIndependentFromText() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();
        for (int i = 0; i < 10; i++) {
            breaker.onFailure(LlmCircuitBreaker.CHANNEL_TEXT);
        }

        assertTrue(breaker.isOpen(LlmCircuitBreaker.CHANNEL_TEXT));
        assertFalse(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_TEXT));
        // 主通道熔断时降级通道仍可用（降级路由的前提）
        assertTrue(breaker.allowRequest("text:fallback"));
    }

    private void ReflectionTestUtilsOpen() {
        // 阈值默认 5：占位说明——直接连续 onFailure 即可打开，无需注入配置
    }
}
