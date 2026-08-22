package com.testagent.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCircuitBreakerTest {

    @Test
    void opensAfterFailureThreshold() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        assertTrue(breaker.allowRequest());
        for (int i = 0; i < 5; i++) {
            breaker.onFailure();
        }

        assertFalse(breaker.allowRequest());
    }

    @Test
    void successResetsFailures() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker();

        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        for (int i = 0; i < 4; i++) {
            breaker.onFailure();
        }

        assertTrue(breaker.allowRequest());
    }
}
