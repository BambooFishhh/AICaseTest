package com.testagent.agent;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.StateMachine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.6(A17): 状态机转换证据校验测试。
 * 业务背景：转换关系此前完全由 LLM 猜测——编造的 CREATED→CANCELLED 只要 code 合法就入库，
 * "状态转换覆盖率"建立在不验证的猜测之上。规则层证据（源码赋值点）是 ground truth。
 */
class StateMachineAgentEvidenceTest {

    private StateMachineAgent agent() {
        return new StateMachineAgent();
    }

    private StateMachine sm(String transitionsJson, double confidence) {
        StateMachine sm = new StateMachine();
        sm.setId("test-sm");
        sm.setName("OrderStatusStateMachine");
        sm.setStates("""
                [{"name":"Created","code":"CREATED"},{"name":"Paid","code":"PAID"},{"name":"Cancelled","code":"CANCELLED"}]
                """);
        sm.setTransitions(transitionsJson);
        sm.setConfidence(confidence);
        return sm;
    }

    @Test
    void evidenceMatchedTransitionIsVerified() {
        StateMachine sm = sm("""
                [{"from":"CREATED","to":"PAID","trigger":"pay"},{"from":"PAID","to":"CANCELLED","trigger":"refund"}]
                """, 0.8);
        List<Map<String, Object>> evidence = List.of(
                Map.of("field", "status", "from", "CREATED", "to", "PAID", "method", "payOrder", "file", "OrderService.java"),
                Map.of("field", "status", "from", "PAID", "to", "CANCELLED", "method", "refundOrder", "file", "OrderService.java"));

        agent().applyEvidence(List.of(sm), evidence);

        List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
        assertEquals(2, transitions.size());
        assertTrue(transitions.stream().allMatch(t -> Boolean.TRUE.equals(t.get("verified"))),
                "与证据匹配的转换应标 verified");
        assertFalse(transitions.stream().anyMatch(t -> t.containsKey("unverified")));
        assertEquals(0.8, sm.getConfidence(), "全部匹配时 confidence 不降");
    }

    @Test
    void fabricatedTransitionIsUnverifiedAndConfidenceDropped() {
        // LLM 编造 CREATED→CANCELLED（源码无此转换），证据只有 CREATED→PAID
        StateMachine sm = sm("""
                [{"from":"CREATED","to":"PAID","trigger":"pay"},{"from":"CREATED","to":"CANCELLED","trigger":"fake"}]
                """, 0.8);
        List<Map<String, Object>> evidence = List.of(
                Map.of("field", "status", "from", "CREATED", "to", "PAID", "method", "payOrder", "file", "OrderService.java"));

        agent().applyEvidence(List.of(sm), evidence);

        List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
        assertTrue(transitions.stream()
                .anyMatch(t -> "CANCELLED".equals(t.get("to")) && Boolean.TRUE.equals(t.get("unverified"))),
                "编造转换应标 unverified");
        assertTrue(transitions.stream()
                .anyMatch(t -> "PAID".equals(t.get("to")) && Boolean.TRUE.equals(t.get("verified"))));
        assertEquals(0.4, sm.getConfidence(), "存在未验证转换时 confidence 降至 0.4");
    }

    @Test
    void wildcardFromMatchesAnyTransitionTo() {
        // 证据 from="*"（无条件赋值，任意状态可达）匹配任意 to
        StateMachine sm = sm("""
                [{"from":"CREATED","to":"CANCELLED","trigger":"cancel"}]
                """, 0.8);
        List<Map<String, Object>> evidence = List.of(
                Map.of("field", "status", "from", "*", "to", "CANCELLED", "method", "cancelOrder", "file", "OrderService.java"));

        agent().applyEvidence(List.of(sm), evidence);

        assertTrue(JsonHelper.parseListMap(sm.getTransitions()).get(0).containsKey("verified"));
    }

    @Test
    void enumPrefixAndCaseAreNormalized() {
        // OrderStatus.PAID → PAID；大小写归一
        StateMachine sm = sm("""
                [{"from":"paid","to":"cancelled","trigger":"refund"}]
                """, 0.8);
        List<Map<String, Object>> evidence = List.of(
                Map.of("field", "status", "from", "OrderStatus.PAID", "to", "OrderStatus.CANCELLED",
                        "method", "refundOrder", "file", "OrderService.java"));

        agent().applyEvidence(List.of(sm), evidence);

        assertTrue(JsonHelper.parseListMap(sm.getTransitions()).get(0).containsKey("verified"),
                "枚举前缀与小写 code 应能匹配");
    }

    @Test
    void emptyEvidenceLeavesTransitionsUntouched() {
        // 无证据（无后端源码/无赋值点）→ 不加标记不降级："未校验"≠"校验失败"
        StateMachine sm = sm("""
                [{"from":"CREATED","to":"PAID","trigger":"pay"}]
                """, 0.8);

        agent().applyEvidence(List.of(sm), List.of());

        List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
        assertFalse(transitions.get(0).containsKey("verified"));
        assertFalse(transitions.get(0).containsKey("unverified"));
        assertEquals(0.8, sm.getConfidence());
    }

    @Test
    void nullArgumentsAreSafe() {
        StateMachine sm = sm("[]", 0.5);
        agent().applyEvidence(null, List.of());
        agent().applyEvidence(List.of(sm), null);
        assertEquals(0.5, sm.getConfidence());
    }

    @Test
    void ruleBasedEmptyTransitionsAreSkipped() {
        // rule_based 兜底 transitions 为空 → 跳过，无异常
        StateMachine sm = sm("[]", 0.5);
        agent().applyEvidence(List.of(sm), List.of(
                Map.of("field", "status", "from", "CREATED", "to", "PAID", "method", "payOrder", "file", "f")));
        assertEquals("[]", sm.getTransitions());
        assertEquals(0.5, sm.getConfidence());
    }
}
