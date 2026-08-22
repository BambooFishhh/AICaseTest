package com.testagent.service;

import com.testagent.service.MilvusService.SearchHit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticServiceRrfTest {

    @Test
    void mergeByRrfRanksAcrossQueries() {
        SemanticService service = new SemanticService();
        ReflectionTestUtils.setField(service, "rrfK", 60.0);

        List<SearchHit> query1 = List.of(
                new SearchHit("a", "A", "prd", "a-text", 0.80),
                new SearchHit("b", "B", "prd", "b-text", 0.60));
        List<SearchHit> query2 = List.of(
                new SearchHit("b", "B", "prd", "b-text", 0.70),
                new SearchHit("c", "C", "context", "c-text", 0.90));

        List<SearchHit> merged = service.mergeByRrf(List.of(query1, query2), 2);

        assertEquals(2, merged.size());
        assertEquals("b", merged.get(0).id());
        assertEquals("a", merged.get(1).id());
    }

    @Test
    void mergeByRrfDeduplicatesSameIdAndKeepsBestHit() {
        SemanticService service = new SemanticService();
        ReflectionTestUtils.setField(service, "rrfK", 60.0);

        List<SearchHit> query1 = List.of(
                new SearchHit("a", "A1", "prd", "a1", 0.50));
        List<SearchHit> query2 = List.of(
                new SearchHit("a", "A2", "prd", "a2", 0.95),
                new SearchHit("b", "B", "prd", "b", 0.80));

        List<SearchHit> merged = service.mergeByRrf(List.of(query1, query2), 2);

        assertEquals(2, merged.size());
        assertEquals("a", merged.get(0).id());
        assertEquals("A2", merged.get(0).title());
    }
}
