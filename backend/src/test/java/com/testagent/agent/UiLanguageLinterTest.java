package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.3(G20层2): 预期结果 UI 语言 lint 规则验证（命中 + 不误报）。
 */
class UiLanguageLinterTest {

    private TestCase caseWith(String expectedResultsJson, String structuredStepsJson) {
        TestCase tc = new TestCase();
        tc.setTitle("lint测试");
        tc.setExpectedResults(expectedResultsJson);
        tc.setStructuredSteps(structuredStepsJson);
        return tc;
    }

    @Test
    void detectsHttpStatusCode() {
        TestCase tc = caseWith("[\"接口返回400\"]", "[]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("HTTP状态码"));
    }

    @Test
    void detectsUpperConstInUiStep() {
        TestCase tc = caseWith("[]",
                "[{\"order\":1,\"type\":\"state_assert\",\"expected\":\"订单状态=PENDING_PAYMENT\"}]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("机器常量"));
    }

    @Test
    void detectsFieldAssignment() {
        TestCase tc = caseWith("[\"页面提示 errorMsg=参数缺失\"]", "[]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("后端字段赋值"));
    }

    @Test
    void uiLanguageExpectedPassesCleanly() {
        TestCase tc = caseWith(
                "[\"页面提示'金额非法，请重新输入'\", \"跳转到订单列表页\"]",
                "[{\"order\":1,\"type\":\"state_assert\",\"expected\":\"订单行显示'待支付'状态\"}]");
        assertEquals(0, UiLanguageLinter.lint(tc).size());
    }

    @Test
    void apiCallStepIsExempted() {
        // api_call 步骤允许接口语义（如"接口返回 400"）
        TestCase tc = caseWith("[]",
                "[{\"order\":1,\"type\":\"api_call\",\"target\":\"POST /api/order/create\",\"expected\":\"接口返回400，页面出现'金额非法'错误提示\"}]");
        assertEquals(0, UiLanguageLinter.lint(tc).size());
    }

    @Test
    void uiActionStepWithHttpCodeIsFlagged() {
        TestCase tc = caseWith("[]",
                "[{\"order\":1,\"type\":\"ui_action\",\"expected\":\"返回401跳转登录页\"}]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
    }

    @Test
    void normalAmountTextIsNotFalsePositive() {
        // 400 元金额这类数字不应触发 HTTP 码规则（规则要求"返回/响应/状态码"上下文）
        TestCase tc = caseWith("[\"订单总价显示 400 元\"]", "[]");
        assertEquals(0, UiLanguageLinter.lint(tc).size());
    }

    @Test
    void nullAndEmptyFieldsAreSafe() {
        TestCase tc = new TestCase();
        assertEquals(0, UiLanguageLinter.lint(tc).size());
        assertEquals(0, UiLanguageLinter.lint(null).size());
    }
}
