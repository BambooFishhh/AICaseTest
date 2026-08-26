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
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * v5.4: 语义检索服务——embedding + Milvus，支撑语义去重、RAG、语义搜索与失败经验库。
 */
@Service
public class SemanticService {

    private static final Logger log = LoggerFactory.getLogger(SemanticService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    // v7.x: 需求上下文按模块（prd/context/supplementary）记录指纹，支持模块级增量重建
    private final Map<String, Map<String, String>> requirementModuleFingerprints = new ConcurrentHashMap<>();
    private static final String MODULE_PRD = "prd";
    private static final String MODULE_CONTEXT = "context";
    private static final String MODULE_SUPPLEMENTARY = "supplementary";

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private TestCaseRepository testCaseRepository;

    // v8.6.1(9.4): 幽灵召回计数器（DB 已删但向量残留仍被召回的次数）——v8.7 指标接入预留
    private final java.util.concurrent.atomic.AtomicLong ghostRecallCount = new java.util.concurrent.atomic.AtomicLong();

    public long getGhostRecallCount() {
        return ghostRecallCount.get();
    }

    // v8.7.1(9.5.3): 指标门面——no-op 兜底
    private com.testagent.observability.MetricsFacade metrics = new com.testagent.observability.MetricsFacade();

    @Autowired(required = false)
    void setMetrics(com.testagent.observability.MetricsFacade metrics) {
        this.metrics = metrics;
    }

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        // v8.7.1: 启动零值预注册
        metrics.registerCounter("rag_recall_count");
        metrics.registerCounter("rag_empty_recall_total");
        metrics.registerTimer("rag_latency_seconds");
    }

    @Value("${app.rag.chunk-size:900}")
    private int chunkSize;

    @Value("${app.rag.chunk-overlap:150}")
    private int chunkOverlap;

    @Value("${app.rag.rrf-k:60}")
    private double rrfK;

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
        requirementModuleFingerprints.remove(projectId);
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

    // v6.4: 需求类上下文统一切片重建（PRD/上下文文档/补充需求），按 module 先清旧再写入。
    // 强制全量重建（保存文档等明确变更场景），重建后记录各模块指纹。
    public void replaceRequirementContexts(String projectId, List<Map<String, Object>> prdDocs,
                                           List<Map<String, Object>> contextDocs, String supplementary) {
        if (!milvusService.isEnabled()) {
            return;
        }
        reindexPrdModule(projectId, prdDocs);
        reindexContextModule(projectId, contextDocs);
        reindexSupplementaryModule(projectId, supplementary);
        storeModuleFingerprints(projectId, prdDocs, contextDocs, supplementary);
    }

    // v6.4: 生成前按需重建。索引内容未变化时跳过，避免每次生成都重复 embedding。
    // 增量优化：仅重建内容变化的模块——补充需求单独修改时不再连带重建 PRD/上下文文档向量。
    public void ensureRequirementContexts(String projectId, List<Map<String, Object>> prdDocs,
                                          List<Map<String, Object>> contextDocs, String supplementary) {
        if (!milvusService.isEnabled()) {
            return;
        }
        Map<String, String> stored = requirementModuleFingerprints
                .computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
        String prdFp = docsFingerprint(prdDocs);
        String contextFp = docsFingerprint(contextDocs);
        String suppFp = supplementary == null ? "" : supplementary;

        if (!prdFp.equals(stored.get(MODULE_PRD))) {
            reindexPrdModule(projectId, prdDocs);
            stored.put(MODULE_PRD, prdFp);
        }
        if (!contextFp.equals(stored.get(MODULE_CONTEXT))) {
            reindexContextModule(projectId, contextDocs);
            stored.put(MODULE_CONTEXT, contextFp);
        }
        if (!suppFp.equals(stored.get(MODULE_SUPPLEMENTARY))) {
            reindexSupplementaryModule(projectId, supplementary);
            stored.put(MODULE_SUPPLEMENTARY, suppFp);
        }
    }

    private void reindexPrdModule(String projectId, List<Map<String, Object>> prdDocs) {
        milvusService.deleteByModule(MilvusService.COLLECTION_CONTEXTS, projectId, MODULE_PRD);
        if (prdDocs != null) {
            for (Map<String, Object> doc : prdDocs) {
                indexDocChunks(projectId, MODULE_PRD, "PRD", doc);
            }
        }
    }

    private void reindexContextModule(String projectId, List<Map<String, Object>> contextDocs) {
        milvusService.deleteByModule(MilvusService.COLLECTION_CONTEXTS, projectId, MODULE_CONTEXT);
        if (contextDocs != null) {
            for (Map<String, Object> doc : contextDocs) {
                indexDocChunks(projectId, MODULE_CONTEXT, "上下文", doc);
            }
        }
    }

    private void reindexSupplementaryModule(String projectId, String supplementary) {
        milvusService.deleteByModule(MilvusService.COLLECTION_CONTEXTS, projectId, MODULE_SUPPLEMENTARY);
        if (supplementary != null && !supplementary.isBlank()) {
            indexTextChunks(projectId, MODULE_SUPPLEMENTARY, "supplementary", "补充需求", supplementary);
        }
    }

    private void storeModuleFingerprints(String projectId, List<Map<String, Object>> prdDocs,
                                         List<Map<String, Object>> contextDocs, String supplementary) {
        Map<String, String> stored = requirementModuleFingerprints
                .computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
        stored.put(MODULE_PRD, docsFingerprint(prdDocs));
        stored.put(MODULE_CONTEXT, docsFingerprint(contextDocs));
        stored.put(MODULE_SUPPLEMENTARY, supplementary == null ? "" : supplementary);
    }

    private String docsFingerprint(List<Map<String, Object>> docs) {
        try {
            return objectMapper.writeValueAsString(docs == null ? List.of() : docs);
        } catch (Exception e) {
            return String.valueOf(docs);
        }
    }

    private void indexDocChunks(String projectId, String module, String sourceLabel, Map<String, Object> doc) {
        if (doc == null) {
            return;
        }
        Object contentObj = doc.get("content");
        if (!(contentObj instanceof String content) || content.isBlank()) {
            return;
        }
        String docId = stringValue(doc.get("id"), "doc");
        String docTitle = stringValue(doc.get("title"), "未命名文档");
        String idPrefix = module + "-" + sanitizeId(docId);
        List<RagTextChunker.Chunk> chunks = RagTextChunker.chunk(content, chunkSize, chunkOverlap);
        for (int i = 0; i < chunks.size(); i++) {
            RagTextChunker.Chunk c = chunks.get(i);
            String chunkTitle = sourceLabel + "｜" + docTitle
                    + (c.title() == null || c.title().isBlank() ? "" : "｜" + c.title());
            insertContextChunk(projectId, module, idPrefix + "-" + (i + 1), chunkTitle, c.text());
        }
    }

    private void indexTextChunks(String projectId, String module, String idPrefix, String sourceLabel, String text) {
        List<RagTextChunker.Chunk> chunks = RagTextChunker.chunk(text, chunkSize, chunkOverlap);
        int n = 0;
        for (RagTextChunker.Chunk c : chunks) {
            n++;
            String chunkTitle = sourceLabel
                    + (c.title() == null || c.title().isBlank() ? "" : "｜" + c.title());
            insertContextChunk(projectId, module, sanitizeId(idPrefix) + "-" + n, chunkTitle, c.text());
        }
    }

    private void insertContextChunk(String projectId, String module, String id, String title, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        List<Float> vector = embeddingService.embed(text);
        if (vector.isEmpty()) {
            return;
        }
        milvusService.insert(MilvusService.COLLECTION_CONTEXTS, projectId, id, title, module, text, vector);
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String sanitizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "doc";
        }
        String s = raw.replaceAll("[^\\p{L}\\p{N}_-]", "-");
        return s.length() > 80 ? s.substring(0, 80) : s;
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
        if (hits.isEmpty()) {
            return false;
        }
        SearchHit top = hits.get(0);
        if (top.score() < milvusService.duplicateThreshold()) {
            return false;
        }
        // v8.6.1(9.4): 存在性兜底——幽灵向量（DB 已删但向量残留）不再误杀新用例
        if (!testCaseRepository.existsById(top.id())) {
            ghostRecallCount.incrementAndGet();
            log.warn("语义去重命中幽灵向量，已放行 (id={}, score={})", top.id(), top.score());
            return false;
        }
        return true;
    }

    /**
     * v7.1(G14): 全量生成批内语义去重——同类型且标题+模块语义高度相似的用例只保留一条。
     * 与 {@link #isDuplicate(String, TestCase)}（查已落库的 Milvus 向量）不同，本方法用于
     * 落库前的生成批内去重（此前仅追加生成有语义去重能力，全量生成同语义不同标题的重复用例会全部落库）。
     * 相似的一组中保留 qualityScore 更高者；Milvus/embedding 未配置或向量失败时原样返回，不阻塞生成。
     */
    public List<TestCase> deduplicateBatch(List<TestCase> cases) {
        if (cases == null || cases.size() < 2 || !isAvailable()) {
            return cases;
        }
        // v8.4fix: 全批向量并行预计算（并发上限 4）替代逐条串行调用——
        // 60 条用例由 N 次串行 HTTP 往返降到约 N/4，生成链路可省数十秒
        List<List<Float>> vectors = embedAllParallel(cases);
        List<TestCase> kept = new ArrayList<>();
        List<List<Float>> keptVectors = new ArrayList<>();
        for (int idx = 0; idx < cases.size(); idx++) {
            TestCase tc = cases.get(idx);
            List<Float> vec = vectors.get(idx);
            boolean dup = false;
            for (int i = 0; i < kept.size(); i++) {
                TestCase existing = kept.get(i);
                // v7.1(G1): 语义去重同样要求类型一致，避免"正向/逆向"同语义不同类型被误删
                if (existing.getType() == null || !existing.getType().equals(tc.getType())) {
                    continue;
                }
                if (vec.isEmpty() || keptVectors.get(i).isEmpty()) {
                    continue;
                }
                if (cosineSimilarity(vec, keptVectors.get(i)) >= milvusService.duplicateThreshold()) {
                    dup = true;
                    if (qualityScoreOf(tc) > qualityScoreOf(existing)) {
                        kept.set(i, tc);
                        keptVectors.set(i, vec);
                    }
                    break;
                }
            }
            if (!dup) {
                kept.add(tc);
                keptVectors.add(vec);
            }
        }
        int dropped = cases.size() - kept.size();
        if (dropped > 0) {
            log.info("Batch semantic dedup dropped {} of {} cases", dropped, cases.size());
        }
        return kept;
    }

    // v8.4fix: 并行 embedding 预计算；单条失败降级为空向量（该条不参与语义判重，由结构判重兑底）
    @SuppressWarnings("unchecked")
    private List<List<Float>> embedAllParallel(List<TestCase> cases) {
        List<Float>[] arr = new List[cases.size()];
        java.util.Arrays.fill(arr, List.<Float>of());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(4, cases.size()), r -> {
                    Thread t = new Thread(r, "dedup-embed");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < cases.size(); i++) {
                final int idx = i;
                futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        arr[idx] = embeddingService.embed(buildCaseText(cases.get(idx)));
                    } catch (Exception e) {
                        log.warn("dedup embedding 失败，第 {} 条降级为空向量: {}", idx, e.getMessage());
                    }
                }, pool));
            }
            java.util.concurrent.CompletableFuture.allOf(
                    futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
        return java.util.Arrays.asList(arr);
    }

    private int qualityScoreOf(TestCase tc) {
        return tc != null && tc.getQualityScore() != null ? tc.getQualityScore() : 0;
    }

    private float cosineSimilarity(List<Float> a, List<Float> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return -1f;
        }
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        if (normA == 0f || normB == 0f) {
            return -1f;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
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
        // v8.6.1(9.4): 单次 findAllById 批量往返替代逐条 findById N+1；幽灵 id 跳过并计数
        List<String> hitIds = new ArrayList<>();
        for (SearchHit hit : hits) {
            hitIds.add(hit.id());
        }
        Map<String, TestCase> existingById = new LinkedHashMap<>();
        for (TestCase tc : testCaseRepository.findAllById(hitIds)) {
            existingById.put(tc.getId(), tc);
        }
        List<TestCaseDTO> result = new ArrayList<>();
        for (SearchHit hit : hits) {
            TestCase tc = existingById.get(hit.id());
            if (tc == null) {
                ghostRecallCount.incrementAndGet();
                log.warn("语义检索命中幽灵向量，已过滤 (id={}, score={})", hit.id(), hit.score());
                continue;
            }
            result.add(TestCaseDTO.from(tc));
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
        return retrieveContexts(projectId, query == null ? List.of() : List.of(query), topK, null);
    }

    public List<String> retrieveContexts(String projectId, List<String> queries, int topK) {
        return retrieveContexts(projectId, queries, topK, null);
    }

    // v6.4: 按多个查询段分别召回，RRF 融合去重后返回 topK；
    // modules 非空时限定命中 module，生成侧只取需求类上下文，避免自我检索代码分析 JSON。
    public List<String> retrieveContexts(String projectId, List<String> queries, int topK, List<String> modules) {
        // v8.7.1(9.5.3): RAG 链路埋点——延迟/召回量/空召回；入口注入 projectId MDC
        long start = System.currentTimeMillis();
        com.testagent.observability.ObservabilityMdc.putProjectId(projectId);
        try {
            List<String> merged = retrieveContextsInternal(projectId, queries, topK, modules);
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordMillis("rag_latency_seconds", elapsed);
            if (merged.isEmpty()) {
                metrics.increment("rag_empty_recall_total");
            }
            metrics.incrementBy("rag_recall_count", merged.size());
            return merged;
        } finally {
            com.testagent.observability.ObservabilityMdc.clear();
        }
    }

    private List<String> retrieveContextsInternal(String projectId, List<String> queries, int topK, List<String> modules) {
        if (!isAvailable() || queries == null || queries.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<List<SearchHit>> ranked = new ArrayList<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            String limited = query.length() > 2000 ? query.substring(0, 2000) : query;
            List<Float> vector = embeddingService.embed(limited);
            if (vector.isEmpty()) {
                continue;
            }
            List<SearchHit> hits = milvusService.search(MilvusService.COLLECTION_CONTEXTS,
                    projectId, vector, Math.max(topK * 3, 15), modules);
            List<SearchHit> clean = new ArrayList<>();
            for (SearchHit hit : hits) {
                if (hit.id() == null || hit.id().isBlank() || hit.text() == null || hit.text().isBlank()) {
                    continue;
                }
                clean.add(hit);
            }
            if (!clean.isEmpty()) {
                ranked.add(clean);
            }
        }
        List<SearchHit> merged = mergeByRrf(ranked, topK);
        return merged.stream().map(this::formatContextHit).toList();
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

    /**
     * v7.10(R13): 失败经验入库——按 (projectId + 归一化 action + 归一化 error) 内容 hash 稳定 ID
     * 去重（写入前 deleteByIds 同 ID，同源失败覆盖不堆积）；语料补用例标题与页面 URL，
     * 向量相似度从"需求 vs 动作"改善为"需求 vs 标题+动作"。
     */
    public void recordFailure(String projectId, String executionId, String action, String error,
                              String caseTitle, String pageUrl) {
        if (!isAvailable()) {
            return;
        }
        String normAction = normalizeFailureText(action);
        String normError = normalizeFailureText(error);
        StringBuilder text = new StringBuilder();
        if (caseTitle != null && !caseTitle.isBlank()) {
            text.append("[").append(caseTitle.trim()).append("] ");
        }
        if (pageUrl != null && !pageUrl.isBlank()) {
            text.append("[").append(pageUrl.trim()).append("] ");
        }
        text.append(normAction).append(" -> ").append(normError);
        List<Float> vector = embeddingService.embed(text.toString());
        String id = "fail-" + sha256Hex(16, projectId == null ? "" : projectId,
                normAction, normError);
        // 同源失败覆盖不堆积（旧实现用 executionId 当主键，同失败 10 次占满 topK）
        milvusService.deleteByIds(MilvusService.COLLECTION_FAILURES, projectId == null ? "" : projectId,
                List.of(id));
        milvusService.insert(MilvusService.COLLECTION_FAILURES, projectId,
                id, action, "failure", text.toString(), vector);
    }

    /** 失败文本归一化：trim + 连续空白压单空格 + 小写——归一化后相同失败才命中同一稳定 ID */
    private String normalizeFailureText(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** v7.10(R13): SHA-256(各部分 '\u0001' 连接) 前 hexLen 位十六进制 */
    private String sha256Hex(int hexLen, String... parts) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\u0001", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i * 2 < hexLen && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(String.join("\u0001", parts).hashCode());
        }
    }

    public List<String> searchFailures(String projectId, String error, int topK) {
        return retrieveFailures(projectId, error == null ? List.of() : List.of(error), topK);
    }

    // v6.4: 失败经验多路检索 + RRF 融合，供生成阶段注入 prompt
    public List<String> retrieveFailures(String projectId, List<String> queries, int topK) {
        if (!isAvailable() || queries == null || queries.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<List<SearchHit>> ranked = new ArrayList<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            String limited = query.length() > 2000 ? query.substring(0, 2000) : query;
            List<Float> vector = embeddingService.embed(limited);
            if (vector.isEmpty()) {
                continue;
            }
            List<SearchHit> hits = milvusService.search(MilvusService.COLLECTION_FAILURES,
                    projectId, vector, Math.max(topK * 3, 9), null);
            if (!hits.isEmpty()) {
                ranked.add(hits);
            }
        }
        List<SearchHit> merged = mergeByRrf(ranked, topK);
        return merged.stream().map(this::formatContextHit).toList();
    }

    private String formatContextHit(SearchHit hit) {
        String title = hit.title();
        String text = hit.text();
        if (title != null && !title.isBlank() && (text == null || !text.startsWith(title))) {
            return title + "\n" + text;
        }
        return text;
    }

    // RRF：多路召回按排名融合，消除单一 cosine 排序对某一路结果的偏置
    List<SearchHit> mergeByRrf(List<List<SearchHit>> ranked, int topK) {
        Map<String, RrfAccumulator> acc = new LinkedHashMap<>();
        for (List<SearchHit> hits : ranked) {
            int rank = 0;
            for (SearchHit hit : hits) {
                rank++;
                final int r = rank;
                acc.compute(hit.id(), (id, a) -> {
                    if (a == null) {
                        a = new RrfAccumulator(hit);
                    }
                    a.rrf += 1.0 / (rrfK + r);
                    if (hit.score() > a.cosine) {
                        a.cosine = hit.score();
                        a.hit = hit;
                    }
                    return a;
                });
            }
        }
        return acc.values().stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.rrf, a.rrf);
                    return c != 0 ? c : Double.compare(b.cosine, a.cosine);
                })
                .limit(topK)
                .map(a -> a.hit)
                .toList();
    }

    private static final class RrfAccumulator {
        private SearchHit hit;
        private double rrf;
        private double cosine = Double.NEGATIVE_INFINITY;

        private RrfAccumulator(SearchHit hit) {
            this.hit = hit;
        }
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
