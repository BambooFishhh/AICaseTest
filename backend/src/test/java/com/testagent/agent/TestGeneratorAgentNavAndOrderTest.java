package com.testagent.agent;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.9: 生成侧两小修单测（batch-284817535bc94b76 八条失败归因的生成侧项）。
 *
 * ① splitAssertNavigationSteps：LLM 把"进入【我的收藏】页面"写成 state_assert + route
 *    uiSelector（实测 TC-1262 s9——执行器只断言不导航，页面停在 /goods 断言必败），
 *    确定性拆为 ui_action 导航 + state_assert 断言两步；
 * ② sortCasesByModule 状态冲突排序：同模块内变更类（删除/取消收藏）排到只读类之后
 *   （实测 TC-1262 先取消收藏清空数据，1263/1265/1277 级联跑在空数据上）。
 */
class TestGeneratorAgentNavAndOrderTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private TestCase caseOf(String title, String stepsJson) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        tc.setModule("收藏");
        tc.setStructuredSteps(stepsJson);
        return tc;
    }

    private static String navStep(String action, String route) {
        return "{\"order\":1,\"phase\":\"verify\",\"action\":\"" + action + "\",\"target\":\"" + route
                + "\",\"expected\":\"页面正常\",\"data\":{},\"type\":\"ui_action\",\"uiSelector\":{\"type\":\"route\",\"value\":\""
                + route + "\"}}";
    }

    // ---- ① state_assert 型导航步拆分 ----

    /** 实测 TC-1262 形态：state_assert + route uiSelector → 拆为导航+断言 */
    @Test
    void assertNavStepWithRouteSelectorIsSplit() {
        String steps = "[{\"order\":1,\"phase\":\"verify\",\"action\":\"点击底部操作栏的【已收藏】按钮\","
                + "\"target\":\"已收藏按钮\",\"expected\":\"提示已取消收藏\",\"data\":{},\"type\":\"ui_action\"},"
                + "{\"order\":2,\"phase\":\"verify\",\"action\":\"进入【我的收藏】页面\",\"target\":\"/collect\","
                + "\"expected\":\"页面显示'我的收藏'，列表不再包含商品'蔓越莓曲奇'\",\"data\":{},\"type\":\"state_assert\","
                + "\"uiSelector\":{\"type\":\"route\",\"value\":\"/collect\"}}]";
        List<TestCase> cases = new ArrayList<>(List.of(caseOf("取消收藏", steps)));
        agent.splitAssertNavigationSteps(cases);

        String out = cases.get(0).getStructuredSteps();
        List<Map<String, Object>> parsed = JsonHelper.parseListMap(out);
        assertEquals(3, parsed.size(), "1 步应拆为 2 步（原第 1 步保留）");
        assertEquals("ui_action", parsed.get(1).get("type"), "第 2 步应变为导航");
        assertEquals("/collect", ((Map<?, ?>) parsed.get(1).get("uiSelector")).get("value"));
        assertEquals("state_assert", parsed.get(2).get("type"), "第 3 步保留断言");
        assertNull(parsed.get(2).get("uiSelector"), "断言步的 route 选择器应剔除");
        assertTrue(String.valueOf(parsed.get(2).get("expected")).contains("不再包含"),
                "断言语义应原样保留");
        assertEquals(1, parsed.get(0).get("order"));
        assertEquals(3, parsed.get(2).get("order"), "order 应重排");
    }

    /** 无 uiSelector 但 target 是路由 + 导航话术 → 同样拆分 */
    @Test
    void assertNavStepWithRouteTargetIsSplit() {
        String steps = "[{\"order\":1,\"action\":\"跳转到浏览足迹页面\",\"target\":\"/footprint\","
                + "\"expected\":\"显示足迹列表\",\"data\":{},\"type\":\"state_assert\"}]";
        List<TestCase> cases = new ArrayList<>(List.of(caseOf("跳转足迹", steps)));
        agent.splitAssertNavigationSteps(cases);

        List<Map<String, Object>> parsed = JsonHelper.parseListMap(cases.get(0).getStructuredSteps());
        assertEquals(2, parsed.size());
        assertEquals("ui_action", parsed.get(0).get("type"));
        assertEquals("/footprint", parsed.get(0).get("target"));
    }

    /** 回归：纯内容断言（无导航话术）不拆；ui_action 进入页面本来就是导航也不动 */
    @Test
    void plainAssertAndUiActionNavUntouched() {
        String steps = "[{\"order\":1,\"action\":\"进入【我的收藏】页面\",\"target\":\"/collect\","
                + "\"expected\":\"页面正常\",\"data\":{},\"type\":\"ui_action\",\"uiSelector\":{\"type\":\"route\",\"value\":\"/collect\"}},"
                + "{\"order\":2,\"action\":\"验证页面显示收藏列表\",\"target\":\"页面\",\"expected\":\"共 N 件收藏\",\"data\":{},\"type\":\"state_assert\"}]";
        List<TestCase> cases = new ArrayList<>(List.of(caseOf("看列表", steps)));
        agent.splitAssertNavigationSteps(cases);

        List<Map<String, Object>> parsed = JsonHelper.parseListMap(cases.get(0).getStructuredSteps());
        assertEquals(2, parsed.size(), "两步均不应被拆动");
        assertEquals("ui_action", parsed.get(0).get("type"));
        assertEquals("state_assert", parsed.get(1).get("type"));
    }

    // ---- ② 状态冲突排序 ----

    /** 同模块内变更类排到只读类之后；模块间顺序不变 */
    @Test
    void mutatingCasesSortAfterReadOnlyWithinModule() {
        TestCase readList = caseOf("我的收藏页-正确显示收藏列表", "[]");
        TestCase readJump = caseOf("点击商品跳转详情页", "[]");
        TestCase unfav = caseOf("商品详情页-取消收藏成功", "[{\"action\":\"点击【已收藏】\",\"type\":\"ui_action\"}]");

        List<TestCase> cases = new ArrayList<>(List.of(unfav, readList, readJump));
        agent.sortCasesByModule(cases);

        assertEquals("我的收藏页-正确显示收藏列表", cases.get(0).getTitle(), "只读类在前");
        assertEquals("点击商品跳转详情页", cases.get(1).getTitle());
        assertEquals("商品详情页-取消收藏成功", cases.get(2).getTitle(), "变更类（取消收藏）排最后");
    }

    /** 只读用例的期望里出现"不再包含"等词不误判为变更类 */
    @Test
    void readOnlyCaseMentioningDeleteNotMisclassified() {
        TestCase readOnly = caseOf("列表展示验证", "[{\"action\":\"查看列表\",\"expected\":\"删除按钮存在\"}]");
        TestCase mutating = caseOf("批量删除足迹", "[{\"action\":\"点击删除选中\",\"type\":\"ui_action\"}]");

        List<TestCase> cases = new ArrayList<>(List.of(readOnly, mutating));
        agent.sortCasesByModule(cases);

        assertEquals("列表展示验证", cases.get(0).getTitle());
        assertEquals("批量删除足迹", cases.get(1).getTitle());
    }
}
