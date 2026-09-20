package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.dto.LocateResult;
import com.testagent.service.LlmService;
import com.testagent.service.McpBridgeService;
import com.testagent.runtime.RuntimeStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v13.22: 点击策略**视觉优先**测试——MCP 视觉定位命中即 visual_click，
 * 未命中才回落 DOM 选择器。取代 v9.8 的 DOM 优先。
 *
 * <p>视觉漂移的兜底不落在本层：改由步骤 6 的降级链承担——点击未生效时
 * {@code askLlmForFallback} 判降级 dom_click（该函数只产出 dom_click/skip，
 * LLM 未配置/返空/异常时一律默认 dom_click）。故本层不再有
 * "带选择器就强制改判 DOM" 的硬约束，否则新策略会被它完全抵消。
 */
class ExecutionAgentStrategyPriorityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutionAgent agent(LlmService llmService) {
        ExecutionAgent agent = new ExecutionAgent();
        ReflectionTestUtils.setField(agent, "playwrightSkill", mock(com.testagent.skill.PlaywrightRecordSkill.class));
        ReflectionTestUtils.setField(agent, "mcpBridgeService", mock(McpBridgeService.class));
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "runtimeStore", mock(RuntimeStore.class));
        return agent;
    }

    private Map<String, Object> invokeDefaultStrategy(ExecutionAgent agent, boolean found, String stepJson) throws Exception {
        JsonNode step = objectMapper.readTree(stepJson);
        LocateResult result = locateResult(found);
        return invokeStrategy(agent, "defaultStrategy", step, result, null);
    }

    private LocateResult locateResult(boolean found) {
        return LocateResult.builder()
                .found(found)
                .clickX(100)
                .clickY(200)
                .confidence(0.9)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeStrategy(ExecutionAgent agent, String method,
                                               JsonNode step, LocateResult result, String action) {
        if (method.equals("defaultStrategy")) {
            return (Map<String, Object>) ReflectionTestUtils.invokeMethod(agent, method, result, step);
        }
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(agent, method, action, "target", step, result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void visualFoundPrefersVisualClickEvenWhenSelectorPresent() throws Exception {
        // 视觉已命中 → 视觉优先；即使步骤带 uiSelector 也不再改判 DOM
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        ExecutionAgent agent = agent(llmService);

        Map<String, Object> decision = invokeDefaultStrategy(agent, true,
                "{\"type\":\"ui_action\",\"action\":\"勾选第一条足迹的复选框\",\"target\":\"复选框\","
                        + "\"uiSelector\":{\"type\":\"css\",\"value\":\".fp-item .van-checkbox\"}}");

        assertEquals("visual_click", decision.get("strategy"),
                "v13.22: 视觉定位命中时优先 visual_click（DOM 选择器存在也不改判）");
        assertEquals(Integer.valueOf(100), decision.get("x"));
        assertEquals(Integer.valueOf(200), decision.get("y"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void visualNotFoundButSelectorPresentUsesDomClick() throws Exception {
        // 视觉未命中 → 回落 DOM 选择器
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        ExecutionAgent agent = agent(llmService);

        Map<String, Object> decision = invokeDefaultStrategy(agent, false,
                "{\"type\":\"ui_action\",\"action\":\"勾选第一条足迹的复选框\",\"target\":\"复选框\","
                        + "\"uiSelector\":{\"type\":\"css\",\"value\":\".fp-item .van-checkbox\"}}");

        assertEquals("dom_click", decision.get("strategy"),
                "视觉未命中时应回落 dom_click");
        assertEquals(".fp-item .van-checkbox", decision.get("selectorValue"));
        assertEquals("css", decision.get("selectorType"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void neitherFoundNorSelectorFallsBackToSkip() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        ExecutionAgent agent = agent(llmService);

        Map<String, Object> decision = invokeDefaultStrategy(agent, false,
                "{\"type\":\"ui_action\",\"action\":\"点击元素\",\"target\":\"未知\"}");

        assertEquals("skip", decision.get("strategy"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void llmVisualClickIsNotOverriddenToDomClick() throws Exception {
        // v13.22: 原 v9.8 硬约束已移除——LLM 的 visual_click 决策不再被改判 dom_click。
        // 这条用例正是防回退的哨兵：若有人把硬约束加回来，新策略会被静默抵消。
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(true);
        Map<String, Object> llmDecision = new LinkedHashMap<>();
        llmDecision.put("strategy", "visual_click");
        llmDecision.put("x", 10);
        llmDecision.put("y", 20);
        when(llmService.chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(llmDecision);
        ExecutionAgent agent = agent(llmService);

        JsonNode step = objectMapper.readTree(
                "{\"type\":\"ui_action\",\"action\":\"点击【全选】复选框\",\"target\":\"全选复选框\","
                        + "\"uiSelector\":{\"type\":\"text\",\"value\":\"全选\"}}");
        LocateResult result = locateResult(true);
        Map<String, Object> decision = invokeStrategy(agent, "askLlmForStrategy", step, result, "点击【全选】复选框");

        assertEquals("visual_click", decision.get("strategy"),
                "v13.22: LLM 返回 visual_click 不再被硬约束改判为 dom_click");
        assertEquals(Integer.valueOf(10), decision.get("x"));
        assertEquals(Integer.valueOf(20), decision.get("y"));
    }
}
