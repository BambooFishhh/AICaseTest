package com.testagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {

    private EmbeddingModel embeddingModel;
    private EmbeddingService embeddingService;

    private void setUp(float[] vector, int dimensions) {
        embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("hello")).thenReturn(vector);
        when(embeddingModel.dimensions()).thenReturn(dimensions);
        embeddingService = new EmbeddingService();
        ReflectionTestUtils.setField(embeddingService, "embeddingModel", embeddingModel);
    }

    @Test
    void embedReturnsFloatList() {
        setUp(new float[]{1.0f, 2.0f, 3.0f}, 3);

        List<Float> result = embeddingService.embed("hello");

        assertEquals(3, result.size());
        assertEquals(1.0f, result.get(0));
        assertEquals(3.0f, result.get(2));
    }

    @Test
    void embedReturnsEmptyForBlankInput() {
        setUp(new float[]{}, 3);

        assertTrue(embeddingService.embed("  ").isEmpty());
        assertTrue(embeddingService.embed(null).isEmpty());
    }

    @Test
    void getDimensionsReturnsModelDimensions() {
        setUp(new float[]{1.0f}, 1024);

        assertEquals(1024, embeddingService.getDimensions());
    }

    @Test
    void isConfiguredReflectsEmbeddingModel() {
        setUp(new float[]{}, 1024);
        assertTrue(embeddingService.isConfigured());

        ReflectionTestUtils.setField(embeddingService, "embeddingModel", null, EmbeddingModel.class);
        assertFalse(embeddingService.isConfigured());
    }
}
