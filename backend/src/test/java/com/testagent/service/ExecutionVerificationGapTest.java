package com.testagent.service;

import com.testagent.entity.ExecutionStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.3(④): 伪通过治理测试。
 * 实证锚点：361af6dd"未选中时点击删除提示错误"——8 步 skipped + 末步仅点击 passed
 * 即挂 passed 徽章，而标题承诺的"提示错误"验证从未发生（与 cb5304a1 同语义一过一败）。
 */
class ExecutionVerificationGapTest {

    private ExecutionStep step(String strategy, String result) {
        return ExecutionStep.builder()
                .id("s" + System.nanoTime())
                .executionId("e1")
                .stepIndex(1)
                .action("a")
                .strategy(strategy)
                .result(result)
                .build();
    }

    @Test
    void legacyStatusRulesUnchanged() {
        assertEquals("failed", ExecutionService.determineStatus(1, 1, 5, true));
        assertEquals("failed", ExecutionService.determineStatus(1, 1, 5, false));
        assertEquals("skipped", ExecutionService.determineStatus(0, 0, 10, false));
        assertEquals("passed", ExecutionService.determineStatus(3, 0, 1, false));
    }

    @Test
    void verificationGapDemotesPassedToSkipped() {
        // 核心回归：8 skipped + 1 passed（非断言步）+ 标题含验证语义 → 不再伪通过
        assertEquals("skipped", ExecutionService.determineStatus(1, 0, 8, true));
        assertEquals("passed", ExecutionService.determineStatus(1, 0, 8, false));
    }

    @Test
    void gapDetectedForRealCaseShape() {
        List<ExecutionStep> steps = List.of(
                step("agent", "skipped"), step("agent", "skipped"),
                step("assert", "skipped"),          // "确认没有勾选任何复选框" → UI 层暂无法验证
                step("dom_click", "passed"));       // "点击删除选中"成功
        assertTrue(ExecutionService.isVerificationGap("浏览足迹管理-未选中时点击删除提示错误", steps),
                "标题承诺'提示错误'验证但断言步零 passed → 验证缺口");
    }

    @Test
    void noGapWhenTitleLacksVerificationSemantics() {
        List<ExecutionStep> steps = List.of(
                step("assert", "skipped"), step("dom_click", "passed"));
        assertFalse(ExecutionService.isVerificationGap("商品详情页-添加收藏成功", steps),
                "标题无验证语义 → 不启用降级");
    }

    @Test
    void noGapWhenAssertActuallyPassed() {
        List<ExecutionStep> steps = List.of(
                step("assert", "passed"), step("dom_click", "passed"));
        assertFalse(ExecutionService.isVerificationGap("未选中时点击删除提示错误", steps),
                "断言真实通过 → 无缺口");
    }

    @Test
    void gapDetectedWhenNoAssertStepAtAll() {
        // 生成侧缺陷：标题说"提示错误"但步骤链根本没有断言步骤
        List<ExecutionStep> steps = List.of(
                step("dom_click", "passed"), step("navigate", "passed"));
        assertTrue(ExecutionService.isVerificationGap("未选中时点击删除提示错误", steps));
    }

    @Test
    void llmJudgePassedAssertCountsAsPassed() {
        // ③ 联动：strategy=assert+llm_judge 且 passed 视为断言真实通过，不降级
        List<ExecutionStep> steps = List.of(
                step("assert+llm_judge", "passed"), step("dom_click", "passed"));
        assertFalse(ExecutionService.isVerificationGap("取消收藏提示验证", steps));
    }
}
