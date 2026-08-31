package com.testagent.agent;

import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.12(G22): 选择器池只收 DOM 选择器单测。
 * 旧实现 pool 同时收表单字段 {name, type: 输入框类型, label}——文本匹配胜出后写入
 * uiSelector = {type: "text", value: null} 的废选择器（type 是 input 类型而非选择器类型，
 * value 为空不可执行），固化进用例资产。新实现 pool 只由 domSelectors 构成。
 */
class TestGeneratorAgentSelectorPoolTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private void enrich(FrontendResult fr, TestCase tc) {
        ReflectionTestUtils.invokeMethod(agent, "enrichStructuredSteps", fr, tc);
    }

    private List<Map<String, Object>> steps(TestCase tc) {
        return JsonHelper.parseListMap(tc.getStructuredSteps());
    }

    @Test
    void formsOnlyPoolWritesNoUiSelector() {
        // 表单字段不再进池：即使文案完全命中，也不得写入 uiSelector
        FrontendResult fr = FrontendResult.builder()
                .domSelectors(List.of())
                .forms(List.of(Map.of(
                        "name", "username",
                        "type", "text",
                        "label", "用户名输入框")))
                .build();

        TestCase tc = new TestCase();
        tc.setTitle("表单字段不入池");
        tc.setStructuredSteps("[{\"order\":1,\"type\":\"ui_action\",\"action\":\"输入\",\"target\":\"用户名输入框\"}]");

        enrich(fr, tc);

        Map<String, Object> step = steps(tc).get(0);
        assertFalse(step.containsKey("uiSelector"),
                "表单字段无可执行 value，不得作为 uiSelector 候选（旧实现曾写入 {type:\"text\",value:null}）");
    }

    @Test
    void domSelectorMatchWritesExecutableUiSelector() {
        // DOM 选择器命中 → uiSelector 携带选择器语义（type=css、value 可执行非空）
        FrontendResult fr = FrontendResult.builder()
                .componentSummaries(List.of(Map.of(
                        "component", "SubmitOrder",
                        "route", "/order/confirm")))
                .domSelectors(List.of(Map.of(
                        "component", "SubmitOrder",
                        "selectors", List.of(Map.of(
                                "type", "css",
                                "value", "#submit-order",
                                "element", "button")))))
                .forms(List.of())
                .build();

        TestCase tc = new TestCase();
        tc.setTitle("DOM 选择器入池");
        tc.setStructuredSteps("[{\"order\":1,\"type\":\"ui_action\",\"action\":\"打开确认订单页\","
                + "\"target\":\"/order/confirm\",\"uiSelector\":{\"type\":\"route\",\"value\":\"/order/confirm\"}},"
                + "{\"order\":2,\"type\":\"ui_action\",\"action\":\"click\",\"target\":\"SubmitOrder 按钮\"}]");

        enrich(fr, tc);

        Map<String, Object> step = steps(tc).get(1);
        Object selObj = step.get("uiSelector");
        assertTrue(selObj instanceof Map, "DOM 选择器命中应写入 uiSelector");
        Map<?, ?> uiSelector = (Map<?, ?>) selObj;
        assertEquals("css", uiSelector.get("type"), "type 应为选择器类型而非 input 类型");
        assertEquals("#submit-order", uiSelector.get("value"), "value 应为可执行选择器");
    }

    @Test
    void existingUiSelectorNotOverwritten() {
        // 已有有效 uiSelector 的步骤保持不变（补齐逻辑只针对空缺步骤）
        FrontendResult fr = FrontendResult.builder()
                .domSelectors(List.of(Map.of(
                        "component", "OtherComponent",
                        "selectors", List.of(Map.of(
                                "type", "xpath",
                                "value", "//button[@id='other']",
                                "element", "button")))))
                .forms(List.of())
                .build();

        TestCase tc = new TestCase();
        tc.setTitle("已有选择器不覆盖");
        tc.setStructuredSteps("[{\"order\":1,\"type\":\"ui_action\",\"action\":\"click\",\"target\":\"提交\","
                + "\"uiSelector\":{\"type\":\"css\",\"value\":\"#btn-submit\"}}]");

        enrich(fr, tc);

        Map<?, ?> uiSelector = (Map<?, ?>) steps(tc).get(0).get("uiSelector");
        assertEquals("#btn-submit", uiSelector.get("value"), "已有有效选择器不得被覆盖");
    }
}
