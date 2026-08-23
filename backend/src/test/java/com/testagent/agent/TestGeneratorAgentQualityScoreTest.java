package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.8(G6): 质量评分并入评审结论单测——旧实现是纯"形式分"（字段填没填），
 * LLM 编造字段填满 = 高分，去重"保留高分者"时编造越全越容易挤掉真实用例。
 * 新公式：形式分 × 0.7 + 评审分（pass 30 / fix 扣减 / 无评审 15 中性）- UI 语言违规扣分。
 */
class TestGeneratorAgentQualityScoreTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    @Test
    void fullFormPassReviewScores100() {
        TestCase tc = fullFormCase("{\"approach\":\"登录后操作\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.9}}");
        assertEquals(100, agent.calculateQualityScore(tc));
    }

    @Test
    void fullFormNoReviewScoresNeutral85() {
        // 形式满分 100×0.7=70 + 无评审中性 15 = 85（LLM 评审跳过/降级时不奖不罚）
        TestCase tc = fullFormCase("{\"approach\":\"登录后操作\"}");
        assertEquals(85, agent.calculateQualityScore(tc));
    }

    @Test
    void passScoresHigherThanFixWhichScoresHigherThanNoReview() {
        TestCase pass = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.9}}");
        TestCase fix = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"fix\",\"confidence\":0.9,"
                + "\"issues\":[\"i1\",\"i2\"],\"suggestedChanges\":{\"title\":\"x\",\"module\":null,\"type\":null,"
                + "\"priority\":null,\"coverageRefs\":null},\"autoApplied\":[]}}");
        TestCase none = fullFormCase("{\"approach\":\"a\"}");

        int passScore = agent.calculateQualityScore(pass);
        int fixScore = agent.calculateQualityScore(fix);
        int noneScore = agent.calculateQualityScore(none);

        // pass=100（70+30）；fix：2 issues×5 + 1 未采纳建议×5 → 评审分 15 → 85；none：中性 15 → 85
        assertTrue(passScore > fixScore, "pass 应高于 fix");
        assertEquals(noneScore, fixScore, "轻量 fix 与无评审打平（扣减未过中性线）");
    }

    @Test
    void manyIssuesFallBelowNeutral() {
        // 4 issues + 2 未采纳建议 = 30-30 = 0 评审分 → 70 < 中性 85
        TestCase heavyFix = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"fix\",\"confidence\":0.9,"
                + "\"issues\":[\"i1\",\"i2\",\"i3\",\"i4\"],\"suggestedChanges\":{\"title\":\"x\",\"module\":\"y\","
                + "\"type\":null,\"priority\":null,\"coverageRefs\":null},\"autoApplied\":[]}}");
        assertEquals(70, agent.calculateQualityScore(heavyFix));
    }

    @Test
    void lowConfidenceReviewScoreHalved() {
        // pass + confidence 0.4 → 评审分 15 → 70+15=85（与无评审打平而非 100）
        TestCase tc = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.4}}");
        assertEquals(85, agent.calculateQualityScore(tc));
    }

    @Test
    void autoAppliedSuggestionsNotCountedAsDebt() {
        // 2 条建议均已自动采纳 → 不扣分；1 issue → 评审分 25 → 70+25=95
        TestCase tc = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"fix\",\"confidence\":0.9,"
                + "\"issues\":[\"i1\"],\"suggestedChanges\":{\"title\":\"x\",\"priority\":\"P0\",\"type\":null,"
                + "\"module\":null,\"coverageRefs\":null},\"autoApplied\":[\"title\",\"priority\"]}}");
        assertEquals(95, agent.calculateQualityScore(tc));
    }

    @Test
    void uiLanguageViolationsPenaltyCapped() {
        TestCase two = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.9},"
                + "\"uiLanguageViolations\":[\"v1\",\"v2\"]}");
        assertEquals(94, agent.calculateQualityScore(two), "2 项违规扣 6 分");

        TestCase five = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.9},"
                + "\"uiLanguageViolations\":[\"v1\",\"v2\",\"v3\",\"v4\",\"v5\"]}");
        assertEquals(91, agent.calculateQualityScore(five), "5 项违规扣分封顶 9");
    }

    @Test
    void emptyCaseNeverNegative() {
        TestCase empty = new TestCase();
        assertTrue(agent.calculateQualityScore(empty) >= 0);
    }

    @Test
    void legacyCaseWithoutReviewDoesNotThrow() {
        // 历史数据：hints 无 aiReview（v5.12 前生成）→ 形式分×0.7 + 中性 15，不抛异常
        TestCase legacy = fullFormCase("{\"approach\":\"a\"}");
        assertEquals(85, agent.calculateQualityScore(legacy));
    }

    @Test
    void nullHintsCaseScoresFormOnly() {
        // hints 为默认 "{}"：无 approach（形式分 85）也无评审 → 85×7/10=59 + 中性 15 = 74
        TestCase noHints = fullFormCase(null);
        assertEquals(74, agent.calculateQualityScore(noHints));
    }

    /** 形式满分用例：6 项形式检查全过（30+20+15+15+10+10=100） */
    private TestCase fullFormCase(String hintsJson) {
        TestCase tc = new TestCase();
        tc.setTitle("t");
        tc.setStructuredSteps("[{\"action\":\"click\",\"target\":\"#btn\",\"expected\":\"ok\"},"
                + "{\"action\":\"input\",\"target\":\"#name\",\"expected\":\"filled\"}]");
        tc.setApiEndpoints("[{\"method\":\"POST\",\"path\":\"/api/x\"}]");
        tc.setTestData("{\"user\":\"admin\"}");
        tc.setExpectedResults("[\"显示成功提示\"]");
        if (hintsJson != null) {
            tc.setExecutionHints(hintsJson);
        }
        return tc;
    }
}
