package com.testagent.agent;

import com.testagent.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v7.9(E6): 生效判断两级化验证。
 * 改动点：本地三指纹（URL/title/textSnippet）任一变化 → 直接判生效不调 LLM
 * （旧实现无论指纹是否变化都调 LLM，而 LLM 输入与本地比较完全相同，纯冗余）；
 * 指纹完全相同才调 LLM 终审，且 prompt 明示"快照无变化"事实。
 */
class ExecutionAgentEffectiveCheckTest {

    private Map<String, String> status(String url, String title, String snippet) {
        return Map.of("url", url, "title", title, "textSnippet", snippet);
    }

    @SuppressWarnings("unchecked")
    private boolean invoke(LlmService llm, Map<String, String> before, Map<String, String> after) {
        ExecutionAgent agent = new ExecutionAgent();
        ReflectionTestUtils.setField(agent, "llmService", llm);
        return (Boolean) ReflectionTestUtils.invokeMethod(agent, "askLlmIfEffective",
                before, after, "点击提交按钮");
    }

    @Test
    void fingerprintChangedSkipsLlmCall() {
        LlmService llm = mock(LlmService.class);
        when(llm.isConfigured()).thenReturn(true);
        Map<String, String> before = status("/order", "订单页", "订单列表 空");
        Map<String, String> after = status("/order", "订单页", "订单列表 下单成功 订单号1001");

        assertTrue(invoke(llm, before, after), "指纹已变化（文本快照不同）→ 本地证据充分直接判生效");
        verify(llm, never()).chatJson(anyString(), anyString(), anyDouble());
    }

    @Test
    void urlChangedSkipsLlmCall() {
        LlmService llm = mock(LlmService.class);
        when(llm.isConfigured()).thenReturn(true);

        assertTrue(invoke(llm, status("/login", "登录", "表单"), status("/home", "首页", "欢迎")));
        verify(llm, never()).chatJson(anyString(), anyString(), anyDouble());
    }

    @Test
    void fingerprintUnchangedCallsLlmWithNoChangeFact() {
        LlmService llm = mock(LlmService.class);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.chatJson(anyString(), anyString(), anyDouble()))
                .thenReturn(Map.of("effective", false, "reason", "无变化"));
        Map<String, String> same = status("/order", "订单页", "订单列表");

        assertFalse(invoke(llm, same, same), "指纹相同 → 交由 LLM 终审（此处 LLM 判未生效）");
        verify(llm, times(1)).chatJson(anyString(), contains("快照无变化"), anyDouble());
    }

    @Test
    void llmUnconfiguredFallsBackToFingerprint() {
        LlmService llm = mock(LlmService.class);
        when(llm.isConfigured()).thenReturn(false);

        assertTrue(invoke(llm, status("/login", "登录", "表单"), status("/home", "首页", "欢迎")),
                "无 LLM 时维持 v7.3 三指纹兜底");
        assertFalse(invoke(llm, status("/order", "订单页", "同"), status("/order", "订单页", "同")));
        verify(llm, never()).chatJson(anyString(), anyString(), anyDouble());
    }

    @Test
    void llmExceptionFallsBackToFingerprintCompare() {
        LlmService llm = mock(LlmService.class);
        when(llm.isConfigured()).thenReturn(true);
        when(llm.chatJson(anyString(), anyString(), anyDouble())).thenThrow(new RuntimeException("timeout"));
        Map<String, String> same = status("/order", "订单页", "同");

        assertFalse(invoke(llm, same, same), "LLM 异常 → 回退指纹比较（相同→未生效，与旧兜底一致）");
    }

    @Test
    void stepIdIs16HexChars() {
        // v7.9(E9): 步骤 ID 从 8 位加长到 16 位（64bit），消除碰撞静默覆盖
        for (int i = 0; i < 200; i++) {
            String id = ExecutionAgent.newStepId();
            org.junit.jupiter.api.Assertions.assertEquals(16, id.length(), "步骤 ID 应为 16 位: " + id);
            org.junit.jupiter.api.Assertions.assertTrue(id.matches("[0-9a-f]{16}"), "应为十六进制: " + id);
        }
    }
}
