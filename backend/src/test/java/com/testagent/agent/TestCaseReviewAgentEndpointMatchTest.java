package com.testagent.agent;

import com.testagent.entity.TestCase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.8(R3): endpoint 匹配收紧单测——旧逻辑 token 相似度 0.65 + method 加分 0.2，
 * 编造的 CRUD 兄弟路径（2/3 token 相同 = 0.667 + 0.2 = 0.867）被"洗白"记为覆盖真实接口。
 * checklist 的 endpoint id 为 "METHOD /path" 格式（buildCoverageChecklist 约定）。
 */
class TestCaseReviewAgentEndpointMatchTest {

    private final TestCaseReviewAgent agent = new TestCaseReviewAgent();

    @Test
    void fabricatedSiblingPathNotWhitelisted() {
        // R3 核心回归：checklist 只有 cancel，用例引用编造的 delete → 不得匹配
        Map<String, Object> coverage = coverage(ep("POST", "/api/order/cancel"));
        Map<String, Object> refs = agent.mergeEndpointRefs(newRefs(),
                caseWithEndpoint("POST", "/api/order/delete"), coverage);
        assertTrue(((List<?>) refs.get("endpointIds")).isEmpty(), "编造的 delete 不应匹配 cancel");
        assertFalse(refs.containsKey("fuzzyEndpointIds"));
    }

    @Test
    void pathVariableNormalizedToExactMatch() {
        // {id} 与 :id 归一化为 * → 精确匹配，不算 fuzzy
        Map<String, Object> coverage = coverage(
                ep("POST", "/api/order/*"),
                ep("GET", "/api/order/{id}"));
        Map<String, Object> refs = agent.mergeEndpointRefs(newRefs(),
                caseWithEndpoint("POST", "/api/order/{id}"), coverage);
        assertEquals(List.of("POST /api/order/*"), refs.get("endpointIds"));
        assertFalse(refs.containsKey("fuzzyEndpointIds"), "归一化相等是精确匹配");
    }

    @Test
    void caseAndQueryAndTrailingSlashNormalized() {
        // 大小写/尾斜杠/query 差异归一化后精确匹配（返回 checklist 原始 id）
        Map<String, Object> coverage = coverage(ep("GET", "/api/User/List/"));
        Map<String, Object> refs = agent.mergeEndpointRefs(newRefs(),
                caseWithEndpoint("GET", "/api/user/list?pageSize=10"), coverage);
        assertEquals(List.of("GET /api/User/List/"), refs.get("endpointIds"));
    }

    @Test
    void methodMismatchNotMatched() {
        // 同路径不同 method：旧逻辑 0.8+0=0.8 匹配 ❌，新逻辑不匹配
        Map<String, Object> coverage = coverage(ep("GET", "/api/order/cancel"));
        Map<String, Object> refs = agent.mergeEndpointRefs(newRefs(),
                caseWithEndpoint("POST", "/api/order/cancel"), coverage);
        assertTrue(((List<?>) refs.get("endpointIds")).isEmpty(), "POST 用例不应匹配 GET 接口");
    }

    @Test
    void highSimilarityFuzzyMatchMarked() {
        // 10 token 中 9 个相同（相似度 0.9）且 token 数一致 → 模糊匹配 + fuzzyEndpointIds 记录
        Map<String, Object> coverage = coverage(ep("POST", "/api/a/b/c/d/e/f/g/h/i"));
        Map<String, Object> refs = agent.mergeEndpointRefs(newRefs(),
                caseWithEndpoint("POST", "/api/a/b/c/d/e/f/g/h/j"), coverage);
        assertEquals(List.of("POST /api/a/b/c/d/e/f/g/h/i"), refs.get("endpointIds"));
        assertEquals(List.of("POST /api/a/b/c/d/e/f/g/h/i"), refs.get("fuzzyEndpointIds"),
                "高门槛模糊命中应记录 fuzzyEndpointIds");
    }

    @Test
    void extraPathSegmentNotFuzzyMatched() {
        // token 数不一致（多一段）：相似度 10/11≈0.909 过阈值，但 token 数不同 → 不匹配
        Map<String, Object> coverage = coverage(ep("POST", "/api/a/b/c/d/e/f/g/h/i/j"));
        Map<String, Object> refs = agent.mergeEndpointRefs(newRefs(),
                caseWithEndpoint("POST", "/api/a/b/c/d/e/f/g/h/i"), coverage);
        assertTrue(((List<?>) refs.get("endpointIds")).isEmpty(), "多一段路径不应模糊匹配");
    }

    @Test
    void existingValidRefPreservedAndInvalidFiltered() {
        // 既有 refs 中合法 id 保留（validIds 校验路径），checklist 外的 id 被过滤
        Map<String, Object> coverage = coverage(ep("POST", "/api/order/cancel"));
        Map<String, Object> refs = newRefs();
        refs.put("endpointIds", List.of("POST /api/order/cancel", "GET /api/fake"));
        Map<String, Object> merged = agent.mergeEndpointRefs(refs,
                caseWithEndpoint("POST", "/api/order/cancel"), coverage);
        // GET /api/fake 不在 checklist → 被过滤；cancel 保留且去重
        assertEquals(List.of("POST /api/order/cancel"), merged.get("endpointIds"));
    }

    private Map<String, Object> newRefs() {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("requirementIds", List.of());
        refs.put("transitionIds", List.of());
        refs.put("endpointIds", List.of());
        refs.put("ruleIds", List.of());
        return refs;
    }

    private TestCase caseWithEndpoint(String method, String path) {
        TestCase tc = new TestCase();
        tc.setTitle("t");
        tc.setApiEndpoints("[{\"method\":\"" + method + "\",\"path\":\"" + path + "\"}]");
        return tc;
    }

    private Map<String, Object> ep(String method, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", method.toUpperCase() + " " + path);
        m.put("method", method);
        m.put("path", path);
        return m;
    }

    private Map<String, Object> coverage(Map<String, Object>... endpoints) {
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("endpoints", List.of(endpoints));
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("checklist", checklist);
        return coverage;
    }
}
