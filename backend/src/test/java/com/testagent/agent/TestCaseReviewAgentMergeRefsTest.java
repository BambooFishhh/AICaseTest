package com.testagent.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.2(R2): mergeCoverageRefs 并集单测——旧实现"评审非空即整体替换"，
 * 生成阶段正确的 requirementIds 会被评审 LLM 的不完整返回覆盖丢失。
 */
class TestCaseReviewAgentMergeRefsTest {

    private final TestCaseReviewAgent agent = new TestCaseReviewAgent();

    @Test
    @SuppressWarnings("unchecked")
    void reviewRefsUnionWithExistingInsteadOfReplace() {
        // R2 核心回归：existing 有 req-1/req-2，评审只返回 req-3 → 并集三者，
        // 旧实现会把 req-1/req-2 整体替换掉
        Map<String, Object> existing = refs(List.of("req-1", "req-2"), List.of("A->B"));
        Map<String, Object> review = refs(List.of("req-3"), List.of("B->C"));

        Map<String, Object> merged = agent.mergeCoverageRefs(existing, review);

        List<String> requirementIds = (List<String>) merged.get("requirementIds");
        assertEquals(List.of("req-1", "req-2", "req-3"), requirementIds);
        assertEquals(List.of("A->B", "B->C"), merged.get("transitionIds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dedupAndOrderPreserved() {
        Map<String, Object> existing = refs(List.of("req-1", "req-2"), List.of());
        Map<String, Object> review = refs(List.of("req-2", "req-1", "req-3"), List.of());

        List<String> requirementIds = (List<String>) agent.mergeCoverageRefs(existing, review).get("requirementIds");

        // 去重且保序：existing 在前，review 新增在后
        assertEquals(List.of("req-1", "req-2", "req-3"), requirementIds);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyReviewDoesNotClearExisting() {
        Map<String, Object> existing = refs(List.of("req-1"), List.of("A->B"));
        Map<String, Object> review = refs(List.of(), List.of());

        Map<String, Object> merged = agent.mergeCoverageRefs(existing, review);

        assertEquals(List.of("req-1"), merged.get("requirementIds"));
        assertEquals(List.of("A->B"), merged.get("transitionIds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nullSafetyAndBlankFiltered() {
        Map<String, Object> merged = agent.mergeCoverageRefs(null, null);

        // null 入参兜底：四类 key 均初始化为空列表
        for (String key : List.of("requirementIds", "transitionIds", "endpointIds", "ruleIds")) {
            assertTrue(((List<String>) merged.get(key)).isEmpty(), key + " 应为空列表");
        }
    }

    private Map<String, Object> refs(List<String> requirementIds, List<String> transitionIds) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("requirementIds", requirementIds);
        refs.put("transitionIds", transitionIds);
        refs.put("endpointIds", List.of());
        refs.put("ruleIds", List.of());
        return refs;
    }
}
