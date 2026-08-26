package com.testagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.7.1(9.5.3): 契约违规计数 llm_schema_violation_total{agent}
class LlmSchemaValidatorViolationMetricTest {

    private LlmSchemaValidator validator;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        validator = new LlmSchemaValidator();
        validator.setMode("observe");
        registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        com.testagent.observability.MetricsFacade facade = new com.testagent.observability.MetricsFacade();
        ReflectionTestUtils.setField(facade, "registry", registry);
        ReflectionTestUtils.setField(validator, "metrics", facade);
    }

    @Test
    void violationIncrementsCounterWithSchemaTag() {
        assertTrue(validator.validateStructured("[{}]", "test-cases", "unit-test"));

        assertEquals(1.0, registry.get("llm_schema_violation_total")
                .tag("agent", "test-cases").counter().count());
    }

    @Test
    void validOutputDoesNotIncrement() {
        String json = "[{\"title\":\"t\",\"steps\":[\"s\"],\"priority\":\"P0\",\"type\":\"positive\"}]";
        assertTrue(validator.validateStructured(json, "test-cases", "unit-test"));

        assertFalse(registry.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals("llm_schema_violation_total")));
    }

    @Test
    void enforceModeAlsoCountsBeforeBlocking() {
        validator.setMode("enforce");
        assertFalse(validator.validateStructured("[{}]", "test-cases", "unit-test"));

        assertEquals(1.0, registry.get("llm_schema_violation_total")
                .tag("agent", "test-cases").counter().count());
    }
}
