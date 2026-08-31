package com.testagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * v9.6: enrich 选择器按路由/组件作用域收敛——不再全局混池，
 * 跨页同名元素（我的页收藏入口 vs 商品详情页收藏图标）不得互相错配。
 */
class TestGeneratorAgentSelectorScopeTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private FrontendResult frontendResult() {
        return FrontendResult.builder()
                .componentSummaries(List.of(
                        Map.of("component", "User", "route", "/user"),
                        Map.of("component", "GoodsDetail", "route", "/goods/:id")))
                .domSelectors(List.of(
                        Map.of("component", "User", "file", "User.vue",
                                "selectors", List.of(
                                        Map.of("type", "css", "value", ".like-o", "element", "i",
                                                "label", "我的收藏"))),
                        Map.of("component", "GoodsDetail", "file", "GoodsDetail.vue",
                                "selectors", List.of(
                                        Map.of("type", "text", "value", "收藏", "label", "收藏")))))
                .build();
    }

    @Test
    void scopedEnrichUsesUserPageSelectorNotDetailPage() throws Exception {
        TestCase tc = caseWith(
                List.of(
                        Map.of("order", 1, "type", "ui_action", "action", "打开【我的】页面",
                                "target", "/user", "expected", "页面加载",
                                "uiSelector", Map.of("type", "route", "value", "/user")),
                        Map.of("order", 2, "type", "ui_action", "action", "点击【我的收藏】入口",
                                "target", "我的收藏入口", "expected", "页面跳转"),
                        Map.of("order", 3, "type", "state_assert", "action", "验证结果",
                                "target", "收藏页", "expected", "页面显示'我的收藏'")));

        agent.enrichStructuredSteps(frontendResult(), tc);

        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        Map<String, Object> selector = (Map<String, Object>) steps.get(1).get("uiSelector");
        assertEquals("css", selector.get("type"));
        assertEquals(".like-o", selector.get("value"));
    }

    @Test
    void templatedRouteStillScopesToDetailComponent() throws Exception {
        TestCase tc = caseWith(
                List.of(
                        Map.of("order", 1, "type", "ui_action", "action", "打开商品详情页",
                                "target", "/goods/456", "expected", "页面加载",
                                "uiSelector", Map.of("type", "route", "value", "/goods/456")),
                        Map.of("order", 2, "type", "ui_action", "action", "点击【收藏】按钮",
                                "target", "收藏按钮", "expected", "状态变化")));

        agent.enrichStructuredSteps(frontendResult(), tc);

        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        Map<String, Object> selector = (Map<String, Object>) steps.get(1).get("uiSelector");
        assertEquals("text", selector.get("type"));
        assertEquals("收藏", selector.get("value"));
    }

    @Test
    void withoutRouteContextNoSelectorIsInjected() throws Exception {
        TestCase tc = caseWith(
                List.of(
                        Map.of("order", 1, "type", "ui_action", "action", "点击【收藏】按钮",
                                "target", "收藏按钮", "expected", "状态变化")));

        agent.enrichStructuredSteps(frontendResult(), tc);

        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        assertNull(steps.get(0).get("uiSelector"), "路由未知时应宁缺勿错，不做全局混池");
    }

    @Test
    void unknownRouteComponentSkipsEnrichment() throws Exception {
        FrontendResult fr = FrontendResult.builder()
                .componentSummaries(List.of(Map.of("component", "User", "route", "/user")))
                .domSelectors(List.of(
                        Map.of("component", "User", "file", "User.vue",
                                "selectors", List.of(
                                        Map.of("type", "css", "value", ".like-o", "element", "i",
                                                "label", "我的收藏")))))
                .build();
        TestCase tc = caseWith(
                List.of(
                        Map.of("order", 1, "type", "ui_action", "action", "打开【我的收藏】页面",
                                "target", "/collect", "expected", "页面加载",
                                "uiSelector", Map.of("type", "route", "value", "/collect")),
                        Map.of("order", 2, "type", "ui_action", "action", "点击首个商品",
                                "target", "商品卡片", "expected", "进入详情")));

        agent.enrichStructuredSteps(fr, tc);

        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        assertFalse(steps.get(1).containsKey("uiSelector"),
                "当前路由没有对应组件选择器时不得跨池补选择器");
    }

    @Test
    void fakeSelectorNotInRealPoolIsDroppedAndReplaced() throws Exception {
        TestCase tc = caseWith(
                List.of(
                        Map.of("order", 1, "type", "ui_action", "action", "打开商品详情页",
                                "target", "/goods/456", "expected", "页面加载",
                                "uiSelector", Map.of("type", "route", "value", "/goods/456")),
                        Map.of("order", 2, "type", "ui_action", "action", "点击收藏图标",
                                "target", "收藏图标", "expected", "状态变化",
                                "uiSelector", Map.of("type", "css", "value", ".collect-icon"))));

        agent.enrichStructuredSteps(frontendResult(), tc);

        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        Map<String, Object> selector = (Map<String, Object>) steps.get(1).get("uiSelector");
        assertEquals("text", selector.get("type"), "编造 css 选择器应被剔除并换成真实池 text 选择器");
        assertEquals("收藏", selector.get("value"));
    }

    private TestCase caseWith(List<Map<String, Object>> steps) throws Exception {
        TestCase tc = new TestCase();
        tc.setTitle("作用域用例");
        tc.setModule("我的收藏");
        tc.setType("positive");
        tc.setStructuredSteps(objectMapper.writeValueAsString(steps));
        return tc;
    }
}
