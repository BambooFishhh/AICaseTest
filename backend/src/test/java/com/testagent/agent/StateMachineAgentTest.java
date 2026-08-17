package com.testagent.agent;

import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.EnumInfo;
import com.testagent.analyzer.result.EnumValue;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.JsonHelper;
import com.testagent.entity.StateMachine;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StateMachineAgentTest {

    @Test
    void frontendEnhancementAppendsValidTransitionsAndMarksSources() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble()))
                .thenReturn(extractionJson(), enhancementJson());
        StateMachineAgent agent = new StateMachineAgent();
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "promptSkillLoader", new PromptSkillLoader());

        List<StateMachine> result = agent.extract(backendResult(), frontendResult());

        assertEquals(1, result.size());
        StateMachine sm = result.get(0);
        assertEquals("OrderStatusStateMachine", sm.getName());
        assertEquals(List.of("backend", "frontend", "llm"),
                JsonHelper.parseListString(sm.getSources()));

        List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
        assertEquals(3, transitions.size());
        assertTrue(transitions.stream()
                .anyMatch(t -> "POST /api/orders/{id}/pay".equals(t.get("endpoint"))));
        assertTrue(transitions.stream()
                .anyMatch(t -> "refund".equals(t.get("trigger"))));
        assertFalse(transitions.stream()
                .anyMatch(t -> "UNKNOWN".equals(t.get("to"))));
        verify(llmService, times(2)).chatWithAnalysis(anyString(), anyString(), anyDouble());
    }

    @Test
    void frontendEnhancementSkippedWhenNoEvidence() {
        LlmService llmService = mock(LlmService.class);
        when(llmService.chatWithAnalysis(anyString(), anyString(), anyDouble())).thenReturn(extractionJson());
        StateMachineAgent agent = new StateMachineAgent();
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "promptSkillLoader", new PromptSkillLoader());

        FrontendResult emptyFrontend = FrontendResult.builder()
                .pageFlows(List.of())
                .apiCalls(List.of())
                .componentStates(List.of())
                .build();
        List<StateMachine> result = agent.extract(backendResult(), emptyFrontend);

        assertEquals(1, result.size());
        StateMachine sm = result.get(0);
        assertEquals(1, JsonHelper.parseListMap(sm.getTransitions()).size());
        assertEquals(List.of("llm"), JsonHelper.parseListString(sm.getSources()));
        verify(llmService).chatWithAnalysis(anyString(), anyString(), anyDouble());
    }

    private BackendResult backendResult() {
        EnumInfo orderStatus = EnumInfo.builder()
                .name("OrderStatus")
                .type("enum")
                .values(List.of(
                        EnumValue.builder().name("CREATED").value("CREATED").build(),
                        EnumValue.builder().name("PAID").value("PAID").build(),
                        EnumValue.builder().name("CANCELLED").value("CANCELLED").build()))
                .file("OrderStatus.java")
                .build();
        return BackendResult.builder()
                .enums(List.of(orderStatus))
                .endpoints(List.of())
                .entities(List.of())
                .businessRules(List.of())
                .status("ok")
                .build();
    }

    private FrontendResult frontendResult() {
        return FrontendResult.builder()
                .pageFlows(List.of(Map.of(
                        "from", "/orders",
                        "to", "/orders/pay",
                        "trigger", "click pay button")))
                .apiCalls(List.of(Map.of(
                        "method", "POST",
                        "url", "/api/orders/{id}/pay")))
                .componentStates(List.of(Map.of(
                        "component", "PayDialog",
                        "type", "submit",
                        "stateVar", "paying")))
                .build();
    }

    private String extractionJson() {
        return """
                [{
                  "name": "OrderStatusStateMachine",
                  "description": "order status flow",
                  "states": [
                    {"name": "Created", "code": "CREATED", "type": "initial", "description": "created"},
                    {"name": "Paid", "code": "PAID", "type": "normal", "description": "paid"},
                    {"name": "Cancelled", "code": "CANCELLED", "type": "final", "description": "cancelled"}
                  ],
                  "transitions": [
                    {"from": "CREATED", "to": "PAID", "trigger": "pay"}
                  ]
                }]
                """;
    }

    private String enhancementJson() {
        return """
                [{
                  "name": "OrderStatusStateMachine",
                  "transitions": [
                    {"from": "CREATED", "to": "PAID", "trigger": "click pay", "endpoint": "POST /api/orders/{id}/pay", "order": 1},
                    {"from": "PAID", "to": "CANCELLED", "trigger": "refund", "endpoint": "POST /api/orders/{id}/refund", "order": 2},
                    {"from": "CREATED", "to": "UNKNOWN", "trigger": "fake"}
                  ]
                }]
                """;
    }
}
