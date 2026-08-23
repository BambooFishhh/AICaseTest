package com.testagent.agent;

import com.testagent.entity.TestCase;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.10(R5): reject 保护三分带单测——旧实现"超半数全保留"盲区：恰好 51% 真垃圾也全留。
 * 新规则：>70% 全保留+告警（可疑）；40%–70% 灰区按置信度逐条裁决（≥0.75 才删，缺省 0.5 保守保留）；≤40% 照删。
 */
class TestCaseReviewAgentRejectBandTest {

    private TestCaseReviewAgent agent;
    private LlmService llmService;

    @BeforeEach
    void setUp() {
        agent = new TestCaseReviewAgent();
        llmService = mock(LlmService.class);
        ReflectionTestUtils.setField(agent, "llmService", llmService);
        ReflectionTestUtils.setField(agent, "promptSkillLoader", new PromptSkillLoader());
    }

    private TestCase caseOf(String title) {
        TestCase tc = new TestCase();
        tc.setTitle(title);
        tc.setModule("M");
        tc.setType("positive");
        tc.setPriority("P1");
        tc.setStructuredSteps("[{\"order\":1,\"type\":\"navigate\",\"action\":\"打开页面\"}]");
        tc.setExecutionHints("{}");
        return tc;
    }

    /** 构造评审响应：每条用例一个节点，reject 指定 index 与 confidence */
    private String reviewJson(int total, List<Integer> rejectIdx, double rejectConfidence) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                sb.append(",");
            }
            if (rejectIdx.contains(i)) {
                sb.append("{\"index\":").append(i).append(",\"status\":\"reject\",\"confidence\":")
                        .append(rejectConfidence).append(",\"issues\":[]}");
            } else {
                sb.append("{\"index\":").append(i).append(",\"status\":\"pass\",\"confidence\":0.9,\"issues\":[]}");
            }
        }
        return sb.append("]").toString();
    }

    private List<String> titles(List<TestCase> cases) {
        return cases.stream().map(TestCase::getTitle).collect(Collectors.toList());
    }

    @Test
    void over70PercentRejectKeepsAll() throws Exception {
        // 10 条中 8 条 reject（80% > 70%）→ 判定可疑，全保留
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(reviewJson(10, List.of(0, 1, 2, 3, 4, 5, 6, 7), 0.9));

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(
                        caseOf("c0"), caseOf("c1"), caseOf("c2"), caseOf("c3"), caseOf("c4"),
                        caseOf("c5"), caseOf("c6"), caseOf("c7"), caseOf("c8"), caseOf("c9"))),
                Map.of(), "generation");

        assertEquals(10, result.size(), "reject 超 70% 判可疑，应全保留防数据丢失");
    }

    @Test
    void greyZoneLowConfidenceRejectIsKept() throws Exception {
        // 5 条中 3 条 reject（60% 灰区）且 confidence 0.6 < 0.75 → 保守保留
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(reviewJson(5, List.of(0, 1, 2), 0.6));

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("c0"), caseOf("c1"), caseOf("c2"), caseOf("c3"), caseOf("c4"))),
                Map.of(), "generation");

        assertEquals(5, result.size(), "灰区低置信 reject 应保守保留");
        assertFalse(titles(result).isEmpty());
    }

    @Test
    void greyZoneHighConfidenceRejectIsDeleted() throws Exception {
        // 5 条中 3 条 reject（60% 灰区）且 confidence 0.9 ≥ 0.75 → 照删
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(reviewJson(5, List.of(0, 1, 2), 0.9));

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("c0"), caseOf("c1"), caseOf("c2"), caseOf("c3"), caseOf("c4"))),
                Map.of(), "generation");

        assertEquals(List.of("c3", "c4"), titles(result),
                "灰区高置信 reject 应删除");
    }

    @Test
    void greyZonePerCaseVerdictMixed() throws Exception {
        // 5 条中 3 条 reject（60% 灰区）：c0 置信 0.9（删）、c1 置信 0.5（留）、c2 置信 0.8（删）
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("[" +
                        "{\"index\":0,\"status\":\"reject\",\"confidence\":0.9,\"issues\":[]}," +
                        "{\"index\":1,\"status\":\"reject\",\"confidence\":0.5,\"issues\":[]}," +
                        "{\"index\":2,\"status\":\"reject\",\"confidence\":0.8,\"issues\":[]}," +
                        "{\"index\":3,\"status\":\"pass\",\"confidence\":0.9,\"issues\":[]}," +
                        "{\"index\":4,\"status\":\"pass\",\"confidence\":0.9,\"issues\":[]}]");

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("c0"), caseOf("c1"), caseOf("c2"), caseOf("c3"), caseOf("c4"))),
                Map.of(), "generation");

        assertEquals(List.of("c1", "c3", "c4"), titles(result),
                "灰区应逐条裁决：高置信删（c0/c2）、低置信留（c1）");
    }

    @Test
    void below40PercentRejectDeletedOutright() throws Exception {
        // 5 条中 2 条 reject（40%，非灰区）→ 照删
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn(reviewJson(5, List.of(0, 1), 0.6));

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("c0"), caseOf("c1"), caseOf("c2"), caseOf("c3"), caseOf("c4"))),
                Map.of(), "generation");

        assertEquals(List.of("c2", "c3", "c4"), titles(result),
                "≤40% reject 即使低置信也照删（少量 reject 视为正常淘汰）");
    }
}
