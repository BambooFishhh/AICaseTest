package com.testagent.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.3(L5): SPA 生效判断三指纹比较验证——URL 不变但页面内容变化时应判"生效"。
 */
class ExecutionAgentEffectivenessTest {

    private Map<String, String> status(String url, String title, String snippet) {
        return Map.of("url", url, "title", title, "textSnippet", snippet);
    }

    @Test
    void spaUrlUnchangedButSnippetChangedIsEffective() {
        Map<String, String> before = status("/order", "订单页", "订单列表 空");
        Map<String, String> after = status("/order", "订单页", "订单列表 下单成功 订单号1001");

        assertTrue(ExecutionAgent.pageChanged(before, after),
                "SPA URL 不变但内容快照变化 → 应判生效（旧版仅比 URL 会误判未生效并重复点击）");
    }

    @Test
    void titleChangeIsEffective() {
        Map<String, String> before = status("/order", "订单页", "订单列表");
        Map<String, String> after = status("/order", "订单详情页", "订单列表");

        assertTrue(ExecutionAgent.pageChanged(before, after));
    }

    @Test
    void urlChangeIsEffective() {
        Map<String, String> before = status("/login", "登录", "登录表单");
        Map<String, String> after = status("/home", "首页", "欢迎");

        assertTrue(ExecutionAgent.pageChanged(before, after));
    }

    @Test
    void nothingChangedIsNotEffective() {
        Map<String, String> before = status("/order", "订单页", "订单列表");
        Map<String, String> after = status("/order", "订单页", "订单列表");

        assertFalse(ExecutionAgent.pageChanged(before, after));
    }

    @Test
    void nullStatusIsConservativelyNotEffective() {
        assertFalse(ExecutionAgent.pageChanged(null, Map.of("url", "/x")));
        assertFalse(ExecutionAgent.pageChanged(Map.of("url", "/x"), null));
        assertFalse(ExecutionAgent.pageChanged(null, null));
    }

    @Test
    void missingSnippetKeyFallsBackToEmpty() {
        Map<String, String> before = Map.of("url", "/order", "title", "订单页");
        Map<String, String> after = Map.of("url", "/order", "title", "订单页", "textSnippet", "新内容");

        assertTrue(ExecutionAgent.pageChanged(before, after), "textSnippet 缺失视为空，与有值比较应判变化");
    }
}
