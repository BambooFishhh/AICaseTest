package com.testagent.service;

import com.testagent.dto.TestCaseDTO;
import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseRepository;
import com.testagent.service.MilvusService.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * v5.4: 语义检索服务——embedding + Milvus，支撑语义去重、RAG、语义搜索与失败经验库。
 */
@Service
public class SemanticService {

    private static final Logger log = LoggerFactory.getLogger(SemanticService.class);

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
        if (!isAvailable() || query == null || query.isBlank()) {
            return List.of();
        }
        String limited = query.length() > 2000 ? query.substring(0, 2000) : query;
        List<Float> vector = embeddingService.embed(limited);
        if (vector.isEmpty()) {
            return List.of();
        }
        List<SearchHit> hits = milvusService.search(MilvusService.COLLECTION_CONTEXTS, projectId, vector, topK);
        return hits.stream().map(SearchHit::text)
                .filter(s -> s != null && !s.isBlank())
                .toList();
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
