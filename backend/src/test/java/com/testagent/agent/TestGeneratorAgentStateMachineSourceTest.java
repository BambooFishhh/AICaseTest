package com.testagent.agent;

import com.testagent.entity.StateMachine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v7.4(A20): 状态机来源派生——从 sources JSON 派生 rule/llm，不加数据库列。
 * rule 来源（规则兜底，transitions 恒空）生成侧禁止虚构转换，测试信任度分支。
 */
class TestGeneratorAgentStateMachineSourceTest {

    @Test
    void ruleBasedOnlyDerivesRule() {
        StateMachine sm = new StateMachine();
        sm.setSources("[\"rule_based\"]");
        assertEquals("rule", TestGeneratorAgent.stateMachineSource(sm));
    }

    @Test
    void llmContainingSourcesDeriveLlm() {
        StateMachine llmOnly = new StateMachine();
        llmOnly.setSources("[\"llm\"]");
        assertEquals("llm", TestGeneratorAgent.stateMachineSource(llmOnly));

        StateMachine mixed = new StateMachine();
        mixed.setSources("[\"rule_based\",\"llm\"]");
        assertEquals("llm", TestGeneratorAgent.stateMachineSource(mixed));
    }

    @Test
    void missingSourcesDeriveLlm() {
        assertEquals("llm", TestGeneratorAgent.stateMachineSource(null));

        StateMachine noSources = new StateMachine();
        noSources.setSources(null);
        assertEquals("llm", TestGeneratorAgent.stateMachineSource(noSources));

        StateMachine emptySources = new StateMachine();
        emptySources.setSources("[]");
        assertEquals("llm", TestGeneratorAgent.stateMachineSource(emptySources));
    }
}
