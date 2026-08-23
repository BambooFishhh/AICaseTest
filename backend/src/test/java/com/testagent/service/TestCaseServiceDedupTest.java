package com.testagent.service;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.12(G23): 存储侧判重口径与生成侧（TestGeneratorAgent.isDuplicate）对齐验证。
 * 旧实现：标题判重不比较 type（追加生成的负向/边界用例被同标题正向旧用例误杀）、
 * 重叠率阈值 0.8（误杀率高）、子串规则无最短门槛（"登录" vs "退出登录后重新登录" 误杀）。
 */
class TestCaseServiceDedupTest {

    private final TestCaseService service = new TestCaseService();

    private TestCase tc(String title, String module, String type) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        tc.setModule(module);
        tc.setType(type);
        return tc;
    }

    private boolean isDup(TestCase a, TestCase b) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(service, "isDuplicate", a, b));
    }

    @Test
    void sameTitleDifferentTypeNotDuplicate() {
        // 对齐 G1：追加生成的负向用例不得被同标题正向旧用例误杀
        assertFalse(isDup(
                tc("新增用户-正常", "用户管理", "positive"),
                tc("新增用户-正常", "用户管理", "negative")),
                "同标题不同 type 不应判重");
    }

    @Test
    void sameTitleSameTypeStillDuplicate() {
        assertTrue(isDup(
                tc("新增用户", "用户管理", "positive"),
                tc("新增用户", "用户管理", "positive")),
                "同标题同 type 应判重");
    }

    @Test
    void overlap085BelowThresholdNotDuplicate() {
        // 字符重叠 ≈85% < 0.9 新阈值 → 放行（旧阈值 0.8 会误杀）
        assertFalse(isDup(
                tc("支付成功后订单状态检查", "订单", "positive"),
                tc("支付失败后订单状态检查", "订单", "positive")),
                "重叠率 0.85 不应判重（阈值 0.9）");
    }

    @Test
    void overlap095AboveThresholdDuplicate() {
        // 高重叠真重复仍判重（两侧口径一致：TestGeneratorAgent 同语义）
        assertTrue(isDup(
                tc("登录成功验证流程", "登录", "positive"),
                tc("验证登录成功流程", "登录", "positive")),
                "字符集几乎一致（重叠 >0.9）应判重");
    }

    @Test
    void twoCharSubstringNotDuplicate() {
        assertFalse(isDup(
                tc("登录", "认证", "positive"),
                tc("退出登录后重新登录", "认证", "positive")),
                "2 字子串包含不应判重（最短门槛 4 字）");
    }

    @Test
    void fourCharSubstringDuplicate() {
        assertTrue(isDup(
                tc("订单查询", "订单", "positive"),
                tc("订单查询列表", "订单", "positive")),
                "4 字子串包含（同模块同类型）应判重");
    }

    @Test
    void substringRuleRequiresSameType() {
        assertFalse(isDup(
                tc("订单查询", "订单", "positive"),
                tc("订单查询列表", "订单", "negative")),
                "type 不一致时子串规则不得判重");
    }
}
