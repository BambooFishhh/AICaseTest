package com.testagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.ExecutionStep;
import com.testagent.runtime.RuntimeStore;
import com.testagent.service.LlmService;
import com.testagent.service.McpBridgeService;
import com.testagent.skill.PlaywrightRecordSkill;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.6(E5): Agent 模式步骤类型分流测试。
 * 业务背景：state_assert/api_call 此前掉进"找元素→截图→定位→点击"流水线，
 * 验证步骤可能随机点中页面元素（描述撞上删除按钮即生产事故）。
 */
class ExecutionAgentStepTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExecutionAgent agent(PlaywrightRecordSkill playwrightSkill) {
        ExecutionAgent agent = new ExecutionAgent();
        ReflectionTestUtils.setField(agent, "playwrightSkill", playwrightSkill);
        ReflectionTestUtils.setField(agent, "mcpBridgeService", mock(McpBridgeService.class));
        LlmService llmService = mock(LlmService.class);
        when(llmService.isConfigured()).thenReturn(false);
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "runtimeStore", mock(RuntimeStore.class));
        return agent;
    }

    @Test
    void stateAssertRunsAssertionWithoutClickPipeline() throws Exception {
        // 验证步骤 → 读页面状态 + DOM 文本断言，绝不触发元素定位/点击
        PlaywrightRecordSkill playwrightSkill = mock(PlaywrightRecordSkill.class);
        when(playwrightSkill.getPageStatus(anyString())).thenReturn(Map.of(
                "url", "/order/list", "title", "订单列表", "textSnippet", "订单列表 删除成功"));
        when(playwrightSkill.takeScreenshot(anyString())).thenReturn("after.png");
        ExecutionAgent agent = agent(playwrightSkill);

        ExecutionStep step = agent.executeStep("session-1", objectMapper.readTree("""
                {"type":"state_assert","action":"验证删除成功提示","target":"","expected":"页面出现删除成功提示"}
                """), "用例: 删除订单", 2, "exec-1");

        assertEquals("passed", step.getResult(), "textSnippet 含预期关键词 → 断言通过");
        assertEquals("assert", step.getStrategy());
        assertTrue(step.getCoordinates().contains("/order/list"));
        verify(playwrightSkill, never()).visualClick(anyString(), anyInt(), anyInt());
        verify(playwrightSkill, never()).domClick(anyString(), anyString(), anyString());
    }

    @Test
    void stateAssertFailureIsHonest() throws Exception {
        PlaywrightRecordSkill playwrightSkill = mock(PlaywrightRecordSkill.class);
        when(playwrightSkill.getPageStatus(anyString())).thenReturn(Map.of(
                "url", "/order/list", "title", "订单列表", "textSnippet", "订单列表 提交失败"));
        when(playwrightSkill.takeScreenshot(anyString())).thenReturn("after.png");
        ExecutionAgent agent = agent(playwrightSkill);

        ExecutionStep step = agent.executeStep("session-1", objectMapper.readTree("""
                {"type":"state_assert","action":"验证删除成功提示","target":"","expected":"页面出现删除成功提示"}
                """), "用例: 删除订单", 2, "exec-1");

        assertEquals("failed", step.getResult(), "textSnippet 不含预期关键词 → 断言失败（不再假通过）");
        assertTrue(step.getError().contains("删除成功"), "失败信息应含期望文本");
    }

    @Test
    void stateAssertPageStatusFailureIsSkippedNotFailed() throws Exception {
        PlaywrightRecordSkill playwrightSkill = mock(PlaywrightRecordSkill.class);
        when(playwrightSkill.getPageStatus(anyString()))
                .thenThrow(new RuntimeException("MCP 调用失败"));
        ExecutionAgent agent = agent(playwrightSkill);

        ExecutionStep step = agent.executeStep("session-1", objectMapper.readTree("""
                {"type":"state_assert","action":"验证订单状态","target":"","expected":"页面出现删除成功提示"}
                """), "用例: 删除订单", 2, "exec-1");

        assertEquals("skipped", step.getResult(), "页面状态读取失败 → 未验证，不误报失败");
        assertTrue(step.getError().contains("页面状态读取失败"));
    }

    @Test
    void apiCallIsExplicitlySkipped() throws Exception {
        PlaywrightRecordSkill playwrightSkill = mock(PlaywrightRecordSkill.class);
        ExecutionAgent agent = agent(playwrightSkill);

        ExecutionStep step = agent.executeStep("session-1", objectMapper.readTree("""
                {"type":"api_call","action":"调用订单接口","target":"POST /api/orders","expected":"接口返回 400"}
                """), "用例: 订单异常流", 1, "exec-1");

        assertEquals("skipped", step.getResult());
        assertEquals("Agent 模式暂不支持 API 调用步骤", step.getError());
        verify(playwrightSkill, never()).getPageStatus(anyString());
        verify(playwrightSkill, never()).visualClick(anyString(), anyInt(), anyInt());
    }
}
