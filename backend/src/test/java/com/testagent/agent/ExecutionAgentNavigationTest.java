package com.testagent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v9.2: 导航命中判定——hash 路由应用必须看 # 后面的路由段。
 * 修复的假通过场景：history 形式导航到 hash 路由 SPA 时 URL 呈 base/collect#/
 * （path 段残留路由、hash 停在首页），旧 contains 全串判定误判命中。
 */
class ExecutionAgentNavigationTest {

    private final ExecutionAgent agent = new ExecutionAgent();

    @Test
    void hashAppNavigatedToPathFormIsNotAHit() {
        // 修复的 bug 场景：base/collect#/ ——path 段残留路由但 hash 为空（实际停在首页）
        assertFalse(agent.urlHitsRoute("http://172.31.160.1:6255/collect#/", "/collect"));
    }

    @Test
    void hashRouteFormIsAHit() {
        assertTrue(agent.urlHitsRoute("http://172.31.160.1:6255/#/collect", "/collect"));
        assertTrue(agent.urlHitsRoute("http://172.31.160.1:6255/#/collect?type=0", "/collect"));
    }

    @Test
    void historyAppPathFormIsAHit() {
        assertTrue(agent.urlHitsRoute("http://172.31.160.1:6255/collect", "/collect"));
        assertTrue(agent.urlHitsRoute("http://172.31.160.1:6255/collect?tab=1", "/collect"));
    }

    @Test
    void hashHomeIsNotAHitForOtherRoute() {
        assertFalse(agent.urlHitsRoute("http://172.31.160.1:6255/#/index", "/collect"));
    }

    @Test
    void blankInputsAreSafe() {
        assertFalse(agent.urlHitsRoute(null, "/collect"));
        assertFalse(agent.urlHitsRoute("", "/collect"));
        assertFalse(agent.urlHitsRoute("http://host/#/collect", ""));
    }
}
