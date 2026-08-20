package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.common.GenerationCancelledException;
import com.testagent.dto.LlmCallResult;
import com.testagent.mcp.McpClientManager;
import com.testagent.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * LLM 调用服务。
 * v1.0: OkHttp 直调 OpenAI API
 * v2.3: 重构为通过 MCP 协议调用独立 MCP Server
 * v2.6: 适配 McpClientManager 多 Server 架构
 * v6.0: 文本/流式/JSON/Embedding 层改用 Spring AI OpenAI starter，
 *       仅多模态(chatWithImage / multimodal_element_locate)与浏览器/工具仍走 MCP。
 * 注意：Spring AI 1.0.0 OpenAI starter 无法透传 DashScope 的 enable_thinking，
 *       因此 analysis/generation 思考开关在该链路为咨询性配置（详见迁移文档 PoC 对比）。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.provider:openai}")
    private String provider;

    @Value("${llm.model:gpt-4o}")
    private String model;

    // v5.14: 按任务粒度控制思考模式——分析类任务保留，生成/评审默认关闭
    // v6.0: 咨询性配置（OpenAI starter 不透传 enable_thinking，见类注释）
    @Value("${llm.thinking.analysis:true}")
    private boolean analysisThinking;

    @Value("${llm.thinking.generation:false}")
    private boolean generationThinking;

    // v6.1 (B 方案): 统一 prompt 上限，防止超大上下文触发 Idle timeout
    @Value("${llm.max-prompt-chars:60000}")
    private int maxPromptChars;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private McpClientManager mcpClientManager;

    @Autowired
    private TelemetryService telemetryService;

    // v1.4: 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    // v6.0: 当前进行中的流式订阅，供取消生成使用（与旧版单 llm-stream 连接语义一致）
    private final AtomicReference<Disposable> activeStream = new AtomicReference<>();
    private final AtomicBoolean streamCancelled = new AtomicBoolean(false);

    /**
     * v6.0: 检查 Spring AI ChatModel/EmbeddingModel 是否可用（替代旧版 MCP llm 可用性检查）。
     */
    public boolean isConfigured() {
        return chatModel != null;
    }

    /**
     * v6.0: 通过 Spring AI ChatClient 调用 LLM 文本对话。
     */
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        return chatWithUsage(systemPrompt, userPrompt, temperature, generationThinking).getText();
    }

    /**
     * v5.14: 分析类调用（PRD 解析/状态机提取）保留思考模式。
     */
    public String chatWithAnalysis(String systemPrompt, String userPrompt, double temperature) {
        return chatWithUsage(systemPrompt, userPrompt, temperature, analysisThinking).getText();
    }

    /**
     * v5.14: chat() 的埋点版本，返回耗时与 token usage。
     */
    public LlmCallResult chatWithUsage(String systemPrompt, String userPrompt, double temperature) {
        return chatWithUsage(systemPrompt, userPrompt, temperature, generationThinking);
    }

    private LlmCallResult chatWithUsage(String systemPrompt, String userPrompt, double temperature,
                                        boolean enableThinking) {
        userPrompt = boundPrompt(userPrompt);
        log.info("[LLM] chat() 开始, provider={}, model={}, prompt长度={}, thinking={}",
                provider, model, userPrompt == null ? 0 : userPrompt.length(), enableThinking);
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                long start = System.currentTimeMillis();
                OpenAiChatOptions options = buildOptions(temperature, enableThinking);
                ChatResponse response = chatClientBuilder.build().prompt()
                        .system(systemPrompt)
                        .user(userPrompt == null ? "" : userPrompt)
                        .options(options)
                        .call()
                        .chatResponse();
                String text = extractText(response);
                LlmCallResult call = toLlmCallResult(text, response == null ? null : response.getMetadata().getUsage(),
                        start, null);
                telemetryService.recordLlmCall(call);
                log.info("[LLM] Spring AI 返回, 耗时={}ms, 响应长度={}, usage={}",
                        System.currentTimeMillis() - start, text == null ? 0 : text.length(),
                        Map.of("prompt", call.getPromptTokens(), "completion", call.getCompletionTokens(),
                                "total", call.getTotalTokens()));
                return call;
            } catch (Exception e) {
                lastException = e;
                log.warn("[LLM] attempt {} 失败: {}", attempt + 1, e.getMessage());
                if (e instanceof GenerationCancelledException) {
                    throw (GenerationCancelledException) e;
                }
            }
            if (attempt < MAX_RETRIES - 1) {
                try {
                    log.info("[LLM] 等待 {}ms 后重试...", RETRY_DELAYS_MS[attempt]);
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new BusinessException(50002,
                "LLM调用失败（已重试" + MAX_RETRIES + "次）: " + (lastException != null ? lastException.getMessage() : "unknown"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * v3.7: 流式调用 LLM。chunkConsumer 接收逐块文本，方法返回完整响应。
     * v6.0: 改为 Spring AI ChatClient.stream()，Flux<ChatResponse> 仅在本类内部适配。
     */
    public String chatStreaming(String systemPrompt, String userPrompt,
                                double temperature,
                                Consumer<String> chunkConsumer) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer, generationThinking).getText();
    }

    /**
     * v5.14: chatStreaming() 的埋点版本，返回耗时、首 token 耗时与 token usage。
     */
    public LlmCallResult chatStreamingWithUsage(String systemPrompt, String userPrompt,
                                                double temperature,
                                                Consumer<String> chunkConsumer) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer, generationThinking);
    }

    private LlmCallResult chatStreamingWithUsage(String systemPrompt, String userPrompt,
                                                 double temperature,
                                                 Consumer<String> chunkConsumer,
                                                 boolean enableThinking) {
        userPrompt = boundPrompt(userPrompt);
        log.info("[LLM] chatStreaming() 开始, prompt长度={}, thinking={}",
                userPrompt == null ? 0 : userPrompt.length(), enableThinking);
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            streamCancelled.set(false);
            try {
                long start = System.currentTimeMillis();
                AtomicLong firstTokenAt = new AtomicLong(0);
                StringBuilder full = new StringBuilder();
                AtomicReference<Usage> usageRef = new AtomicReference<>();
                AtomicReference<Throwable> errorRef = new AtomicReference<>();
                CountDownLatch done = new CountDownLatch(1);

                OpenAiChatOptions options = buildOptions(temperature, enableThinking);
                var flux = chatClientBuilder.build().prompt()
                        .system(systemPrompt)
                        .user(userPrompt == null ? "" : userPrompt)
                        .options(options)
                        .stream()
                        .chatResponse();

                Disposable disposable = flux.doOnNext(response -> {
                    if (streamCancelled.get()) {
                        throw new GenerationCancelledException("用户取消生成");
                    }
                    String delta = extractText(response);
                    if (delta != null && !delta.isEmpty()) {
                        firstTokenAt.compareAndSet(0, System.currentTimeMillis());
                        full.append(delta);
                        if (chunkConsumer != null) {
                            chunkConsumer.accept(delta);
                        }
                    }
                    Usage usage = response.getMetadata().getUsage();
                    if (usage != null) {
                        usageRef.set(usage);
                    }
                }).doOnError(errorRef::set)
                  .doOnComplete(done::countDown)
                  .doOnCancel(done::countDown)
                  .subscribe();
                activeStream.set(disposable);

                try {
                    while (!done.await(200, TimeUnit.MILLISECONDS)) {
                        if (streamCancelled.get()) {
                            disposable.dispose();
                            throw new GenerationCancelledException("用户取消生成");
                        }
                    }
                } finally {
                    activeStream.set(null);
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }

                if (streamCancelled.get()) {
                    throw new GenerationCancelledException("用户取消生成");
                }
                Throwable error = errorRef.get();
                if (error != null) {
                    throw new RuntimeException(error);
                }

                long elapsed = System.currentTimeMillis() - start;
                Long firstTokenMs = firstTokenAt.get() == 0 ? null : firstTokenAt.get() - start;
                LlmCallResult call = toLlmCallResult(full.toString(), usageRef.get(), start, firstTokenMs);
                telemetryService.recordLlmCall(call);
                log.info("[LLM] chatStreaming 完成, 耗时={}ms, ttft={}ms, 响应长度={}, usage={}",
                        elapsed, firstTokenMs, full.length(),
                        Map.of("prompt", call.getPromptTokens(), "completion", call.getCompletionTokens(),
                                "total", call.getTotalTokens()));
                return call;
            } catch (Exception e) {
                lastException = e;
                log.warn("[LLM] chatStreaming attempt {} 失败: {}", attempt + 1, e.getMessage());
                if (e instanceof GenerationCancelledException) {
                    throw (GenerationCancelledException) e;
                }
            }
            if (attempt < MAX_RETRIES - 1) {
                try {
                    log.info("[LLM] 等待 {}ms 后重试...", RETRY_DELAYS_MS[attempt]);
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new BusinessException(50002,
                "LLM流式调用失败（已重试" + MAX_RETRIES + "次）: " + (lastException != null ? lastException.getMessage() : "unknown"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * v6.0: 取消当前进行中的 Spring AI 流式请求（由测试用例生成取消端点触发）。
     */
    public void cancelStreaming() {
        streamCancelled.set(true);
        Disposable disposable = activeStream.get();
        if (disposable != null && !disposable.isDisposed()) {
            try {
                disposable.dispose();
            } catch (Exception e) {
                log.debug("Cancel Spring AI stream dispose failed: {}", e.getMessage());
            }
        }
    }

    /**
     * v2.6: 通过 MCP 协议调用 llm_chat_with_image 工具（保留 MCP 多模态）。
     */
    public String chatWithImage(String systemPrompt, String userText, String imageBase64) {
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                long start = System.currentTimeMillis();
                McpToolResult result = mcpClientManager.callToolWithMeta("llm", "llm_chat_with_image", Map.of(
                        "system_prompt", systemPrompt,
                        "user_text", userText,
                        "image_base64", imageBase64
                ));
                LlmCallResult call = toLlmCallResult(result == null ? null : result.getText(),
                        result == null || result.getMetadata() == null ? null
                                : usageFromMcpJson(result.getMetadata().path("usage")),
                        start, null);
                telemetryService.recordLlmCall(call);
                return call.getText();
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM image call attempt {} failed: {}", attempt + 1, e.getMessage());
            }
            if (attempt < MAX_RETRIES - 1) {
                try {
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new BusinessException(50002,
                "LLM多模态调用失败（已重试" + MAX_RETRIES + "次）: " + (lastException != null ? lastException.getMessage() : "unknown"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private OpenAiChatOptions buildOptions(double temperature, boolean enableThinking) {
        // v6.0: OpenAI starter 不透传 enable_thinking；temperature/maxTokens 对齐旧版 MCP 请求。
        log.debug("[LLM] thinking flag={} (Spring AI OpenAI starter advisory only)", enableThinking);
        return OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(16384)
                .streamUsage(true)
                .build();
    }

    private String extractText(ChatResponse response) {
        if (response == null) {
            return "";
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null) {
            return "";
        }
        return generation.getOutput().getText();
    }

    private Usage usageFromMcpJson(JsonNode usage) {
        if (usage == null || usage.isMissingNode()) {
            return null;
        }
        return new SimpleUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0));
    }

    private LlmCallResult toLlmCallResult(String text, Usage usage, long startMs, Long firstTokenMs) {
        LlmCallResult.LlmCallResultBuilder builder = LlmCallResult.builder()
                .text(text)
                .durationMs(System.currentTimeMillis() - startMs)
                .firstTokenMs(firstTokenMs);
        if (usage == null) {
            builder.promptTokens(0).completionTokens(0).totalTokens(0);
        } else {
            builder.promptTokens(usage.getPromptTokens() == null ? 0 : usage.getPromptTokens())
                    .completionTokens(usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens())
                    .totalTokens(usage.getTotalTokens() == null ? 0 : usage.getTotalTokens());
        }
        return builder.build();
    }

    /**
     * v2.6: chatJson 复用 chat() + JSON 解析。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chatJson(String systemPrompt, String userPrompt, double temperature) {
        String response = chat(systemPrompt, userPrompt, temperature);
        String json = extractJsonObject(response);
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse LLM JSON response. Raw: {}", response, e);
            throw new BusinessException(50002,
                    "LLM返回JSON解析失败: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        String trimmed = text.trim();

        if (trimmed.contains("```")) {
            int fenceStart = trimmed.indexOf("```");
            int contentStart = trimmed.indexOf("\n", fenceStart);
            if (contentStart != -1) {
                int fenceEnd = trimmed.indexOf("```", contentStart);
                if (fenceEnd != -1) {
                    return trimmed.substring(contentStart + 1, fenceEnd).trim();
                }
            }
        }

        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return trimmed;
    }

    /**
     * v2.6: 测试 LLM 连通性。
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("model", model);
        result.put("springAiChatModel", chatModel != null);
        result.put("springAiEmbeddingModel", embeddingModel != null);
        try {
            String response = chat("You are a helpful assistant.", "Reply with the word: ok", 0.0);
            String preview = response.length() > 200 ? response.substring(0, 200) : response;
            result.put("response_preview", preview);
            result.put("status", "success");
        } catch (Exception e) {
            result.put("response_preview", "Error: " + e.getMessage());
            result.put("status", "failed");
        }
        return result;
    }

    // v6.1 (B 方案): 对 userPrompt 做统一长度约束，避免 277KB 巨型 prompt 触发 idle/read timeout。
    // 截断时保留头部并追加提示标记，让调用方仍能感知上下文被裁剪。
    private String boundPrompt(String userPrompt) {
        if (userPrompt == null || userPrompt.length() <= maxPromptChars) {
            return userPrompt;
        }
        log.warn("[LLM] prompt 超限 {} → 截断到 {}", userPrompt.length(), maxPromptChars);
        String head = userPrompt.substring(0, maxPromptChars);
        return head + "\n\n[system] 上下文已按 llm.max-prompt-chars 上限裁剪，请只使用剩余内容作答。";
    }

    /**
     * 最小 Usage 实现，用于 MCP 多模态返回（chatWithImage）的埋点适配。
     */
    private static final class SimpleUsage implements Usage {
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        private SimpleUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Integer getTotalTokens() {
            return totalTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
