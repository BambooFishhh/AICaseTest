package com.testagent.agent;

import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * v9.2: module 归一——用例 module 与候选（PRD modules/前端路由页面名）互相包含时
 * 收敛到候选原名；匹配不上保留原值（不虚构）。
 */
class TestGeneratorAgentModuleNormalizeTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private PrdAnalysisResult prdWith(String... moduleNames) {
        PrdAnalysisResult prd = new PrdAnalysisResult();
        prd.setModules(java.util.Arrays.stream(moduleNames)
                .map(n -> Map.<String, Object>of("name", n)).toList());
        return prd;
    }

    private FrontendResult routesWith(String... routeNames) {
        FrontendResult fr = new FrontendResult();
        fr.setRoutes(java.util.Arrays.stream(routeNames)
                .map(n -> Map.<String, Object>of("name", n, "path", "/" + n)).toList());
        return fr;
    }

    private TestCase caseWithModule(String module) {
        TestCase tc = new TestCase();
        tc.setTitle("t");
        tc.setModule(module);
        return tc;
    }

    @Test
    void variantNamesCollapseToCandidateOriginal() {
        // 同一页面的多种 LLM 命名（我的收藏页/会员中心聚合）归一到候选原名
        List<TestCase> cases = List.of(
                caseWithModule("我的收藏页"),
                caseWithModule("会员中心聚合"),
                caseWithModule("我的收藏"));
        agent.normalizeModules(cases, prdWith("会员中心"), routesWith("我的收藏", "浏览足迹"));

        assertEquals("我的收藏", cases.get(0).getModule());
        assertEquals("会员中心", cases.get(1).getModule());
        assertEquals("我的收藏", cases.get(2).getModule());
    }

    @Test
    void unmatchedModuleIsKeptAsIs() {
        // 候选全覆盖不到时保留原值（不虚构模块名）
        List<TestCase> cases = List.of(caseWithModule("前端页面"));
        agent.normalizeModules(cases, prdWith("会员中心"), routesWith("我的收藏"));

        assertEquals("前端页面", cases.get(0).getModule());
    }

    @Test
    void inventedModuleFallsBackToTitlePageName() {
        // v9.2: 自创 module 匹配不上候选，但 title 含真实页面名（"我的收藏页-XXX" ⊃ "我的收藏"）→ 归一
        TestCase tc = caseWithModule("前端页面");
        tc.setTitle("我的收藏页-加载并点击商品跳转详情");
        agent.normalizeModules(List.of(tc), prdWith("会员中心"), routesWith("我的收藏", "浏览足迹"));

        assertEquals("我的收藏", tc.getModule());
    }

    @Test
    void titleFallbackDoesNotFireWhenTitleHasNoCandidate() {
        TestCase tc = caseWithModule("前端页面");
        tc.setTitle("批量导出功能验证");
        agent.normalizeModules(List.of(tc), prdWith("会员中心"), routesWith("我的收藏"));

        assertEquals("前端页面", tc.getModule());
    }

    @Test
    void longestCandidateWins() {
        // 候选互相包含时取最具体的（最长）匹配
        List<TestCase> cases = List.of(caseWithModule("我的收藏页列表"));
        agent.normalizeModules(cases, prdWith("我的"), routesWith("我的收藏"));

        assertEquals("我的收藏", cases.get(0).getModule());
    }

    @Test
    void emptyCandidatesAreNoop() {
        List<TestCase> cases = List.of(caseWithModule("我的收藏页"));
        agent.normalizeModules(cases, new PrdAnalysisResult(), new FrontendResult());

        assertEquals("我的收藏页", cases.get(0).getModule());
    }

    @Test
    void stemVoteMergesSuffixVariantsToMajority() {
        // v9.2: 剥后缀同词干（浏览足迹管理/浏览足迹页 → 浏览足迹）→ 归一到批内多数派
        List<TestCase> cases = List.of(
                caseWithModule("浏览足迹管理"),
                caseWithModule("浏览足迹管理"),
                caseWithModule("浏览足迹页"),
                caseWithModule("我的收藏"),
                caseWithModule("我的收藏"),
                caseWithModule("我的收藏页"));
        agent.normalizeModules(cases, new PrdAnalysisResult(), new FrontendResult());

        assertEquals("浏览足迹管理", cases.get(0).getModule());
        assertEquals("浏览足迹管理", cases.get(1).getModule());
        assertEquals("浏览足迹管理", cases.get(2).getModule());
        assertEquals("我的收藏", cases.get(3).getModule());
        assertEquals("我的收藏", cases.get(4).getModule());
        assertEquals("我的收藏", cases.get(5).getModule());
    }

    @Test
    void distinctStemsAreNotMerged() {
        // 词干不同的模块（收藏状态查询 / 会员中心聚合）互不合并
        List<TestCase> cases = List.of(
                caseWithModule("收藏状态查询"),
                caseWithModule("会员中心聚合"));
        agent.normalizeModules(cases, new PrdAnalysisResult(), new FrontendResult());

        assertEquals("收藏状态查询", cases.get(0).getModule());
        assertEquals("会员中心聚合", cases.get(1).getModule());
    }

    @Test
    void routeSelectorInjectedForPathTargets() {
        // v9.2: target 呈路由形态的 ui_action 注入 route 选择器；元素点击与 input 不受影响
        com.testagent.entity.TestCase tc = new com.testagent.entity.TestCase();
        tc.setTitle("t");
        tc.setStructuredSteps("[{\"order\":1,\"type\":\"ui_action\",\"action\":\"打开\",\"target\":\"/collect\"},"
                + "{\"order\":2,\"type\":\"ui_action\",\"action\":\"点击\",\"target\":\"登录按钮\"},"
                + "{\"order\":3,\"type\":\"input\",\"action\":\"输入\",\"target\":\"/login\",\"inputValue\":\"x\"},"
                + "{\"order\":4,\"type\":\"ui_action\",\"action\":\"已有选择器\",\"target\":\"/footprint\","
                + "\"uiSelector\":{\"type\":\"css\",\"value\":\".fp\"}}]");
        agent.injectRouteSelectors(List.of(tc));

        List<Map<String, Object>> steps = com.testagent.dto.JsonHelper.parseListMap(tc.getStructuredSteps());
        Map<?, ?> navSelector = (Map<?, ?>) steps.get(0).get("uiSelector");
        assertEquals("route", navSelector.get("type"));
        assertEquals("/collect", navSelector.get("value"));
        assertFalse(steps.get(1).containsKey("uiSelector"));
        assertFalse(steps.get(2).containsKey("uiSelector"));
        assertEquals("css", ((Map<?, ?>) steps.get(3).get("uiSelector")).get("type"));
    }
}
