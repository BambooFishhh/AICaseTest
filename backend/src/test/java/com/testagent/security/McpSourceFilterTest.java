package com.testagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.9.1(12.4): 客户端 IP 解析与回环判定（trust-proxy 双态 + XFF 首跳）
class McpSourceFilterTest {

    @Test
    void trustProxyOffUsesRemoteAddrIgnoringXff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.1.1.1");
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.1.1.2");

        assertEquals("10.1.1.1", McpSourceFilter.resolveClientIp(req, false));
    }

    @Test
    void trustProxyOnTakesFirstHopOfXff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.9, 10.1.1.2");

        assertEquals("203.0.113.9", McpSourceFilter.resolveClientIp(req, true));
    }

    @Test
    void trustProxyOnFallsBackToRemoteAddrWhenXffBlank() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("192.168.5.5");
        req.addHeader("X-Forwarded-For", " ");

        assertEquals("192.168.5.5", McpSourceFilter.resolveClientIp(req, true));
    }

    @Test
    void loopbackFormsDetected() {
        assertTrue(McpSourceFilter.isLoopbackIp("127.0.0.1"));
        assertTrue(McpSourceFilter.isLoopbackIp("127.8.8.8"));
        assertTrue(McpSourceFilter.isLoopbackIp("::1"));
        assertTrue(McpSourceFilter.isLoopbackIp("0:0:0:0:0:0:0:1"));
        assertFalse(McpSourceFilter.isLoopbackIp("10.0.0.5"));
        assertFalse(McpSourceFilter.isLoopbackIp(null));
    }
}
