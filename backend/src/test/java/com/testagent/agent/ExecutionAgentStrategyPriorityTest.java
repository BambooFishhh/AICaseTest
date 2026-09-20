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
 * v9.8: 点击策略 DOM 优先测试——步骤自带 uiSelector 时优先 dom_click，
 * 视觉定位坐标易漂移（litemall 足迹/收藏页勾选/全选/信息区域点击误导航商品详情），
 * 仅无选择器时回退 visual_click；LLM 即使返回 visual_click 也被硬约束改判 dom_click。
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
    void domSelectorPresentPrefersDomClickEvenWhenVisualFound() throws Exception {
        // 未配置 LLM 的默认策略：视觉已找到元素但步骤带 uiSelector → 必须 dom_click（防坐标漂移）
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        ExecutionAgent agent = agent(llmService);

        Map<String, Object> decision = invokeDefaultStrategy(agent, true,
                "{\"type\":\"ui_action\",\"action\":\"勾选第一条足迹的复选框\",\"target\":\"复选框\","
                        + "\"uiSelector\":{\"type\":\"css\",\"value\":\".fp-item .van-checkbox\"}}");

        assertEquals("dom_click", decision.get("strategy"),
                "v9.8: 有 DOM 选择器时优先 dom_click，杜绝视觉坐标漂移点错子元素");
        assertEquals(".fp-item .van-checkbox", decision.get("selectorValue"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void noSelectorButVisualFoundUsesVisualClick() throws Exception {
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        ExecutionAgent agent = agent(llmService);

        Map<String, Object> decision = invokeDefaultStrategy(agent, true,
                "{\"type\":\"ui_action\",\"action\":\"点击信息区域\",\"target\":\"足迹商品项\"}");

        assertEquals("visual_click", decision.get("strategy"),
                "无 DOM 选择器时回退视觉点击");
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
    void llmVisualClickOverriddenToDomClickWhenSelectorPresent() throws Exception {
        // LLM 决策返回 visual_click，但步骤带 uiSelector → 硬约束改判 dom_click
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

        assertEquals("dom_click", decision.get("strategy"),
                "v9.8: LLM 返回 visual_click 但有 DOM 选择器 → 改判 dom_click 防视觉漂移");
        assertEquals("text", decision.get("selectorType"));
        assertEquals("全选", decision.get("selectorValue"));
    }
}