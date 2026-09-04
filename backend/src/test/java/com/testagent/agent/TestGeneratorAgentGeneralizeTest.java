package com.testagent.agent;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.11: 两项泛化规则单测——替代"按措辞逐个加正则"的打地鼠模式。
 *
 * ① 结构性剔除：type=input 且无 uiSelector.value 且无 inputValue 的步骤执行端必然
 *    报"输入步骤缺少 uiSelector.value"（实测三种措辞变体），按结构判定而非话术枚举；
 * ② 泛化去钉：列表语境断言里的引号值不在 pageReality 固定文案集内即视为运行时数据
 *    剥掉（'我的收藏'/'暂无足迹'等固定 UI 有权威记录保留，'共 N 件'/'/goods/' 豁免）。
 *    pageReality 未加载（其他项目/单测默认态）时泛化不激活，行为与旧版一致。
 */
class TestGeneratorAgentGeneralizeTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private TestCase caseOf(String title, String stepsJson) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        tc.setModule("收藏");
        tc.setStructuredSteps(stepsJson);
        return tc;
    }

    // ---- ① 结构性 input 剔除 ----

    @Test
    void inputWithoutSelectorAndValueRemoved() {
        String steps = "[{\"order\":1,\"action\":\"正常输入用户名\",\"type\":\"input\","
                + "\"uiSelector\":{\"type\":\"css\",\"value\":\"input[name=username]\"},\"data\":{\"inputValue\":\"user123\"}},"
                + "{\"order\":2,\"action\":\"在Console中执行fetch请求，传入type=2\",\"type\":\"input\",\"data\":{}},"
                + "{\"order\":3,\"action\":\"输入搜索备注\",\"type\":\"input\",\"inputValue\":\"hello\"},"
                + "{\"order\":4,\"action\":\"点击查询\",\"type\":\"ui_action\"}]";
        List<TestCase> cases = new ArrayList<>(List.of(caseOf("参数缺失", steps)));
        agent.stripUnexecutableInputSteps(cases);

        List<Map<String, Object>> parsed = JsonHelper.parseListMap(cases.get(0).getStructuredSteps());
        assertEquals(3, parsed.size(), "无选择器且无值的 input 步应被剔除");
        assertEquals("正常输入用户名", parsed.get(0).get("action"), "带选择器的输入步保留");
        assertEquals("输入搜索备注", parsed.get(1).get("action"), "带 inputValue 的输入步保留");
        assertEquals("点击查询", parsed.get(2).get("action"), "非 input 步保留");
    }

    @Test
    void casesWithoutInputUntouched() {
        String steps = "[{\"order\":1,\"action\":\"点击删除\",\"type\":\"ui_action\"}]";
        List<TestCase> cases = new ArrayList<>(List.of(caseOf("无输入步", steps)));
        agent.stripUnexecutableInputSteps(cases);
        assertEquals(steps, cases.get(0).getStructuredSteps(), "无 input 步的用例不应被改写");
    }

    // ---- ② 泛化去钉 ----

    private static Set<String> fixedOf(String... items) {
        return new HashSet<>(List.of(items));
    }

    @Test
    void runtimeQuotedValueDemotedInListContext() {
        Set<String> fixed = fixedOf("我的收藏", "暂无足迹", "共 N 件收藏", "删除");
        String out = TestGeneratorAgent.demoteRuntimeQuotedValues(
                "列表中包含商品名'蔓越莓曲奇'", fixed);
        assertFalse(out.contains("蔓越莓曲奇"), "运行时商品名应被剥离: " + out);
        assertTrue(out.contains("至少一个"), "失去锚点后应追加存在性断言: " + out);
    }

    @Test
    void fixedUiQuoteKeptAndRuntimeDropped() {
        Set<String> fixed = fixedOf("我的收藏", "暂无足迹");
        String out = TestGeneratorAgent.demoteRuntimeQuotedValues(
                "页面显示'我的收藏'，列表不再包含商品'蔓越莓曲奇'", fixed);
        assertTrue(out.contains("'我的收藏'"), "pageReality 固定文案应保留: " + out);
        assertFalse(out.contains("'蔓越莓曲奇'"), "运行时商品名应剥离: " + out);
        assertFalse(out.contains("至少一个"), "仍有引号锚点不应追加存在性: " + out);
    }

    @Test
    void countAndUrlFormsExempt() {
        Set<String> fixed = fixedOf("我的收藏");
        assertEquals("页面显示'共 N 件收藏'",
                TestGeneratorAgent.demoteRuntimeQuotedValues("页面显示'共 N 件收藏'", fixed),
                "共 N 计数形态豁免");
        assertEquals("页面URL包含'/goods/'",
                TestGeneratorAgent.demoteRuntimeQuotedValues("页面URL包含'/goods/'", fixed),
                "URL 形态豁免");
    }

    @Test
    void emptyFixedSetInactive() {
        String exp = "列表中包含商品名'蔓越莓曲奇'";
        assertEquals(exp, TestGeneratorAgent.demoteRuntimeQuotedValues(exp, Set.of()),
                "固定文案集为空时泛化不激活");
    }

    // ---- ③ 集成：pageRealityCache 驱动泛化开关 ----

    @Test
    void pageRealityCacheActivatesGeneralDemote() throws Exception {
        Object reality = Map.of("collect", Map.of("visibleText",
                List.of("我的收藏", "暂无收藏", "共 N 件收藏", "删除")));
        Field f = TestGeneratorAgent.class.getDeclaredField("pageRealityCache");
        f.setAccessible(true);
        f.set(agent, reality);

        String steps = "[{\"order\":1,\"action\":\"查看收藏列表\",\"target\":\"页面\","
                + "\"expected\":\"列表中包含商品名'蔓越莓曲奇'\",\"data\":{},\"type\":\"state_assert\"}]";
        List<TestCase> cases = new ArrayList<>(List.of(caseOf("收藏列表", steps)));
        agent.demotePinnedBusinessNames(cases);

        String exp = String.valueOf(JsonHelper.parseListMap(cases.get(0).getStructuredSteps())
                .get(0).get("expected"));
        assertFalse(exp.contains("蔓越莓曲奇"), "pageReality 在场时运行时商品名应被剥离: " + exp);
        assertTrue(exp.contains("至少一个"), "应追加存在性断言: " + exp);
    }

    @Test
    void noPageRealityKeepsOldBehavior() {
        String exp = "列表中包含商品名'蔓越莓曲奇'";
        String steps = "[{\"order\":1,\"action\":\"查看收藏列表\",\"target\":\"页面\","
                + "\"expected\":\"" + exp + "\",\"data\":{},\"type\":\"state_assert\"}]";
        List<TestCase> cases = new ArrayList<>(List.of(caseOf("收藏列表", steps)));
        agent.demotePinnedBusinessNames(cases);

        String out = String.valueOf(JsonHelper.parseListMap(cases.get(0).getStructuredSteps())
                .get(0).get("expected"));
        assertEquals(exp, out, "pageReality 未加载时泛化不激活，保持旧行为（其他项目零影响）");
    }
}
