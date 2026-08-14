package com.testagent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ObservabilityFilterTest {

    @Test
    void generatesTraceIdAndRecordsSloMetrics() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityFilter filter = new ObservabilityFilter(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNotNull(response.getHeader("X-Trace-Id"));
        assertEquals(1.0, registry.get("aicasetest.http.requests").counter().count(), 0.001);
    }

    @Test
    void propagatesIncomingTraceId() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityFilter filter = new ObservabilityFilter(registry);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        request.addHeader("X-Trace-Id", "custom-trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("custom-trace-123", response.getHeader("X-Trace-Id"));
    }
}
