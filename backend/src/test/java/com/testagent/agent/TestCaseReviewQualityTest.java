package com.testagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v9.6: 评审层静态质量检查——语义重复 + 预期与动作一致性。
 */
class TestCaseReviewQualityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void semanticDuplicatesAreFlaggedAcrossTitles() throws Exception {
        TestCase a = caseWith("我的收藏", "positive", "取消收藏后总数减少",
                List.of(
                        Map.of("type", "ui_action", "action", "进入【我的收藏】页面", "target", "/collect"),
                        Map.of("type", "state_assert", "action", "查看总数", "target", "总数",
                                "expected", "页面显示'共 N 件收藏'"),
                        Map.of("type", "ui_action", "action", "点击商品的删除按钮", "target", "删除按钮"),
                        Map.of("type", "state_assert", "action", "验证总数", "target", "总数",
                                "expected", "页面显示'共 N 件收藏'")));
        TestCase b = caseWith("我的收藏", "positive", "取消收藏后总数正确更新",
                List.of(
                        Map.of("type", "ui_action", "action", "进入【我的收藏】页面", "target", "/collect"),
                        Map.of("type", "state_assert", "action", "查看总数", "target", "总数",
                                "expected", "页面显示'共 N 件收藏'"),
                        Map.of("type", "ui_action", "action", "点击商品的删除按钮", "target", "删除按钮"),
                        Map.of("type", "state_assert", "action", "验证总数", "target", "总数",
                                "expected", "页面显示'共 N 件收藏'")));

        Map<String, List<String>> issues = ReviewQualityChecker.semanticDuplicates(List.of(a, b));

        assertTrue(issues.containsKey("取消收藏后总数减少"));
        assertTrue(issues.containsKey("取消收藏后总数正确更新"));
        assertTrue(issues.get("取消收藏后总数减少").get(0).contains("语义重复"));
    }

    @Test
    void differentBehaviorsAreNotFlagged() throws Exception {
        TestCase collect = caseWith("我的收藏", "positive", "加载收藏列表",
                List.of(Map.of("type", "ui_action", "action", "进入我的收藏页", "target", "/collect")));
        TestCase footprint = caseWith("我的收藏", "positive", "加载足迹列表",
                List.of(Map.of("type", "ui_action", "action", "进入浏览足迹页", "target", "/footprint")));

        Map<String, List<String>> issues = ReviewQualityChecker.semanticDuplicates(List.of(collect, footprint));

        assertTrue(issues.isEmpty(), "不同路由/不同行为不得误报语义重复");
    }

    @Test
    void deleteActionWithTitleOnlyExpectedIsFlagged() throws Exception {
        TestCase tc = caseWith("浏览足迹管理", "positive", "删除选中",
                List.of(
                        Map.of("type", "ui_action", "action", "点击【删除选中】按钮",
                                "target", "删除选中按钮", "expected", "页面显示'浏览足迹'标题"),
                        Map.of("type", "state_assert", "action", "验证删除结果", "target", "列表",
                                "expected", "页面显示'浏览足迹'标题")));

        List<String> issues = ReviewQualityChecker.expectedActionConsistency(tc);

        assertEquals(2, issues.size());
        assertTrue(issues.stream().anyMatch(v -> v.contains("不匹配")));
        assertTrue(issues.stream().anyMatch(v -> v.contains("未验证删除")));
    }

    @Test
    void genericJumpWithoutAnchorIsFlagged() throws Exception {
        TestCase tc = caseWith("会员中心聚合", "positive", "入口跳转",
                List.of(Map.of("type", "ui_action", "action", "点击【我的收藏】入口",
                        "target", "我的收藏入口", "expected", "触发页面跳转")));

        List<String> issues = ReviewQualityChecker.expectedActionConsistency(tc);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).contains("泛化表述"));
    }

    @Test
    void jumpWithTargetPageIsNotFlagged() throws Exception {
        TestCase tc = caseWith("会员中心聚合", "positive", "入口跳转",
                List.of(Map.of("type", "ui_action", "action", "点击【我的收藏】入口",
                        "target", "我的收藏入口", "expected", "页面跳转至'我的收藏'页")));

        List<String> issues = ReviewQualityChecker.expectedActionConsistency(tc);

        assertTrue(issues.isEmpty(), "带目标页引号锚点的跳转断言不得误报");
    }

    @Test
    void gestureStepIsDetectedAsViolation() throws Exception {
        TestCase swipe = caseWith("我的收藏", "positive", "左滑取消收藏",
                List.of(Map.of("type", "ui_action", "action", "对目标商品执行左滑操作",
                        "target", "商品项左滑后出现的删除按钮")));
        TestCase normal = caseWith("我的收藏", "positive", "详情页取消收藏",
                List.of(Map.of("type", "ui_action", "action", "点击商品详情页的收藏按钮",
                        "target", "收藏按钮")));

        assertTrue(ReviewQualityChecker.hasGestureViolation(swipe), "左滑步骤必须判违规");
        assertTrue(!ReviewQualityChecker.hasGestureViolation(normal), "普通点击不得误判");
    }

    private TestCase caseWith(String module, String type, String title,
                              List<Map<String, Object>> steps) throws Exception {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        tc.setModule(module);
        tc.setType(type);
        tc.setStructuredSteps(objectMapper.writeValueAsString(steps));
        return tc;
    }
}
