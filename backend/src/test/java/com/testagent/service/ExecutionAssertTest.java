package com.testagent.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.6(L6): 共享断言工具测试——三层断言（URL/标题 → DOM 文本 textSnippet → skipped）。
 * 业务背景：expected 此前从未被验证，"用例通过"≠"预期结果成立"；
 * v7.0(E4) 只比对 url/title，v7.6 扩展 DOM 文本断言（G20 的 UI 现象形 expected 具备可执行前提）。
 */
class ExecutionAssertTest {

    private Map<String, String> page(String url, String title, String snippet) {
        Map<String, String> m = new HashMap<>();
        m.put("url", url);
        m.put("title", title);
        if (snippet != null) {
            m.put("textSnippet", snippet);
        }
        return m;
    }

    @Test
    void urlKeywordMatchedPasses() {
        assertEquals("passed", ExecutionAssert.assertExpected(
                "URL 包含 /order/list", page("/order/list?page=1", "订单列表", null)));
    }

    @Test
    void urlKeywordMissedFails() {
        assertEquals("failed", ExecutionAssert.assertExpected(
                "URL 跳转 /login 页面", page("/home", "首页", null)));
    }

    @Test
    void chineseOnlyTargetWithoutSnippetIsSkipped() {
        // 无 textSnippet（页面状态无文本快照）且 url/title 无法机械比较 → 未验证
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "URL 跳转首页", page("/home", "首页", null)));
    }

    // ==================== 层 2: DOM 文本断言 ====================

    @Test
    void domTextKeywordHitPasses() {
        // toast/提示文案出现在页面 body 文本中 → 通过（v7.6 新能力：中文断言）
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面出现删除成功提示",
                page("/order/list", "订单列表", "订单列表 删除成功 操作已完成")));
    }

    @Test
    void domTextKeywordMissedFails() {
        // 期望"库存不足"提示，页面文本没有 → 明确失败（不再无条件假通过）
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面提示库存不足",
                page("/order/create", "创建订单", "创建订单 提交成功")));
    }

    @Test
    void chineseOnlyTargetWithSnippetIsVerifiable() {
        // "URL 跳转首页"——层 1 提取不到英文 token，落入层 2 中文断言
        assertEquals("passed", ExecutionAssert.assertExpected(
                "URL 跳转首页", page("/home", "首页", "首页 欢迎回来")));
        assertEquals("failed", ExecutionAssert.assertExpected(
                "URL 跳转首页", page("/home", "订单", "订单列表")));
    }

    @Test
    void multipleKeywordsAllMustHit() {
        // 多个关键词：全部命中才通过
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面出现提交成功且返回订单列表",
                page("/order/list", "订单列表", "提交成功 订单列表")));
        assertEquals("failed", ExecutionAssert.assertExpected(
                "页面出现提交成功且库存充足",
                page("/order/list", "订单列表", "提交成功")));
    }

    @Test
    void englishTokenInSnippetCaseInsensitive() {
        assertEquals("passed", ExecutionAssert.assertExpected(
                "页面出现 Order Created 提示",
                page("/orders", "订单", "ORDER CREATED at 2026-08-23")));
    }

    @Test
    void apiStyleExpectedIsSkippedNotFailed() {
        // API 形态断言无中文关键词且无英文 token 可提取 → 未验证，不误报失败
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "status=PENDING_PAYMENT", page("/api/order/1", "订单详情", "订单详情")));
    }

    @Test
    void emptySnippetIsSkipped() {
        assertEquals("skipped", ExecutionAssert.assertExpected(
                "页面出现删除成功提示", page("/order/list", "订单列表", "")));
    }

    @Test
    void blankExpectedAndNullPageStateAreSkipped() {
        assertEquals("skipped", ExecutionAssert.assertExpected("", page("/home", "首页", null)));
        assertEquals("skipped", ExecutionAssert.assertExpected(null, page("/home", "首页", null)));
        assertEquals("skipped", ExecutionAssert.assertExpected("页面出现删除成功提示", null));
    }

    @Test
    void describeContainsExpectedAndActual() {
        String desc = ExecutionAssert.describe("页面提示库存不足",
                page("/order/create", "创建订单", "提交成功"));
        assertTrue(desc.contains("库存不足"), "描述应含期望文本");
        assertTrue(desc.contains("/order/create"), "描述应含实际 URL");
    }

    @Test
    void snippetSummaryTruncatedTo120Chars() {
        String longSnippet = "a".repeat(300);
        assertEquals(123, ExecutionAssert.snippetSummary(Map.of("textSnippet", longSnippet)).length());
        assertEquals("短文本", ExecutionAssert.snippetSummary(Map.of("textSnippet", "短文本")));
    }
}
