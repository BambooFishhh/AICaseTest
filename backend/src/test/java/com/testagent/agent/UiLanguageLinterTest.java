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
        // 机器常量 + state_assert 缺引号文案锚点（v9.2）两条规则都命中
        assertEquals(2, violations.size());
        assertTrue(violations.stream().anyMatch(v -> v.contains("机器常量")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("缺少引号")));
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
    void apiCallStepIsFlagged() {
        // v9.2: 取消 api_call 豁免——UI 自动化用例禁止直接调接口（type/target/expected 全部标记）
        TestCase tc = caseWith("[]",
                "[{\"order\":1,\"type\":\"api_call\",\"target\":\"POST /api/order/create\",\"expected\":\"接口返回400，页面出现'金额非法'错误提示\"}]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertTrue(violations.size() >= 3);
        assertTrue(violations.stream().anyMatch(v -> v.contains("api_call")));
        assertTrue(violations.stream().anyMatch(v -> v.contains("接口化步骤")));
    }

    @Test
    void apiPhraseInTextStepsIsFlagged() {
        TestCase tc = caseWith("[]", "[]");
        tc.setSteps("[\"打开收藏列表\", \"调用取消收藏接口\", \"验证列表刷新\"]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("steps[1]"));
        assertTrue(violations.get(0).contains("接口化步骤"));
    }

    @Test
    void httpMethodPathInTargetIsFlagged() {
        TestCase tc = caseWith("[]",
                "[{\"order\":1,\"type\":\"ui_action\",\"action\":\"取消收藏\",\"target\":\"POST /wx/collect/delete\"}]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("接口化步骤"));
    }

    @Test
    void snakeCaseIdentifierIsFlagged() {
        TestCase tc = caseWith("[]",
                "[{\"order\":1,\"type\":\"input\",\"action\":\"向 input_username 输入 valid_username\",\"target\":\"用户名输入框\"}]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("变量占位符"));
    }

    @Test
    void humanReadableUiStepsPassCleanly() {
        // 人话写法不应被误伤：【】按钮名、真实数据值、中文元素描述
        TestCase tc = caseWith(
                "[\"页面跳转至首页\", \"页面提示'取消收藏成功'\"]",
                "[{\"order\":1,\"type\":\"ui_action\",\"action\":\"打开登录页面\",\"target\":\"/login\"}," +
                 "{\"order\":2,\"type\":\"input\",\"action\":\"输入正确密码：Test@123456\",\"target\":\"密码输入框\"}," +
                 "{\"order\":3,\"type\":\"ui_action\",\"action\":\"点击【登录】按钮\",\"target\":\"登录按钮\"}," +
                 "{\"order\":4,\"type\":\"state_assert\",\"expected\":\"页面跳转至首页，页面显示'首页'与用户昵称\"}]");
        assertEquals(0, UiLanguageLinter.lint(tc).size());
    }

    @Test
    void stateAssertWithoutQuotedAnchorIsFlagged() {
        // v9.2: 抽象断言（无引号文案锚点）在执行侧无法文本验证，生成时即标记
        TestCase tc = caseWith("[]",
                "[{\"order\":1,\"type\":\"state_assert\",\"expected\":\"页面加载完成，不再显示loading状态\"}," +
                 "{\"order\":2,\"type\":\"state_assert\",\"expected\":\"列表中显示至少一个商品项，每个商品项包含图片、名称、价格\"}]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(2, violations.size());
        assertTrue(violations.get(0).contains("缺少引号"));
        assertTrue(violations.get(1).contains("缺少引号"));
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
    void quotedPlaceholderIsFlagged() {
        // v9.4: 引号文案含占位符降级为确认性提示（执行器已支持数字语义匹配），仍纳入 lint 提示
        TestCase tc = caseWith("[\"页面显示'我的收藏'与'共 N 件收藏'\"]", "[]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("占位符"));
        assertTrue(violations.get(0).contains("共 N 件收藏"));
    }

    @Test
    void quotedRealTextIsNotFlaggedAsPlaceholder() {
        // 12.21: 无数量的真实文案不触发任何规则
        TestCase tc = caseWith("[\"页面显示'我的收藏'与'蔓越莓曲奇'\"]", "[]");
        assertEquals(0, UiLanguageLinter.lint(tc).size());
    }

    @Test
    void fixedCountInExpectedIsFlagged() {
        // 12.21: 数量写死（'共 1 件收藏'）数字随数据变化必然脆断，应改占位符 '共 N 件收藏'
        TestCase tc = caseWith("[\"页面显示'我的收藏'，共 1 件收藏\"]", "[]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("数量写死"));
    }

    @Test
    void genericJumpWithoutAnchorIsFlagged() {
        // v9.6: 触发跳转/页面跳转没有 URL 或目标页锚点时不可验证
        TestCase tc = caseWith("[\"点击后触发页面跳转\"]", "[]");
        List<String> violations = UiLanguageLinter.lint(tc);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("泛化表述"));
    }

    @Test
    void nullAndEmptyFieldsAreSafe() {
        TestCase tc = new TestCase();
        assertEquals(0, UiLanguageLinter.lint(tc).size());
        assertEquals(0, UiLanguageLinter.lint(null).size());
    }
}
