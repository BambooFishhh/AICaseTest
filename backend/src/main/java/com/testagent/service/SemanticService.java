package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.TestCaseDTO;
import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseRepository;
import com.testagent.service.MilvusService.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * v5.4: 语义检索服务——embedding + Milvus，支撑语义去重、RAG、语义搜索与失败经验库。
 */
@Service
public class SemanticService {

    private static final Logger log = LoggerFactory.getLogger(SemanticService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private TestCaseRepository testCaseRepository;

    public boolean isAvailable() {
        return milvusService.isEnabled() && embeddingService.isConfigured();
    }

    public void indexCase(String projectId, TestCase tc) {
        if (!isAvailable() || tc == null) {
            return;
        }
        String text = buildCaseText(tc);
        List<Float> vector = embeddingService.embed(text);
        milvusService.insert(MilvusService.COLLECTION_CASES, projectId, tc.getId(),
                tc.getTitle(), tc.getModule(), text, vector);
    }

    public void indexCases(String projectId, List<TestCase> cases) {
        if (cases == null) {
            return;
        }
        for (TestCase tc : cases) {
            indexCase(projectId, tc);
        }
    }

    // v5.6: 重新生成/重建用例时只清 cases，保留 contexts/failures
    public void clearCases(String projectId) {
        if (!milvusService.isEnabled()) {
            return;
        }
        milvusService.deleteByProject(MilvusService.COLLECTION_CASES, projectId);
    }

    // v5.6: 项目删除时清理三集合
    public void clearProject(String projectId) {
        clearCases(projectId);
        milvusService.deleteByProject(MilvusService.COLLECTION_CONTEXTS, projectId);
        milvusService.deleteByProject(MilvusService.COLLECTION_FAILURES, projectId);
        milvusService.deleteByProject(MilvusService.COLLECTION_COMPONENTS, projectId);
    }

    // v5.6: 删除指定用例向量
    public void removeCases(String projectId, List<String> ids) {
        if (!milvusService.isEnabled() || ids == null || ids.isEmpty()) {
            return;
        }
        milvusService.deleteByIds(MilvusService.COLLECTION_CASES, projectId, ids);
    }

    // v5.6: 编辑用例后重建向量（先删旧 id 再写入）
    public void reindexCase(String projectId, TestCase tc) {
        if (!isAvailable() || tc == null) {
            return;
        }
        removeCases(projectId, List.of(tc.getId()));
        indexCase(projectId, tc);
    }

    // v5.6: 上下文按模块替换（先删旧模块向量再写入）
    public void replaceContext(String projectId, String module, String text) {
        if (!milvusService.isEnabled()) {
            return;
        }
        milvusService.deleteByModule(MilvusService.COLLECTION_CONTEXTS, projectId, module);
        indexContext(projectId, module, text);
    }

    public boolean isDuplicate(String projectId, TestCase tc) {
        if (!isAvailable() || tc == null) {
            return false;
        }
        String text = (tc.getTitle() == null ? "" : tc.getTitle())
                + " " + (tc.getModule() == null ? "" : tc.getModule());
        List<Float> vector = embeddingService.embed(text);
        if (vector.isEmpty()) {
            return false;
        }
        List<SearchHit> hits = milvusService.search(MilvusService.COLLECTION_CASES, projectId, vector, 1);
        return !hits.isEmpty() && hits.get(0).score() >= milvusService.duplicateThreshold();
    }

    public List<TestCaseDTO> searchCases(String projectId, String query) {
        if (!isAvailable() || query == null || query.isBlank()) {
            return List.of();
        }
        List<Float> vector = embeddingService.embed(query);
        if (vector.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = milvusService.search(MilvusService.COLLECTION_CASES, projectId, vector, 20);
        List<TestCaseDTO> result = new ArrayList<>();
        for (SearchHit hit : hits) {
            testCaseRepository.findById(hit.id()).ifPresent(tc -> result.add(TestCaseDTO.from(tc)));
        }
        return result;
    }

    public void indexContext(String projectId, String module, String text) {
        if (!isAvailable() || text == null || text.isBlank()) {
            return;
        }
        String limited = text.length() > 8000 ? text.substring(0, 8000) : text;
        List<Float> vector = embeddingService.embed(limited);
        String id = "ctx-" + UUID.randomUUID().toString().substring(0, 8);
        String title = limited.length() > 100 ? limited.substring(0, 100) : limited;
        milvusService.insert(MilvusService.COLLECTION_CONTEXTS, projectId, id, title, module, limited, vector);
    }

    public List<String> retrieveContexts(String projectId, String query, int topK) {
        return retrieveContexts(projectId, query == null ? List.of() : List.of(query), topK);
    }

    // v6.1: 按多个查询段（整段 PRD + 各 module/requirement）分别检索上下文，
    // 按命中 id 保留最高分去重后返回 topK。避免整段 PRD 揉成一个向量带来的噪声。
    public List<String> retrieveContexts(String projectId, List<String> queries, int topK) {
        if (!isAvailable() || queries == null || queries.isEmpty() || topK <= 0) {
            return List.of();
        }
        Map<String, SearchHit> best = new LinkedHashMap<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            String limited = query.length() > 2000 ? query.substring(0, 2000) : query;
            List<Float> vector = embeddingService.embed(limited);
            if (vector.isEmpty()) {
                continue;
            }
            for (SearchHit hit : milvusService.search(MilvusService.COLLECTION_CONTEXTS,
                    projectId, vector, topK)) {
                if (hit.id() == null || hit.id().isBlank() || hit.text() == null || hit.text().isBlank()) {
                    continue;
                }
                SearchHit existing = best.get(hit.id());
                if (existing == null || hit.score() > existing.score()) {
                    best.put(hit.id(), hit);
                }
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .map(SearchHit::text)
                .toList();
    }

    // v6.1 (前端 Agentic RAG): 全量替换组件语义索引（先删同项目旧向量再写入）。
    public void replaceComponents(String projectId, FrontendResult frontendResult) {
        if (!isAvailable() || frontendResult == null || frontendResult.getComponentSummaries() == null) {
            return;
        }
        milvusService.deleteByProject(MilvusService.COLLECTION_COMPONENTS, projectId);
        for (Map<String, Object> comp : frontendResult.getComponentSummaries()) {
            indexComponent(projectId, comp);
        }
        log.info("Indexed {} frontend components for project {}", frontendResult.getComponentSummaries().size(), projectId);
    }

    private void indexComponent(String projectId, Map<String, Object> comp) {
        if (comp == null) {
            return;
        }
        try {
            String id = comp.get("id") == null ? "comp-" + UUID.randomUUID().toString().substring(0, 8)
                    : String.valueOf(comp.get("id"));
            String title = comp.get("component") == null ? id : String.valueOf(comp.get("component"));
            String json = objectMapper.writeValueAsString(comp);
            if (json.length() > 8000) {
                json = json.substring(0, 8000);
            }
            String embedText = buildComponentEmbedText(comp);
            List<Float> vector = embeddingService.embed(embedText);
            if (vector.isEmpty()) {
                return;
            }
            milvusService.insert(MilvusService.COLLECTION_COMPONENTS, projectId, id, title,
                    "component", json, vector);
        } catch (Exception e) {
            log.warn("Frontend component index failed: {}", e.getMessage());
        }
    }

    private String buildComponentEmbedText(Map<String, Object> comp) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, comp.get("component"));
        appendIfPresent(sb, comp.get("route"));
        appendIfPresent(sb, comp.get("summary"));
        appendListIfPresent(sb, comp.get("interactions"));
        appendListIfPresent(sb, comp.get("apiCalls"));
        appendListIfPresent(sb, comp.get("stateOps"));
        appendListIfPresent(sb, comp.get("routeNavigations"));
        appendListIfPresent(sb, comp.get("keywords"));
        return sb.toString().trim();
    }

    public List<Map<String, Object>> retrieveComponents(String projectId, String query, int topK) {
        return retrieveComponents(projectId, query == null ? List.of() : List.of(query), topK);
    }

    // v6.1: 按多个查询段分别混合检索组件，按组件 id 保留最高 relevance 去重后返回 topK。
    public List<Map<String, Object>> retrieveComponents(String projectId, List<String> queries, int topK) {
        if (!isAvailable() || queries == null || queries.isEmpty() || topK <= 0) {
            return List.of();
        }
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            for (Map<String, Object> comp : scoreComponentsQuery(projectId, query, topK)) {
                String id = String.valueOf(comp.getOrDefault("id", ""));
                if (id.isBlank()) {
                    continue;
                }
                Map<String, Object> existing = best.get(id);
                double rel = numberScore(comp.get("relevance"));
                if (existing == null || rel > numberScore(existing.get("relevance"))) {
                    best.put(id, comp);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> numberScore(m.get("relevance"))).reversed());
        return out.size() > topK ? out.subList(0, topK) : out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scoreComponentsQuery(String projectId, String query, int topK) {
        String limited = query.length() > 2000 ? query.substring(0, 2000) : query;
        List<Float> vector = embeddingService.embed(limited);
        if (vector.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = milvusService.search(MilvusService.COLLECTION_COMPONENTS, projectId, vector,
                topK * 3);
        Set<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> scored = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (hit.text() == null || hit.text().isBlank()) {
                continue;
            }
            Map<String, Object> comp;
            try {
                comp = objectMapper.readValue(hit.text(), Map.class);
            } catch (Exception e) {
                comp = new LinkedHashMap<>();
                comp.put("component", hit.title());
                comp.put("summary", hit.text());
            }
            double cosine = Math.max(0.0, hit.score());
            double keyword = keywordScore(comp, queryTerms);
            double business = comp.get("businessScore") instanceof Number n ? n.doubleValue() : 0.0;
            double combined = 0.6 * cosine + 0.35 * keyword + 0.05 * business;
            comp.put("relevance", Math.round(combined * 100.0) / 100.0);
            scored.add(comp);
        }
        scored.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> numberScore(m.get("relevance"))).reversed());
        return scored;
    }

    private double numberScore(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private double keywordScore(Map<String, Object> comp, Set<String> queryTerms) {
        Object kwObj = comp.get("keywords");
        Set<String> kws = new LinkedHashSet<>();
        if (kwObj instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    kws.add(String.valueOf(o).toLowerCase(Locale.ROOT));
                }
            }
        }
        String hay = (String.valueOf(comp.getOrDefault("component", "")) + " "
                + String.valueOf(comp.getOrDefault("file", ""))).toLowerCase(Locale.ROOT);
        int overlap = 0;
        for (String term : queryTerms) {
            if (kws.contains(term) || hay.contains(term)) {
                overlap++;
            }
        }
        return (double) overlap / queryTerms.size();
    }

    private Set<String> tokenize(String text) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> stop = Set.of("the", "and", "for", "with", "page", "view", "component",
                "interface", "api", "测试", "用例", "功能", "页面");
        for (String part : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            String p = part.trim();
            if (p.length() >= 2 && !stop.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private void appendIfPresent(StringBuilder sb, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            sb.append(String.valueOf(value)).append(' ');
        }
    }

    @SuppressWarnings("unchecked")
    private void appendListIfPresent(StringBuilder sb, Object value) {
        if (value instanceof List<?> list) {
            for (Object o : list) {
                appendIfPresent(sb, o);
            }
        }
    }

    public void recordFailure(String projectId, String executionId, String action, String error) {
        if (!isAvailable()) {
            return;
        }
        String text = (action == null ? "" : action) + " -> " + (error == null ? "" : error);
        List<Float> vector = embeddingService.embed(text);
        milvusService.insert(MilvusService.COLLECTION_FAILURES, projectId,
                executionId == null ? "exec-" + UUID.randomUUID() : executionId,
                action, "failure", text, vector);
    }

    public List<String> searchFailures(String projectId, String error, int topK) {
        if (!isAvailable() || error == null || error.isBlank()) {
            return List.of();
        }
        List<Float> vector = embeddingService.embed(error);
        if (vector.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = milvusService.search(MilvusService.COLLECTION_FAILURES, projectId, vector, topK);
        return hits.stream().map(SearchHit::text)
                .filter(s -> s != null && !s.isBlank())
                .toList();
    }

    private String buildCaseText(TestCase tc) {
        StringBuilder sb = new StringBuilder();
        if (tc.getTitle() != null) sb.append(tc.getTitle()).append(' ');
        if (tc.getModule() != null) sb.append(tc.getModule()).append(' ');
        if (tc.getSteps() != null) sb.append(tc.getSteps()).append(' ');
        if (tc.getExpectedResults() != null) sb.append(tc.getExpectedResults()).append(' ');
        return sb.toString().trim();
    }
}
