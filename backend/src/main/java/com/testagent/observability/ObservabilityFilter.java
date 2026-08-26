package com.testagent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * vP3: 可观测过滤器。为每个请求生成/透传 traceId，写 access log，
 * 并上报 SLO 指标（请求数、耗时直方图，按 method/status 分桶）。
 */
@Component
@Order(-50)
public class ObservabilityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityFilter.class);
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final int MAX_TRACE_LENGTH = 64;

    private final MeterRegistry meterRegistry;
    // v8.9.2(12.6/C7): 热路径 meter 缓存——按 method|status 复用，避免每请求 Builder+registry 查找
    private final java.util.Map<String, Counter> sloCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Timer> sloTimers = new java.util.concurrent.ConcurrentHashMap<>();

    public ObservabilityFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank() || traceId.length() > MAX_TRACE_LENGTH) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put("trace_id", traceId);
        response.setHeader(TRACE_HEADER, traceId);

        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            String method = request.getMethod() == null ? "-" : request.getMethod();
            String uri = request.getRequestURI() == null ? "-" : request.getRequestURI();
            String status = String.valueOf(response.getStatus());
            recordSlo(method, status, durationMs);
            if (uri.startsWith("/actuator") || "/api/health".equals(uri)) {
                log.debug("access method={} uri={} status={} durationMs={} traceId={}",
                        method, uri, status, durationMs, traceId);
            } else {
                log.info("access method={} uri={} status={} durationMs={} traceId={}",
                        method, uri, status, durationMs, traceId);
            }
            MDC.remove("trace_id");
        }
    }

    private void recordSlo(String method, String status, long durationMs) {
        try {
            String key = method + "|" + status;
            sloCounters.computeIfAbsent(key, k -> Counter.builder("aicasetest.http.requests")
                            .tags("method", method, "status", status).register(meterRegistry))
                    .increment();
            sloTimers.computeIfAbsent(key, k -> Timer.builder("aicasetest.http.requests.duration")
                            .tags("method", method, "status", status)
                            .publishPercentileHistogram().register(meterRegistry))
                    .record(Duration.ofMillis(durationMs));
        } catch (Exception e) {
            log.debug("SLO metric recording failed: {}", e.getMessage());
        }
    }
}
