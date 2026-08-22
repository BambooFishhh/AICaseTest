package com.testagent.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.3(L2): 熔断器通道隔离验证——多模态故障不连坐文本通道。
 */
class LlmCircuitBreakerChannelTest {

    @Test
    void multimodalFailuresDoNotAffectTextChannel() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        for (int i = 0; i < 5; i++) {
            breaker.onFailure(LlmCircuitBreaker.CHANNEL_MULTIMODAL);
        }

        assertFalse(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_MULTIMODAL), "多模态通道应已熔断");
        assertTrue(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_TEXT), "文本通道不应被多模态故障连坐");
    }

    @Test
    void textFailuresDoNotAffectMultimodalChannel() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        for (int i = 0; i < 5; i++) {
            breaker.onFailure(LlmCircuitBreaker.CHANNEL_TEXT);
        }

        assertFalse(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_TEXT));
        assertTrue(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_MULTIMODAL));
    }

    @Test
    void successResetsOnlyItsOwnChannel() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        breaker.onFailure(LlmCircuitBreaker.CHANNEL_TEXT);
        breaker.onFailure(LlmCircuitBreaker.CHANNEL_TEXT);
        breaker.onFailure(LlmCircuitBreaker.CHANNEL_MULTIMODAL);
        breaker.onSuccess(LlmCircuitBreaker.CHANNEL_TEXT);

        for (int i = 0; i < 4; i++) {
            breaker.onFailure(LlmCircuitBreaker.CHANNEL_TEXT);
        }
        assertTrue(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_TEXT), "text 成功后计数应归零");

        // multimodal 已积累 1 次失败，再失败 4 次应熔断
        for (int i = 0; i < 4; i++) {
            breaker.onFailure(LlmCircuitBreaker.CHANNEL_MULTIMODAL);
        }
        assertFalse(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_MULTIMODAL),
                "multimodal 计数不应被 text 通道的 onSuccess 重置");
    }

    @Test
    void nullChannelDefaultsToText() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        for (int i = 0; i < 5; i++) {
            breaker.onFailure(null);
        }

        assertFalse(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_TEXT), "null 通道应默认计入 text");
        assertTrue(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_MULTIMODAL));
    }

    @Test
    void legacyNoArgMethodsUseTextChannel() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        for (int i = 0; i < 5; i++) {
            breaker.onFailure();
        }

        assertFalse(breaker.allowRequest(), "旧无参方法默认 text 通道");
        assertFalse(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_TEXT));
        assertTrue(breaker.allowRequest(LlmCircuitBreaker.CHANNEL_MULTIMODAL));
    }
}
