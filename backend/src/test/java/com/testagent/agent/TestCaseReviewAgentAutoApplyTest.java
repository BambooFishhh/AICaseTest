package com.testagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.8(R1): 评审建议分级采纳单测——旧实现 suggestedChanges 只存标签从未应用，
 * "fix"与"pass"唯一区别是 hints.aiReview 里的 status。
 * 新规则：confidence ≥ 0.8 时 coverageRefs（并集）/priority（枚举校验）自动采纳并登记 autoApplied。
 */
class TestCaseReviewAgentAutoApplyTest {

    private final TestCaseReviewAgent agent = new TestCaseReviewAgent();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void highConfidenceRefsAndPriorityApplied() throws Exception {
        TestCase tc = baseCase();
        agent.applyReview(tc, objectMapper.readTree("""
                {"status":"fix","issues":["缺少 coverageRefs"],"confidence":0.85,
                 "suggestedChanges":{"title":null,"module":null,"type":null,"priority":"P0",
                   "coverageRefs":{"requirementIds":["req-9"],"transitionIds":[],"endpointIds":[],"ruleIds":[]}}}
                """), "generation");

        assertEquals("P0", tc.getPriority(), "高置信 priority 建议应自动采纳");
        Map<String, Object> hints = parse(tc.getExecutionHints());
        Map<String, Object> refs = (Map<String, Object>) hints.get("coverageRefs");
        assertTrue(((List<?>) refs.get("requirementIds")).contains("req-1"), "既有 req-1 保留（并集）");
        assertTrue(((List<?>) refs.get("requirementIds")).contains("req-9"), "建议 req-9 并入");
        Map<String, Object> review = (Map<String, Object>) hints.get("aiReview");
        assertEquals(List.of("coverageRefs", "priority"), review.get("autoApplied"));
    }

    @Test
    void lowConfidenceNotApplied() throws Exception {
        TestCase tc = baseCase();
        agent.applyReview(tc, objectMapper.readTree("""
                {"status":"fix","issues":["x"],"confidence":0.6,
                 "suggestedChanges":{"priority":"P0",
                   "coverageRefs":{"requirementIds":["req-9"],"transitionIds":[],"endpointIds":[],"ruleIds":[]}}}
                """), "generation");

        assertEquals("P2", tc.getPriority(), "低置信不应采纳 priority");
        Map<String, Object> hints = parse(tc.getExecutionHints());
        Map<String, Object> refs = (Map<String, Object>) hints.get("coverageRefs");
        assertEquals(List.of("req-1"), refs.get("requirementIds"), "低置信不应并入建议 refs");
        Map<String, Object> review = (Map<String, Object>) hints.get("aiReview");
        assertTrue(((List<?>) review.get("autoApplied")).isEmpty(), "autoApplied 应为空");
    }

    @Test
    void invalidPriorityNotApplied() throws Exception {
        TestCase tc = baseCase();
        agent.applyReview(tc, objectMapper.readTree("""
                {"status":"fix","issues":["x"],"confidence":0.9,
                 "suggestedChanges":{"priority":"P9"}}
                """), "generation");

        assertEquals("P2", tc.getPriority(), "P9 非法枚举不应采纳");
        Map<String, Object> review = (Map<String, Object>) parse(tc.getExecutionHints()).get("aiReview");
        assertTrue(((List<?>) review.get("autoApplied")).isEmpty());
    }

    @Test
    void emptyRefsSuggestionSkipped() throws Exception {
        TestCase tc = baseCase();
        agent.applyReview(tc, objectMapper.readTree("""
                {"status":"fix","issues":["x"],"confidence":0.9,
                 "suggestedChanges":{"coverageRefs":{"requirementIds":[],"transitionIds":[],"endpointIds":[],"ruleIds":[]}}}
                """), "generation");

        Map<String, Object> hints = parse(tc.getExecutionHints());
        Map<String, Object> review = (Map<String, Object>) hints.get("aiReview");
        assertTrue(((List<?>) review.get("autoApplied")).isEmpty(), "空 refs 建议是 no-op 不登记");
        Map<String, Object> refs = (Map<String, Object>) hints.get("coverageRefs");
        assertEquals(List.of("req-1"), refs.get("requirementIds"), "空建议不改变既有 refs");
    }

    @Test
    void reviewCoverageRefsMergedWithAppliedSuggestions() throws Exception {
        // 评审节点顶层 coverageRefs 与 suggestedChanges.coverageRefs 双通道都并入（并集语义）
        TestCase tc = baseCase();
        agent.applyReview(tc, objectMapper.readTree("""
                {"status":"fix","issues":[],"confidence":0.9,
                 "coverageRefs":{"requirementIds":["req-top"],"transitionIds":["A->B"],"endpointIds":[],"ruleIds":[]},
                 "suggestedChanges":{"coverageRefs":{"requirementIds":["req-sug"],"transitionIds":[],"endpointIds":[],"ruleIds":[]}}}
                """), "generation");

        Map<String, Object> refs = (Map<String, Object>) parse(tc.getExecutionHints()).get("coverageRefs");
        List<?> requirementIds = (List<?>) refs.get("requirementIds");
        assertTrue(requirementIds.containsAll(List.of("req-1", "req-sug", "req-top")));
        assertTrue(((List<?>) refs.get("transitionIds")).contains("A->B"));
    }

    private TestCase baseCase() {
        TestCase tc = new TestCase();
        tc.setTitle("t");
        tc.setPriority("P2");
        tc.setExecutionHints("{\"coverageRefs\":{\"requirementIds\":[\"req-1\"],"
                + "\"transitionIds\":[],\"endpointIds\":[],\"ruleIds\":[]}}");
        return tc;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
