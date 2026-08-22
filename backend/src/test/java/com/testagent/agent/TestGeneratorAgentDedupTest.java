package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.1(G1): 判重语义单测——"正向/异常"成对用例不得误杀。
 * 背景：旧实现字符重叠 > 0.8 即判重且不比较 type，
 * "新增用户-正常" vs "新增用户-异常"（重叠 83%）被系统性误删。
 */
class TestGeneratorAgentDedupTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private TestCase tc(String title, String module, String type) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        tc.setModule(module);
        tc.setType(type);
        return tc;
    }

    @Test
    void sameTitleSameTypeIsDuplicate() {
        assertTrue(agent.isDuplicate(
                tc("新增用户", "用户管理", "positive"),
                tc("新增用户", "用户管理", "positive")));
    }

    @Test
    void sameTitleDifferentTypeNotDuplicate() {
        // G1 核心回归：标题相同、类型不同（一正一逆）不再合并
        assertFalse(agent.isDuplicate(
                tc("新增用户-正常", "用户管理", "positive"),
                tc("新增用户-异常", "用户管理", "negative")));
    }

    @Test
    void sameTypeOverlap082BelowNewThresholdNotDuplicate() {
        // 字符重叠 ≈81.8%（风险清单 G1 实测样例）：旧阈值 0.8 误杀，新阈值 0.9 放行
        assertFalse(agent.isDuplicate(
                tc("支付成功后订单状态检查", "订单", "positive"),
                tc("支付失败后订单状态检查", "订单", "positive")));
    }

    @Test
    void sameTypeFullOverlapStillDuplicate() {
        // 同类型且字符集合完全一致（重叠 1.0 > 0.9）仍判重——真重复不能漏
        assertTrue(agent.isDuplicate(
                tc("登录成功验证", "登录", "positive"),
                tc("验证登录成功", "登录", "positive")));
    }

    @Test
    void sameTitleSameTypeDifferentModuleStillDuplicate() {
        // 标题完全相同且类型相同 → 判重（该分支不比较模块：完全相同的标题几乎必然是重复）
        assertTrue(agent.isDuplicate(
                tc("查询列表", "订单", "positive"),
                tc("查询列表", "用户", "positive")));
    }
}
