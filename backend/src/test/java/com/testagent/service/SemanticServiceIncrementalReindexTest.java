package com.testagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticServiceIncrementalReindexTest {

    @Test
    void reindexesOnlySupplementaryWhenOnlySupplementaryChanges() {
        SemanticService service = new SemanticService();
        MilvusService milvus = mock(MilvusService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        ReflectionTestUtils.setField(service, "milvusService", milvus);
        ReflectionTestUtils.setField(service, "embeddingService", embedding);
        ReflectionTestUtils.setField(service, "chunkSize", 900);
        ReflectionTestUtils.setField(service, "chunkOverlap", 150);
        when(milvus.isEnabled()).thenReturn(true);
        when(embedding.embed(anyString())).thenReturn(List.of(0.5f));

        List<Map<String, Object>> prdDocs = List.of(doc("p1", "主 PRD", "prd content"));
        List<Map<String, Object>> contextDocs = List.of(doc("c1", "上下文", "context content"));

        service.ensureRequirementContexts("p1", prdDocs, contextDocs, "旧补充");
        clearInvocations(milvus, embedding);

        service.ensureRequirementContexts("p1", prdDocs, contextDocs, "新补充");

        verify(milvus).deleteByModule(
                MilvusService.COLLECTION_CONTEXTS, "p1", "supplementary");
        verify(milvus, never()).deleteByModule(
                MilvusService.COLLECTION_CONTEXTS, "p1", "prd");
        verify(milvus, never()).deleteByModule(
                MilvusService.COLLECTION_CONTEXTS, "p1", "context");
        verify(embedding, times(1)).embed(anyString());
    }

    @Test
    void skipsAllModulesWhenNothingChanged() {
        SemanticService service = new SemanticService();
        MilvusService milvus = mock(MilvusService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        ReflectionTestUtils.setField(service, "milvusService", milvus);
        ReflectionTestUtils.setField(service, "embeddingService", embedding);
        ReflectionTestUtils.setField(service, "chunkSize", 900);
        ReflectionTestUtils.setField(service, "chunkOverlap", 150);
        when(milvus.isEnabled()).thenReturn(true);
        when(embedding.embed(anyString())).thenReturn(List.of(0.5f));

        List<Map<String, Object>> prdDocs = List.of(doc("p1", "主 PRD", "prd content"));
        List<Map<String, Object>> contextDocs = List.of(doc("c1", "上下文", "context content"));

        service.ensureRequirementContexts("p1", prdDocs, contextDocs, "补充");
        clearInvocations(milvus, embedding);

        service.ensureRequirementContexts("p1", prdDocs, contextDocs, "补充");

        verify(milvus, never()).deleteByModule(eq(MilvusService.COLLECTION_CONTEXTS),
                eq("p1"), anyString());
        verify(embedding, never()).embed(anyString());
    }

    private Map<String, Object> doc(String id, String title, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("content", content);
        m.put("docType", title.equals("主 PRD") ? "prd" : "context");
        return m;
    }
}
