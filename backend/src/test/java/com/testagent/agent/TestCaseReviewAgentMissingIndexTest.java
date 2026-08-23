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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.10(R4): 评审输出截断缺 index 单测——旧实现 LLM 返回缺 index 的用例无结果且无告警（静默）。
 * 新规则：① 重复 index 保留首个（putIfAbsent）② 越界 index 丢弃 ③ 缺失子集二次送评
 * ④ 补评后仍缺 → warn 告警 + 保留用例（安全默认：未评审 ≠ 删除）。
 */
class TestCaseReviewAgentMissingIndexTest {

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

    private Map<String, Object> hints(TestCase tc) {
        return com.testagent.dto.JsonHelper.parseMap(tc.getExecutionHints());
    }

    @Test
    void missingIndexSubsetIsReReviewedInSecondRound() throws Exception {
        // 首轮只回 index=0（index=1 截断丢失）→ 补评子集第二轮回 index=1
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("[{\"index\":0,\"status\":\"pass\",\"confidence\":0.9,\"issues\":[]}]")
                .thenReturn("[{\"index\":0,\"status\":\"fix\",\"confidence\":0.9,\"issues\":[\"补评\"]}]");

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("A"), caseOf("B"))), Map.of(), "generation");

        assertEquals(2, result.size(), "两条用例都保留");
        assertEquals("pass", ((Map<?, ?>) hints(result.get(0)).get("aiReview")).get("status"));
        assertEquals("fix", ((Map<?, ?>) hints(result.get(1)).get("aiReview")).get("status"),
                "缺失 index 的用例应在第二轮补评中获得评审结果");
    }

    @Test
    void duplicateIndexKeepsFirstNode() throws Exception {
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("[{\"index\":0,\"status\":\"pass\",\"confidence\":0.9,\"issues\":[\"首个\"]},"
                        + "{\"index\":0,\"status\":\"reject\",\"confidence\":0.9,\"issues\":[\"重复\"]}]");

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("A"))), Map.of(), "generation");

        assertEquals(1, result.size());
        Map<?, ?> review = (Map<?, ?>) hints(result.get(0)).get("aiReview");
        assertEquals("pass", review.get("status"), "重复 index 应保留首个结果");
        assertEquals(List.of("首个"), review.get("issues"));
    }

    @Test
    void outOfRangeIndexIsDroppedSafely() throws Exception {
        // LLM 编造越界 index=5（仅 1 条用例）→ 丢弃不崩；补评轮同样返回越界 → 用例保留无 aiReview
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("[{\"index\":5,\"status\":\"reject\",\"confidence\":0.9,\"issues\":[]}]")
                .thenReturn("[{\"index\":5,\"status\":\"reject\",\"confidence\":0.9,\"issues\":[]}]");

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("A"))), Map.of(), "generation");

        assertEquals(1, result.size(), "越界 index 丢弃后用例保留（未评审 ≠ 删除）");
        assertNull(hints(result.get(0)).get("aiReview"), "无有效评审结果时不写 aiReview");
    }

    @Test
    void stillMissingAfterReReviewKeepsCaseWithoutRecord() throws Exception {
        // 首轮只回 index=0；补评子集（仅用例 B）返回空数组 → B 保留 + 无 aiReview（warn 告警路径）
        when(llmService.chat(anyString(), anyString(), anyDouble()))
                .thenReturn("[{\"index\":0,\"status\":\"pass\",\"confidence\":0.9,\"issues\":[]}]")
                .thenReturn("[]");

        List<TestCase> result = agent.llmReview(
                new ArrayList<>(List.of(caseOf("A"), caseOf("B"))), Map.of(), "generation");

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(tc -> "B".equals(tc.getTitle())),
                "补评后仍缺失的用例保留（安全默认）");
        assertNull(hints(result.get(1)).get("aiReview"));
    }
}
