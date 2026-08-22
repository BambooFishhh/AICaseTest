package com.testagent.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v7.0(E4): state_assert 最小诚实断言的纯函数测试。
 * 业务背景：状态断言步骤此前无条件 passed（假通过），修复后按 expected 与
 * 页面 url/title 的包含比较给出 passed/failed/skipped（未验证）。
 */
class ExecutionServiceAssertTest {

    private Map<String, String> page(String url, String title) {
        Map<String, String> m = new HashMap<>();
        m.put("url", url);
        m.put("title", title);
        return m;
    }

    @Test
    void urlKeywordMatchedPasses() {
        // 需求：URL 包含 /order/list，实际跳到了订单列表页 → 通过
        assertEquals("passed", ExecutionService.assertExpected(
                "URL 包含 /order/list", page("https://app.com/order/list?page=1", "订单列表")));
    }

    @Test
    void urlKeywordMissedFails() {
        // 需求：跳转登录页，实际还在首页 → 明确失败（不再假通过）
        assertEquals("failed", ExecutionService.assertExpected(
                "URL 跳转 /login 页面", page("https://app.com/home", "首页")));
    }

    @Test
    void apiStyleExpectedIsSkippedNotFailed() {
        // API 形态断言（status=XXX）无法在 UI 层验证 → 未验证，而不是误报失败
        assertEquals("skipped", ExecutionService.assertExpected(
                "status=PENDING_PAYMENT", page("https://app.com/api/order/1", "订单详情")));
    }

    @Test
    void blankExpectedIsSkipped() {
        assertEquals("skipped", ExecutionService.assertExpected("", page("/home", "首页")));
        assertEquals("skipped", ExecutionService.assertExpected(null, page("/home", "首页")));
    }

    @Test
    void chineseOnlyTargetIsSkipped() {
        // "URL 跳转首页"——目标是中文语义，url/title 无法机械比较 → 诚实标注未验证
        assertEquals("skipped", ExecutionService.assertExpected(
                "URL 跳转首页", page("https://app.com/home", "首页")));
    }

    @Test
    void titleKeywordMatchedPasses() {
        assertEquals("passed", ExecutionService.assertExpected(
                "页面标题显示 Dashboard", page("https://app.com/x", "My Dashboard Panel")));
    }

    @Test
    void nullPageStateIsSkipped() {
        // 页面状态读取失败时不误报失败
        assertEquals("skipped", ExecutionService.assertExpected("URL 包含 /order/list", null));
    }
}
