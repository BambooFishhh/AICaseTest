package com.testagent.service;

import com.testagent.dto.TestCaseDTO;
import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseRepository;
import com.testagent.service.MilvusService.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// v8.6.1(9.4): 检索侧存在性兜底——幽灵向量不误杀去重、不出现在检索结果
class SemanticServiceExistenceFilterTest {

    private SemanticService service;
    private EmbeddingService embeddingService;
    private MilvusService milvusService;
    private TestCaseRepository testCaseRepository;

    @BeforeEach
    void setUp() {
        service = new SemanticService();
        embeddingService = mock(EmbeddingService.class);
        milvusService = mock(MilvusService.class);
        testCaseRepository = mock(TestCaseRepository.class);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(service, "milvusService", milvusService);
        ReflectionTestUtils.setField(service, "testCaseRepository", testCaseRepository);
        when(embeddingService.isConfigured()).thenReturn(true);
        when(milvusService.isEnabled()).thenReturn(true);
        when(milvusService.duplicateThreshold()).thenReturn(0.92);
        when(embeddingService.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
    }

    private SearchHit hit(String id, double score) {
        return new SearchHit(id, "t", "m", "text", score);
    }

    @Test
    void ghostVectorDoesNotTriggerDuplicate() {
        when(milvusService.search(eq(MilvusService.COLLECTION_CASES), eq("p1"), any(), eq(1)))
                .thenReturn(List.of(hit("TC-GHOST", 0.97)));
        when(testCaseRepository.existsById("TC-GHOST")).thenReturn(false);

        boolean dup = service.isDuplicate("p1", caseWithTitle("新用例"));

        assertFalse(dup);
        assertTrue(service.getGhostRecallCount() > 0);
    }

    @Test
    void existingVectorStillTriggersDuplicate() {
        when(milvusService.search(eq(MilvusService.COLLECTION_CASES), eq("p1"), any(), eq(1)))
                .thenReturn(List.of(hit("TC-1", 0.97)));
        when(testCaseRepository.existsById("TC-1")).thenReturn(true);

        assertTrue(service.isDuplicate("p1", caseWithTitle("已有用例")));
    }

    @Test
    void searchCasesFiltersGhostAndKeepsScoreOrder() {
        when(milvusService.search(eq(MilvusService.COLLECTION_CASES), eq("p1"), any(), eq(20)))
                .thenReturn(List.of(hit("TC-A", 0.98), hit("TC-GHOST", 0.95), hit("TC-B", 0.90)));
        TestCase a = caseWithTitle("A");
        a.setId("TC-A");
        TestCase b = caseWithTitle("B");
        b.setId("TC-B");
        when(testCaseRepository.findAllById(List.of("TC-A", "TC-GHOST", "TC-B")))
                .thenReturn(List.of(a, b));

        List<TestCaseDTO> result = service.searchCases("p1", "查询");

        assertEquals(2, result.size());
        assertEquals("TC-A", result.get(0).getId());
        assertEquals("TC-B", result.get(1).getId());
    }

    private TestCase caseWithTitle(String title) {
        TestCase tc = new TestCase();
        tc.setId("TC-X");
        tc.setProjectId("p1");
        tc.setTitle(title);
        tc.setModule("mod");
        return tc;
    }
}
