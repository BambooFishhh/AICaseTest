package com.testagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v8.8.1(10.1): 多供应商注册——保留现有 llm.* 为 primary，llm.models.fallback.* 为降级通道。
 * fallback 未配置 api-key 时降级路由禁用（enabled=false）。
 * 降级 ChatModel/EmbeddingModel 按需懒构建并缓存（primary 复用 Spring AI 自动装配 Bean）。
 */
@Component
public class LlmProviders {

    private static final Logger log = LoggerFactory.getLogger(LlmProviders.class);

    @Value("${llm.provider:openai}")
    private String primaryProvider;

    @Value("${llm.model:gpt-4o}")
    private String primaryModel;

    @Value("${llm.max-prompt-chars:500000}")
    private int primaryMaxPromptChars = 500000;

    @Value("${llm.models.fallback.base-url:}")
    private String fallbackBaseUrl;

    @Value("${llm.models.fallback.api-key:}")
    private String fallbackApiKey;

    @Value("${llm.models.fallback.model:}")
    private String fallbackModel;

    @Value("${llm.models.fallback.max-prompt-chars:200000}")
    private int fallbackMaxPromptChars = 200000;

    @Value("${llm.models.fallback.embedding-base-url:}")
    private String fallbackEmbeddingBaseUrl;

    @Value("${llm.models.fallback.embedding-api-key:}")
    private String fallbackEmbeddingApiKey;

    @Value("${llm.models.fallback.embedding-model:}")
    private String fallbackEmbeddingModel;

    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private volatile EmbeddingModel fallbackEmbeddingModelInstance;

    public record ProviderSpec(String name, String baseUrl, String apiKey,
                               String model, int maxPromptChars) {
    }

    public ProviderSpec primary() {
        return new ProviderSpec("primary", null, null, primaryModel, primaryMaxPromptChars);
    }

    public ProviderSpec fallback() {
        return new ProviderSpec("fallback", fallbackBaseUrl, fallbackApiKey, fallbackModel, fallbackMaxPromptChars);
    }

    // 配置三件套：yml 键 + @Value 默认值 + 字段初始化兜底（直 new 单测）
    public boolean fallbackEnabled() {
        return fallbackApiKey != null && !fallbackApiKey.isBlank()
                && fallbackBaseUrl != null && !fallbackBaseUrl.isBlank()
                && fallbackModel != null && !fallbackModel.isBlank();
    }

    /**
     * 降级通道 ChatClient（懒构建缓存）；未启用时返回 null。
     */
    public ChatClient fallbackChatClient() {
        if (!fallbackEnabled()) {
            return null;
        }
        return clientCache.computeIfAbsent("fallback", k -> {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(fallbackBaseUrl)
                    .apiKey(fallbackApiKey)
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                            .model(fallbackModel)
                            .build())
                    .retryTemplate(RetryTemplate.builder()
                            .maxAttempts(2)
                            .retryOn(TransientAiException.class)
                            .exponentialBackoff(1000, 2000, 4000)
                            .build())
                    .build();
            log.info("[LLM] 降级通道已构建: baseUrl={}, model={}", fallbackBaseUrl, fallbackModel);
            return ChatClient.builder(model).build();
        });
    }

    /**
     * 降级 embedding 模型（10.3）：llm.models.fallback.embedding-* 三键齐备才构建；未配置返回 null。
     */
    public EmbeddingModel fallbackEmbeddingModel() {
        if (fallbackEmbeddingApiKey == null || fallbackEmbeddingApiKey.isBlank()
                || fallbackEmbeddingBaseUrl == null || fallbackEmbeddingBaseUrl.isBlank()
                || fallbackEmbeddingModel == null || fallbackEmbeddingModel.isBlank()) {
            return null;
        }
        if (fallbackEmbeddingModelInstance == null) {
            synchronized (this) {
                if (fallbackEmbeddingModelInstance == null) {
                    OpenAiApi api = OpenAiApi.builder()
                            .baseUrl(fallbackEmbeddingBaseUrl)
                            .apiKey(fallbackEmbeddingApiKey)
                            .build();
                    fallbackEmbeddingModelInstance = new OpenAiEmbeddingModel(api,
                            MetadataMode.EMBED,
                            OpenAiEmbeddingOptions.builder().model(fallbackEmbeddingModel).build());
                    log.info("[LLM] 降级 embedding 已构建: model={}", fallbackEmbeddingModel);
                }
            }
        }
        return fallbackEmbeddingModelInstance;
    }
}
