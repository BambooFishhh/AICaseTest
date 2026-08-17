package com.testagent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实链路冒烟测试（默认关闭）。
 * 仅在显式设置 AICT_LIVE_LLM_TEST=true 且已配置 LLM_API_KEY/LLM_BASE_URL 时运行，
 * 用于验证 Spring AI 流式输出与 embedding 维度（1000/1024）。正常 mvn test 默认跳过。
 */
@SpringBootTest(properties = {
        "app.mcp.enabled=false",
        "app.redis.enabled=false",
        "app.milvus.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "AICT_LIVE_LLM_TEST", matches = "true")
class LlmStreamingIntegrationTest {

    @Autowired
    private LlmService llmService;

    @Autowired
    private EmbeddingService embeddingService;

    @Test
    void chatStreamingReturnsRealChunks() {
        List<String> chunks = new ArrayList<>();
        String full = llmService.chatStreaming(
                "You are a test assistant.",
                "Return only this JSON array, nothing else: [{\"title\":\"demo\",\"type\":\"positive\"}]",
                0.2, chunks::add);

        assertNotNull(full);
        assertFalse(full.isBlank());
        assertFalse(chunks.isEmpty(), "expected at least one real streamed chunk");
        String joined = String.join("", chunks);
        assertEquals(full, joined);
        assertTrue(full.contains("demo"));
        assertTrue(full.contains("positive"));
    }

    @Test
    void embeddingIs1024Dimensions() {
        List<Float> vector = embeddingService.embed("hello");
        assertEquals(1024, vector.size());
    }
}
