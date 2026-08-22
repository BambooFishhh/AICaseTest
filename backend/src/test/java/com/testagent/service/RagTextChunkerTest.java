package com.testagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagTextChunkerTest {

    @Test
    void blankTextReturnsEmpty() {
        assertTrue(RagTextChunker.chunk(null, 900, 150).isEmpty());
        assertTrue(RagTextChunker.chunk("   ", 900, 150).isEmpty());
    }

    @Test
    void shortTextReturnsSingleChunk() {
        List<RagTextChunker.Chunk> chunks = RagTextChunker.chunk("这是一段短需求描述。", 900, 150);

        assertEquals(1, chunks.size());
        assertEquals("这是一段短需求描述。", chunks.get(0).text());
        assertTrue(chunks.get(0).title() == null || chunks.get(0).title().isBlank());
    }

    @Test
    void headingBecomesChunkTitle() {
        List<RagTextChunker.Chunk> chunks = RagTextChunker.chunk(
                "# 订单模块\n订单需要支持先付款后发货。", 900, 150);

        assertEquals(1, chunks.size());
        assertEquals("订单模块", chunks.get(0).title());
        assertEquals("订单需要支持先付款后发货。", chunks.get(0).text());
    }

    @Test
    void longParagraphSplitsWithoutDroppingContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("测试内容").append(i).append("。");
        }
        String text = sb.toString();

        List<RagTextChunker.Chunk> chunks = RagTextChunker.chunk(text, 500, 100);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(c -> c.text().length() <= 500));
        assertTrue(chunks.get(chunks.size() - 1).text().contains("测试内容299"));
        assertTrue(chunks.get(0).text().contains("测试内容0"));
    }
}
