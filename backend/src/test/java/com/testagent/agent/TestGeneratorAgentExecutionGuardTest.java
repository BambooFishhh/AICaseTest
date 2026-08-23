package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.15: 执行可信治理——流式推送跨轮去重 + uiSelector 白名单清洗单测。
 */
class TestGeneratorAgentExecutionGuardTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    // ==================== 跨轮推送去重 ====================

    private TestCase caseOf(String title) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        return tc;
    }

    @Test
    void sameTitlePushedOnce_caseInsensitiveAndTrimmed() {
        List<TestCase> pushed = new ArrayList<>();
        TestGeneratorAgent.CaseCallback cb = TestGeneratorAgent.wrapPushDedup(pushed::add);

        cb.onCase(caseOf("登录成功跳转首页"));
        cb.onCase(caseOf("登录成功跳转首页"));   // 完全同题 → 抑制
        cb.onCase(caseOf(" 登录成功跳转首页 ")); // 首尾空白 → 抑制
        cb.onCase(caseOf("Login Success"));      // 不同题 → 放行
        cb.onCase(caseOf("login success"));      // 仅大小写差异 → 抑制

        assertEquals(2, pushed.size());
    }

    @Test
    void blankTitlesTreatedAsSameKey() {
        List<TestCase> pushed = new ArrayList<>();
        TestGeneratorAgent.CaseCallback cb = TestGeneratorAgent.wrapPushDedup(pushed::add);

        cb.onCase(caseOf(null));
        cb.onCase(caseOf(""));
        cb.onCase(caseOf("  "));
        cb.onCase(caseOf("有效用例"));

        assertEquals(2, pushed.size());
    }

    @Test
    void nullCallbackReturnsNull() {
        assertNull(TestGeneratorAgent.wrapPushDedup(null));
    }

    // ==================== uiSelector 白名单清洗（B） ====================

    @Test
    void illegalSelectorTypeRemoved_legalKept() {
        String steps = """
                [
                  {"order":1,"action":"点击首页","target":"首页入口","type":"ui_action",
                   "uiSelector":{"type":"ref","value":"banner"}},
                  {"order":2,"action":"输入用户名","target":"用户名框","type":"ui_action",
                   "uiSelector":{"type":"id","value":"username"}},
                  {"order":3,"action":"断言跳转","target":"页面","type":"state_assert"}
                ]
                """;
        String out = agent.sanitizeUiSelectors(steps);

        assertTrue(out.contains("\"id\""));
        assertFalse(out.contains("\"ref\""), "ref 类型应被剔除");
        // 合法步骤的 uiSelector 保留、非法步骤整体移除该字段
        assertFalse(out.contains("banner"));
        assertTrue(out.contains("username"));
        // state_assert 步骤无 uiSelector，不受影响
        assertTrue(out.contains("state_assert"));
    }

    @Test
    void textAndPathTypesAlsoStripped() {
        String steps = """
                [
                  {"order":1,"action":"点按钮","target":"提交按钮","type":"ui_action",
                   "uiSelector":{"type":"text","value":"提 交"}},
                  {"order":2,"action":"进页面","target":"首页","type":"ui_action",
                   "uiSelector":{"type":"path","value":"/"}}
                ]
                """;
        String out = agent.sanitizeUiSelectors(steps);
        assertFalse(out.contains("uiSelector"), "text/path 均为非法类型，应全部剔除");
    }

    @Test
    void sanitizeIsIdempotentAndSafeOnBadJson() {
        String legal = """
                [{"order":1,"action":"a","target":"t","type":"ui_action",
                  "uiSelector":{"type":"xpath","value":"//div"}}]
                """;
        assertEquals(agent.sanitizeUiSelectors(legal), agent.sanitizeUiSelectors(legal));
        assertEquals("[]", agent.sanitizeUiSelectors("[]"));
        // 非法 JSON 原样返回不抛异常
        assertEquals("not-json", agent.sanitizeUiSelectors("not-json"));
    }
}
