package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.10(G13): 置信度派生单测——旧实现 confidence 硬编码 0.8 无信息量，
 * 新实现 confidence = qualityScore / 100（评分公式 v7.8 G6 已含评审结论，
 * confidence 与 qualityScore 同源同刻才有信息量）。
 */
class TestGeneratorAgentConfidenceTest {

    private final TestGeneratorAgent agent = new TestGeneratorAgent();

    private TestCase fullFormCase(String hintsJson) {
        // 对齐 TestGeneratorAgentQualityScoreTest 的满分构造（形式分 6 项全满）
        TestCase tc = new TestCase();
        tc.setTitle("标题");
        tc.setStructuredSteps("[{\"action\":\"click\",\"target\":\"#btn\",\"expected\":\"ok\"},"
                + "{\"action\":\"input\",\"target\":\"#name\",\"expected\":\"filled\"}]");
        tc.setApiEndpoints("[{\"method\":\"POST\",\"path\":\"/api/x\"}]");
        tc.setTestData("{\"user\":\"admin\"}");
        tc.setExpectedResults("[\"显示成功提示\"]");
        tc.setExecutionHints(hintsJson);
        return tc;
    }

    @Test
    void confidenceEqualsQualityScoreOver100() {
        TestCase pass = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.9}}");
        TestCase none = fullFormCase("{\"approach\":\"a\"}");
        agent.calculateQualityScores(List.of(pass, none));

        assertEquals(agent.calculateQualityScore(pass) / 100.0, pass.getConfidence(), 1e-9,
                "confidence 必须等于 qualityScore/100");
        assertEquals(agent.calculateQualityScore(none) / 100.0, none.getConfidence(), 1e-9,
                "confidence 必须等于 qualityScore/100");
    }

    @Test
    void highQualityScoresHigherConfidence() {
        TestCase pass = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.9}}");
        // 多 issue + 多条 UI 语言违规 → 低分
        TestCase poor = fullFormCase("{\"approach\":\"a\",\"aiReview\":{\"status\":\"fix\",\"confidence\":0.9,"
                + "\"issues\":[\"i1\",\"i2\",\"i3\",\"i4\",\"i5\",\"i6\"],"
                + "\"suggestedChanges\":{\"title\":\"x\",\"module\":\"y\",\"priority\":\"P0\",\"type\":null,"
                + "\"coverageRefs\":null},\"autoApplied\":[]},"
                + "\"uiLanguageViolations\":[\"v1\",\"v2\",\"v3\"]}");
        agent.calculateQualityScores(List.of(pass, poor));

        assertTrue(pass.getConfidence() > poor.getConfidence(),
                "高分用例置信度应高于低分用例（pass=" + pass.getConfidence()
                        + " poor=" + poor.getConfidence() + "）");
    }

    @Test
    void fullFormPassCaseConfidenceIsOne() {
        TestCase pass = fullFormCase("{\"approach\":\"登录后操作\",\"aiReview\":{\"status\":\"pass\",\"confidence\":0.9}}");
        agent.calculateQualityScores(List.of(pass));

        assertEquals(100, pass.getQualityScore());
        assertEquals(1.0, pass.getConfidence(), 1e-9, "满分用例 confidence 应为 1.0");
    }

    @Test
    void confidenceNoLongerHardcodedPointEight() {
        // 无评审中性分 85 → confidence 0.85，证明不再是恒定 0.8
        TestCase none = fullFormCase("{\"approach\":\"a\"}");
        agent.calculateQualityScores(List.of(none));

        assertEquals(85, none.getQualityScore());
        assertEquals(0.85, none.getConfidence(), 1e-9);
    }
}
