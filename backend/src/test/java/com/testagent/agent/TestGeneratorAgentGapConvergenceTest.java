package com.testagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.11(G21): 补测收敛判定单测——componentIds/dependencyIds 不再计入循环条件。
 * 背景：缺口补测循环以 hasRemainingGaps 为续跑条件，而组件/依赖缺口
 * （componentIds/dependencyIds）生成侧不会随补测轮次收敛，导致无限循环
 * 烧 token 直到手数上限。v7.11 将收敛检查收窄到真正可收敛的四类缺口。
 */
class TestGeneratorAgentGapConvergenceTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private Boolean hasRemainingGaps(Map<String, Object> gaps) {
        return ReflectionTestUtils.invokeMethod(agent, "hasRemainingGaps", gaps);
    }

    @Test
    void nullGapsMeansConverged() {
        assertFalse(hasRemainingGaps(null));
    }

    @Test
    void componentOrDependencyGapsAloneDoNotTriggerLoop() {
        // G21 核心回归：组件/依赖缺口不随补测收敛，不能作为续跑条件
        assertFalse(hasRemainingGaps(Map.of(
                "componentIds", List.of("comp-1", "comp-2"),
                "dependencyIds", List.of("dep-1"))));
        assertFalse(hasRemainingGaps(Map.of("componentIds", List.of("comp-1"))));
        assertFalse(hasRemainingGaps(Map.of("dependencyIds", List.of("dep-1"))));
    }

    @Test
    void requirementGapStillTriggersLoop() {
        assertTrue(hasRemainingGaps(Map.of(
                "requirementIds", List.of("req-1"),
                "componentIds", List.of("comp-1"))));
    }

    @Test
    void eachConvergableGapTypeAloneTriggersLoop() {
        assertTrue(hasRemainingGaps(Map.of("transitionIds", List.of("tr-1"))));
        assertTrue(hasRemainingGaps(Map.of("endpointIds", List.of("ep-1"))));
        assertTrue(hasRemainingGaps(Map.of("ruleIds", List.of("rule-1"))));
    }

    @Test
    void emptyConvergableGapsMeansConverged() {
        assertFalse(hasRemainingGaps(Map.of(
                "requirementIds", List.of(),
                "transitionIds", List.of(),
                "endpointIds", List.of(),
                "ruleIds", List.of(),
                "componentIds", List.of("comp-1"))));
    }
}
