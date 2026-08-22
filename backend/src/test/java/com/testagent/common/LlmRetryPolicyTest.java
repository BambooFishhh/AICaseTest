package com.testagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmRetryPolicyTest {

    @Test
    void classifiesRetryableErrors() {
        assertTrue(LlmRetryPolicy.isRetryable(new RuntimeException("Read timed out")));
        assertTrue(LlmRetryPolicy.isRetryable(new SocketTimeoutException("timeout")));
        assertTrue(LlmRetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS)));
        assertTrue(LlmRetryPolicy.isRetryable(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)));
        assertTrue(LlmRetryPolicy.isRetryable(new RuntimeException("wrapped",
                new IOException("Connection reset"))));
    }

    @Test
    void doesNotRetryNonRetryableErrors() {
        assertFalse(LlmRetryPolicy.isRetryable(new RuntimeException("model refused")));
        assertFalse(LlmRetryPolicy.isRetryable(new HttpClientErrorException(HttpStatus.BAD_REQUEST)));
        assertFalse(LlmRetryPolicy.isRetryable(new GenerationCancelledException("cancel")));
        assertFalse(LlmRetryPolicy.isRetryable(new RuntimeException(
                "Upstream request failed: [400] invalid_argument")));
    }
}
