package com.testagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v8.8.1(10.1): 多供应商注册——fallback 三键齐备才启用；未配置安全禁用
class LlmProvidersTest {

    private LlmProviders providersWith(String baseUrl, String apiKey, String model) {
        LlmProviders providers = new LlmProviders();
        ReflectionTestUtils.setField(providers, "primaryProvider", "bailian");
        ReflectionTestUtils.setField(providers, "primaryModel", "mimo-v2.5");
        ReflectionTestUtils.setField(providers, "primaryMaxPromptChars", 500000);
        ReflectionTestUtils.setField(providers, "fallbackBaseUrl", baseUrl);
        ReflectionTestUtils.setField(providers, "fallbackApiKey", apiKey);
        ReflectionTestUtils.setField(providers, "fallbackModel", model);
        return providers;
    }

    @Test
    void fallbackDisabledWhenApiKeyBlank() {
        assertFalse(providersWith("https://fb.example.com", "", "m1").fallbackEnabled());
        assertNull(providersWith("https://fb.example.com", "", "m1").fallbackChatClient());
        assertNull(providersWith("https://fb.example.com", "", "m1").fallbackEmbeddingModel());
    }

    @Test
    void fallbackDisabledWhenBaseUrlOrModelMissing() {
        assertFalse(providersWith("", "key", "m1").fallbackEnabled());
        assertFalse(providersWith("https://fb.example.com", "key", "").fallbackEnabled());
    }

    @Test
    void enabledWhenAllThreeKeysPresent() {
        assertTrue(providersWith("https://fb.example.com", "key-123", "gpt-x").fallbackEnabled());
        LlmProviders.ProviderSpec spec = providersWith("https://fb.example.com", "key-123", "gpt-x").fallback();
        assertEquals("fallback", spec.name());
        assertEquals("gpt-x", spec.model());
    }

    @Test
    void primarySpecDefaults() {
        LlmProviders.ProviderSpec spec = providersWith("https://fb.example.com", "k", "m").primary();
        assertEquals("primary", spec.name());
        assertEquals(500000, spec.maxPromptChars());
    }
}
