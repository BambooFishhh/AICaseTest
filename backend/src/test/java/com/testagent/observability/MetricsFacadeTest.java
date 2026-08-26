package com.testagent.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.7.1(9.5.1): MetricsFacade 计数/计时/Gauge 行为
class MetricsFacadeTest {

    private MetricsFacade facade;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        facade = new MetricsFacade();
        ReflectionTestUtils.setField(facade, "registry", registry);
    }

    @Test
    void counterWithTagsAccumulates() {
        facade.increment("gen_parse_skipped_total");
        facade.increment("gen_parse_skipped_total");
        facade.increment("milvus_op_failed_total", "op", "delete");

        assertEquals(2.0, registry.get("gen_parse_skipped_total").counter().count());
        assertEquals(1.0, registry.get("milvus_op_failed_total").tag("op", "delete").counter().count());
    }

    @Test
    void timerRecordsDuration() {
        facade.recordMillis("rag_latency_seconds", 250);

        assertEquals(1, registry.get("rag_latency_seconds").timer().count());
        assertTrue(registry.get("rag_latency_seconds").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 250);
    }

    @Test
    void gaugeUpdatesValueWithoutReRegistration() {
        facade.setGauge("vector_pending_ops_size", 3);
        assertEquals(3.0, registry.get("vector_pending_ops_size").gauge().value());

        // 同名再设置只更新值
        AtomicLong before = new AtomicLong(0);
        registry.get("vector_pending_ops_size").gauge();
        facade.setGauge("vector_pending_ops_size", 7);
        assertEquals(7.0, registry.get("vector_pending_ops_size").gauge().value());
    }

    @Test
    void noOpWithoutRegistry() {
        MetricsFacade bare = new MetricsFacade();
        // 无 registry：不抛异常即通过
        bare.increment("gen_rounds_total", "result", "completed");
        bare.recordMillis("rag_latency_seconds", 10);
        bare.setGauge("vector_pending_ops_size", 1);
    }
}
