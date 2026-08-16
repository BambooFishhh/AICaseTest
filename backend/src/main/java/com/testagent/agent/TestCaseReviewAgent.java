package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;
import com.testagent.service.LlmService;
import com.testagent.service.AiReviewHistoryRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v5.12: AI 用例评审第一版。
 * 生成后先做规则兜底（补 coverageRefs、过滤不可执行用例），
 * 再调用 LLM 对每条用例做 pass/fix/reject 评审；评审结果写入 executionHints.aiReview。
 */
@Component
public class TestCaseReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(TestCaseReviewAgent.class);
    private static final int MAX_LLM_CASES = 60;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    @Autowired
    private AiReviewHistoryRecorder aiReviewHistoryRecorder;

    public List<TestCase> review(List<TestCase> cases, Map<String, Object> coverage) {
        return review(cases, coverage, "generation");
    }

    public List<TestCase> review(List<TestCase> cases, Map<String, Object> coverage, String source) {
        if (cases == null || cases.isEmpty()) {
            return cases;
        }
        List<TestCase> cleaned = ruleReview(cases, coverage);
        if (cleaned.size() <= MAX_LLM_CASES && !cleaned.isEmpty()) {
            try {
                cleaned = llmReview(cleaned, coverage, source);
            } catch (Exception e) {
                log.warn("LLM review failed, keep rule-reviewed cases: {}", e.getMessage());
            }
        } else if (cleaned.size() > MAX_LLM_CASES) {
            log.info("Skip LLM review for {} cases (limit {})", cleaned.size(), MAX_LLM_CASES);
        }
        cleaned = applyEndpointMatching(cleaned, coverage);
        return cleaned;
    }

    private List<TestCase> ruleReview(List<TestCase> cases, Map<String, Object> coverage) {
        List<TestCase> result = new ArrayList<>();
        for (TestCase tc : cases) {
            List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
            if (steps.isEmpty()) {
                log.warn("Review reject (no structuredSteps): {}", tc.getTitle());
                continue;
            }
            Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
            Map<String, Object> refs = readCoverageRefs(hints);
            if (isEmptyRefs(refs)) {
                refs = inferCoverageRefs(tc, coverage);
            }
            refs = mergeEndpointRefs(refs, tc, coverage);
            hints.put("coverageRefs", refs);
            tc.setExecutionHints(toJson(hints));
            result.add(tc);
        }
        return result;
    }

    private List<TestCase> llmReview(List<TestCase> cases, Map<String, Object> coverage, String source) throws Exception {
        List<Map<String, Object>> brief = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            TestCase tc = cases.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("title", tc.getTitle());
            item.put("module", tc.getModule());
            item.put("type", tc.getType());
            item.put("priority", tc.getPriority());
            item.put("structuredSteps", JsonHelper.parseListMap(tc.getStructuredSteps()));
            item.put("apiEndpoints", JsonHelper.parseListMap(tc.getApiEndpoints()));
            item.put("stateMachineRef", JsonHelper.parseMap(tc.getStateMachineRef()));
            item.put("coverageRefs", readCoverageRefs(JsonHelper.parseMap(tc.getExecutionHints())));
            brief.add(item);
        }

        String systemPrompt = """
                你是测试用例评审专家。逐条检查候选用例：
                - status：pass（通过）/ fix（需修正）/ reject（应删除）
                - issues：列出可执行性、覆盖率、预期可验证性、重复等具体问题
                - coverageRefs 只能引用 coverageChecklist 中真实存在的 id：
                  transitionIds 用 "from->to"；endpointIds 用 "METHOD /path"；ruleIds 用 "rule-N"；requirementIds 用 "req-N"
                - suggestedChanges：给出可自动采纳的修正（title/module/type/priority/coverageRefs），没有修正则填 null
                返回 JSON 数组，不要修改用例正文，不要输出其他文字：
                [{"index":0,"status":"fix","issues":["缺少 coverageRefs"],"confidence":0.8,"coverageRefs":{"requirementIds":[],"transitionIds":[],"endpointIds":[],"ruleIds":[]},"suggestedChanges":{"title":null,"module":null,"type":null,"priority":null,"coverageRefs":null}}]
                """;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("coverageChecklist", coverage == null ? Map.of() : coverage.get("checklist"));
        payload.put("coverageGaps", coverage == null ? Map.of() : coverage.get("gaps"));
        payload.put("cases", brief);
        String userPrompt = "评审输入：\n" + objectMapper.writeValueAsString(payload);

        String response = llmService.chat(systemPrompt, userPrompt, 0.2);
        JsonNode array = objectMapper.readTree(extractJsonArray(response));
        if (!array.isArray()) {
            return cases;
        }

        Map<Integer, JsonNode> byIndex = new LinkedHashMap<>();
        for (JsonNode node : array) {
            int idx = node.path("index").asInt(-1);
            if (idx >= 0) {
                byIndex.put(idx, node);
            }
        }

        Set<Integer> rejectIndices = new HashSet<>();
        for (Map.Entry<Integer, JsonNode> entry : byIndex.entrySet()) {
            if ("reject".equals(entry.getValue().path("status").asText())) {
                rejectIndices.add(entry.getKey());
            }
        }
        if (rejectIndices.size() > cases.size() / 2) {
            log.warn("LLM review rejected too many cases ({}), keep all to avoid data loss", rejectIndices.size());
            rejectIndices.clear();
        }

        List<TestCase> result = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            if (rejectIndices.contains(i)) {
                continue;
            }
            TestCase tc = cases.get(i);
            JsonNode reviewNode = byIndex.get(i);
            if (reviewNode != null) {
                applyReview(tc, reviewNode, source);
            }
            result.add(tc);
        }
        return result;
    }

    private void applyReview(TestCase tc, JsonNode reviewNode, String source) {
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        List<String> issues = new ArrayList<>();
        JsonNode issuesNode = reviewNode.path("issues");
        if (issuesNode.isArray()) {
            issuesNode.forEach(n -> issues.add(n.asText()));
        }
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", reviewNode.path("status").asText("fix"));
        review.put("issues", issues);
        review.put("confidence", reviewNode.path("confidence").asDouble(0.5));
        review.put("suggestedChanges", normalizeSuggestions(reviewNode.path("suggestedChanges")));
        hints.put("aiReview", review);

        JsonNode refsNode = reviewNode.path("coverageRefs");
        if (refsNode.isObject()) {
            try {
                Map<String, Object> refs = objectMapper.convertValue(refsNode, Map.class);
                Map<String, Object> existing = readCoverageRefs(hints);
                hints.put("coverageRefs", mergeCoverageRefs(existing, refs));
            } catch (Exception e) {
                log.warn("Failed to apply coverageRefs from review: {}", e.getMessage());
            }
        }
        tc.setExecutionHints(toJson(hints));
    }

    // v5.12: 生成链路在用例编号/项目归属确定后统一补记评审历史
    public void recordHistoryForCases(List<TestCase> cases, String source) {
        if (cases == null) {
            return;
        }
        for (TestCase tc : cases) {
            if (tc.getId() == null || tc.getProjectId() == null) {
                continue;
            }
            Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
            Object reviewObj = hints.get("aiReview");
            if (reviewObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> review = (Map<String, Object>) reviewObj;
                aiReviewHistoryRecorder.record(tc, review, readCoverageRefs(hints), source);
            }
        }
    }

    private Map<String, Object> readCoverageRefs(Map<String, Object> hints) {
        Object refs = hints.get("coverageRefs");
        if (refs instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) refs;
            return map;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> normalizeSuggestions(JsonNode suggestionsNode) {
        Map<String, Object> suggestions = new LinkedHashMap<>();
        suggestions.put("title", null);
        suggestions.put("module", null);
        suggestions.put("type", null);
        suggestions.put("priority", null);
        suggestions.put("coverageRefs", null);
        if (suggestionsNode != null && suggestionsNode.isObject()) {
            for (String key : suggestions.keySet()) {
                JsonNode value = suggestionsNode.get(key);
                if (value == null || value.isNull()) {
                    continue;
                }
                if (value.isValueNode()) {
                    suggestions.put(key, value.asText());
                } else if (value.isObject()) {
                    try {
                        suggestions.put(key, objectMapper.convertValue(value, Map.class));
                    } catch (Exception e) {
                        log.warn("Failed to parse suggested change {}: {}", key, e.getMessage());
                    }
                }
            }
        }
        return suggestions;
    }

    private Map<String, Object> mergeCoverageRefs(Map<String, Object> existing, Map<String, Object> review) {
        Map<String, Object> merged = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        for (String key : List.of("requirementIds", "transitionIds", "endpointIds", "ruleIds")) {
            Object value = review.get(key);
            if (value instanceof List && !((List<?>) value).isEmpty()) {
                merged.put(key, value);
            } else if (!merged.containsKey(key)) {
                merged.put(key, new ArrayList<String>());
            }
        }
        return merged;
    }

    private boolean isEmptyRefs(Map<String, Object> refs) {
        if (refs == null || refs.isEmpty()) return true;
        return refs.values().stream().allMatch(v ->
                v == null || (v instanceof List && ((List<?>) v).isEmpty()));
    }

    private Map<String, Object> inferCoverageRefs(TestCase tc, Map<String, Object> coverage) {
        Map<String, Object> refs = new LinkedHashMap<>();
        List<String> transitionIds = new ArrayList<>();
        Map<String, Object> smRef = JsonHelper.parseMap(tc.getStateMachineRef());
        Object transitionsObj = smRef.get("transitions");
        if (transitionsObj instanceof List) {
            for (Object item : (List<?>) transitionsObj) {
                if (item instanceof Map) {
                    Map<?, ?> t = (Map<?, ?>) item;
                    transitionIds.add(String.valueOf(t.get("from")) + "->" + String.valueOf(t.get("to")));
                }
            }
        }
        refs.put("requirementIds", new ArrayList<String>());
        refs.put("transitionIds", transitionIds);
        refs.put("endpointIds", new ArrayList<String>());
        refs.put("ruleIds", new ArrayList<String>());
        return mergeEndpointRefs(refs, tc, coverage);
    }

    private List<TestCase> applyEndpointMatching(List<TestCase> cases, Map<String, Object> coverage) {
        if (cases == null || coverage == null) {
            return cases;
        }
        for (TestCase tc : cases) {
            Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
            Map<String, Object> refs = readCoverageRefs(hints);
            refs = mergeEndpointRefs(refs, tc, coverage);
            hints.put("coverageRefs", refs);
            tc.setExecutionHints(toJson(hints));
        }
        return cases;
    }

    private Map<String, Object> mergeEndpointRefs(Map<String, Object> refs, TestCase tc,
                                                   Map<String, Object> coverage) {
        List<String> endpointIds = new ArrayList<>();
        Set<String> validIds = readValidEndpointIds(coverage);
        Object existing = refs.get("endpointIds");
        if (existing instanceof List) {
            for (Object item : (List<?>) existing) {
                if (item != null) {
                    String id = normalizeEndpointId(String.valueOf(item));
                    if (validIds.isEmpty() || validIds.contains(id)) {
                        if (!endpointIds.contains(id)) {
                            endpointIds.add(id);
                        }
                    }
                }
            }
        }
        for (Map<String, Object> ep : JsonHelper.parseListMap(tc.getApiEndpoints())) {
            String matchedId = matchEndpoint(ep, coverage);
            if (matchedId != null && !endpointIds.contains(matchedId)) {
                endpointIds.add(matchedId);
            }
        }
        refs.put("endpointIds", endpointIds);
        return refs;
    }

    private String matchEndpoint(Map<String, Object> ep, Map<String, Object> coverage) {
        String method = String.valueOf(ep.getOrDefault("method", "")).toUpperCase();
        String path = normalizePath(String.valueOf(ep.getOrDefault("path", "")));
        if (path.isBlank()) {
            return null;
        }
        List<Map<String, Object>> endpoints = readEndpoints(coverage);
        String bestId = null;
        double bestScore = 0.65;
        boolean bestMethodMatch = false;
        for (Map<String, Object> item : endpoints) {
            String itemId = String.valueOf(item.get("id"));
            String itemMethod = String.valueOf(item.getOrDefault("method", "")).toUpperCase();
            String itemPath = normalizePath(String.valueOf(item.getOrDefault("path", "")));
            if (itemPath.isBlank()) {
                continue;
            }
            double score = pathScore(path, itemPath);
            boolean methodMatch = !itemMethod.isBlank() && itemMethod.equals(method);
            if (methodMatch) {
                score += 0.2;
            }
            if (score > bestScore || (score == bestScore && methodMatch && !bestMethodMatch)) {
                bestScore = score;
                bestId = itemId;
                bestMethodMatch = methodMatch;
            }
        }
        return bestId;
    }

    private Set<String> readValidEndpointIds(Map<String, Object> coverage) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> ep : readEndpoints(coverage)) {
            String id = String.valueOf(ep.get("id"));
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String normalizeEndpointId(String id) {
        if (id == null) {
            return "";
        }
        int space = id.indexOf(' ');
        if (space > 0) {
            return id.substring(0, space).toUpperCase() + id.substring(space);
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readEndpoints(Map<String, Object> coverage) {
        Object checklistObj = coverage == null ? null : coverage.get("checklist");
        if (checklistObj instanceof Map) {
            Object endpoints = ((Map<String, Object>) checklistObj).get("endpoints");
            if (endpoints instanceof List) {
                return (List<Map<String, Object>>) endpoints;
            }
        }
        return List.of();
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.toLowerCase().split("\\?")[0].trim();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        p = p.replaceAll("\\{[^}]+}", "*").replaceAll(":[^/]+", "*");
        return p;
    }

    private double pathScore(String a, String b) {
        Set<String> sa = tokenSet(a);
        Set<String> sb = tokenSet(b);
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String token : sa) {
            if (sb.contains(token)) {
                inter++;
            }
        }
        return (double) inter / Math.max(sa.size(), sb.size());
    }

    private Set<String> tokenSet(String path) {
        Set<String> set = new HashSet<>();
        for (String part : path.split("[/\\-_]")) {
            if (!part.isBlank()) {
                set.add(part);
            }
        }
        return set;
    }

    private String extractJsonArray(String text) {
        if (text == null || text.isBlank()) {
            return "[]";
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        return "[]";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
