package com.testagent.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v12.31: 伪接口断言步剔除（A 类可扩展根治）——生成器把"模拟发送非法请求体→断言前端报错"写成
 * type=state_assert + data{ids/incomplete_body}，无任何 UI 锚点、执行器无处可点，必然 failed
 * （实测 TC-1208/1209/1215）。isFakeApiAssertStep 判定：state_assert + action 模拟话术/target 接口名
 * + data 请求体模拟键 → 剔除。纯 UI 可测负向语义（点击按钮看前端校验提示，ui_action 形态）不被误删。
 */
class TestGeneratorAgentFakeApiStepTest {

    private static Map<String, Object> step(Object type, String action, String target, Map<String, Object> data) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", type);
        m.put("action", action);
        m.put("target", target);
        m.put("expected", "…");
        m.put("data", data == null ? Map.of() : data);
        return m;
    }

    // ---- TC-1208: 足迹删除，ids=[9999999]，action 明示"构造非法请求体" ----
    @Test
    void footprintDeleteIllegalIdsIsFakeApiStep() {
        Map<String, Object> s = step("state_assert",
                "尝试执行删除操作（测试需在自动化脚本中构造非法请求体）",
                "足迹删除接口",
                Map.of("ids", java.util.List.of(9999999)));
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "含构造非法请求体+ids 应判伪接口步");
    }

    // ---- v13.8: input 型变体（实测 TC-1264）——开发者工具/断点模拟缺参请求 ----
    @Test
    void inputTypeDevToolsMockIsFakeApiStep() {
        Map<String, Object> s = step("input",
                "通过开发者工具或断点模拟缺失type的取消请求",
                "取消收藏请求",
                Map.of());
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "input 型开发者工具模拟请求应判伪接口步");
    }

    @Test
    void inputTypeInterceptRequestIsFakeApiStep() {
        Map<String, Object> s = step("input",
                "拦截发送到取消收藏接口的请求并删除type字段",
                "取消收藏接口",
                Map.of());
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "拦截/接口话术应判伪接口步");
    }

    // ---- v13.10: 第二变体（实测 TC-1290/1293/1294）Console/fetch/控制台话术 ----
    @Test
    void inputTypeConsoleFetchIsFakeApiStep() {
        Map<String, Object> s = step("input",
                "在Console中执行fetch请求，传入type=2（非0非1）",
                "收藏总数接口",
                Map.of());
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "Console/fetch 话术应判伪接口步");
    }

    @Test
    void inputTypeDevConsoleApiCallIsFakeApiStep() {
        Map<String, Object> s = step("input",
                "在控制台输入缺失参数的接口调用代码",
                "取消收藏接口",
                Map.of());
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "控制台+接口调用应判伪接口步");
    }

    /** 正常 UI 输入措辞（"模拟用户输入"无接口/请求词）不得误删 */
    @Test
    void inputTypeNormalUserInputMockKept() {
        Map<String, Object> s = step("input",
                "模拟用户输入用户名user123",
                "账号输入框",
                Map.of());
        assertFalse(TestGeneratorAgent.isFakeApiAssertStep(s), "正常 UI 输入不应误删");
    }

    // ---- TC-1209: 足迹批量删除，ids=[]，action 明示"测试脚本模拟" ----
    @Test
    void footprintBatchEmptyIdsIsFakeApiStep() {
        Map<String, Object> s = step("state_assert",
                "（测试脚本模拟）发送一个ids为空数组的批量删除请求",
                "批量删除接口",
                Map.of("ids", java.util.List.of()));
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "含测试脚本模拟+ids 应判伪接口步");
    }

    // ---- TC-1215: 收藏，incomplete_body={type:0} ----
    @Test
    void collectIncompleteBodyIsFakeApiStep() {
        Map<String, Object> s = step("state_assert",
                "（测试脚本模拟）发送一个不完整的收藏请求体（如缺少valueId）",
                "收藏接口",
                Map.of("incomplete_body", Map.of("type", 0)));
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "含发送不完整请求体+incomplete_body 应判伪接口步");
    }

    // ---- 非 state_assert（ui_action 真实点击）不被误删 ----
    @Test
    void realUiActionNotDeleted() {
        Map<String, Object> s = step("ui_action",
                "点击【删除选中】按钮",
                "删除选中按钮",
                Map.of());
        assertFalse(TestGeneratorAgent.isFakeApiAssertStep(s), "真实 ui_action 点击不应被误删");
    }

    // ---- 正常 state_assert（页面可见文案断言，无模拟话术/data 空）不被误删 ----
    @Test
    void realStateAssertPageTextNotDeleted() {
        Map<String, Object> s = step("state_assert",
                "验证足迹列表为空",
                "浏览足迹页",
                Map.of());
        assertFalse(TestGeneratorAgent.isFakeApiAssertStep(s), "正常页面文案断言不应被误删");
    }

    // ---- 真实"删除选中"按钮点击后断言前端"请先选择"toast（负向 UI 可测语义）不被误删 ----
    @Test
    void uiNegativeSemanticsNotDeleted() {
        Map<String, Object> s = step("ui_action",
                "点击【删除选中】按钮",
                "删除选中按钮",
                Map.of());
        assertFalse(TestGeneratorAgent.isFakeApiAssertStep(s),
                "点击按钮看前端'请先选择'提示是 UI 可测语义，不应误判伪接口步");
    }

    // ---- 无 data 但 target 是接口名 + action 模拟话术，判伪接口步 ----
    @Test
    void apiTargetWithMockSpeechIsFakeEvenNoData() {
        Map<String, Object> s = step("state_assert",
                "（测试脚本）模拟调用删除接口",
                "批量删除接口",
                Map.of());
        assertTrue(TestGeneratorAgent.isFakeApiAssertStep(s), "target 明确是接口+模拟话术即判伪接口步");
    }
}
