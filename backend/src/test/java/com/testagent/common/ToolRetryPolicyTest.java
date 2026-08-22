package com.testagent.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRetryPolicyTest {

    @Test
    void classifiesIdempotentTools() {
        assertTrue(ToolRetryPolicy.isIdempotentTool("playwright", "browser_take_screenshot"));
        assertTrue(ToolRetryPolicy.isIdempotentTool("playwright", "browser_get_page_status"));
        assertTrue(ToolRetryPolicy.isIdempotentTool("playwright", "browser_scroll"));
        assertTrue(ToolRetryPolicy.isIdempotentTool("tools", "semantic_search"));
        assertFalse(ToolRetryPolicy.isIdempotentTool("playwright", "browser_dom_click"));
        assertFalse(ToolRetryPolicy.isIdempotentTool("playwright", "browser_fill"));
        assertFalse(ToolRetryPolicy.isIdempotentTool("playwright", "browser_visual_click"));
    }

    @Test
    void classifiesRetryableErrors() {
        assertTrue(ToolRetryPolicy.isRetryable(new SocketTimeoutException("timeout")));
        assertTrue(ToolRetryPolicy.isRetryable(new IOException("MCP stdout 已关闭")));
        assertTrue(ToolRetryPolicy.isRetryable(new RuntimeException("wrapped",
                new IOException("Connection reset"))));
        assertFalse(ToolRetryPolicy.isRetryable(new RuntimeException("tool returned error")));
    }
}
