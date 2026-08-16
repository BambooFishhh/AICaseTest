package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.dto.LlmCallResult;
import com.testagent.mcp.McpClientManager;
import com.testagent.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 调用服务。
 * v1.0: OkHttp 直调 OpenAI API
 * v2.3: 重构为通过 MCP 协议调用独立 MCP Server
 * v2.6: 适配 McpClientManager 多 Server 架构
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.provider:openai}")
    private String provider;

    @Value("${llm.model:gpt-4o}")
    private String model;

    @Autowired
    private McpClientManager mcpClientManager;

    @Autowired
    private TelemetryService telemetryService;

    // v1.4: 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    /**
     * v2.6: 通过 MCP Server 调用 LLM 文本对话。
     */
    public boolean isConfigured() {
        return mcpClientManager.isAvailable("llm");
    }

    /**
     * v2.6: 通过 MCP 协议调用 llm_chat 工具。
     */
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        return chatWithUsage(systemPrompt, userPrompt, temperature).getText();
    }

    /**
     * v5.14: chat() 的埋点版本，返回耗时与 token usage。
     */
    public LlmCallResult chatWithUsage(String systemPrompt, String userPrompt, double temperature) {
        log.info("[LLM] chat() 开始, provider={}, model={}, prompt长度={}", provider, model, userPrompt == null ? 0 : userPrompt.length());
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                log.info("[LLM] 调用 MCP callTool(llm, llm_chat), attempt={}", attempt + 1);
                long start = System.currentTimeMillis();
                McpToolResult result = mcpClientManager.callToolWithMeta("llm", "llm_chat", Map.of(
                        "system_prompt", systemPrompt,
                        "user_prompt", userPrompt,
                        "temperature", temperature
                ));
                long elapsed = System.currentTimeMillis() - start;
                LlmCallResult call = toLlmCallResult(result, start, null);
                telemetryService.recordLlmCall(call);
                log.info("[LLM] MCP 返回, 耗时={}ms, 响应长度={}, usage={}",
                        elapsed, call.getText() == null ? 0 : call.getText().length(),
                        Map.of("prompt", call.getPromptTokens(), "completion", call.getCompletionTokens(),
                                "total", call.getTotalTokens()));
                return call;
            } catch (Exception e) {
                lastException = e;
                log.warn("[LLM] attempt {} 失败: {}", attempt + 1, e.getMessage());
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
     * 用于用例生成场景，让用户在 LLM 生成过程中即可看到逐条用例。
     */
    public String chatStreaming(String systemPrompt, String userPrompt,
                               double temperature,
                               Consumer<String> chunkConsumer) {
        return chatStreamingWithUsage(systemPrompt, userPrompt, temperature, chunkConsumer).getText();
    }

    /**
     * v5.14: chatStreaming() 的埋点版本，返回耗时、首 token 耗时与 token usage。
     */
    public LlmCallResult chatStreamingWithUsage(String systemPrompt, String userPrompt,
                                                double temperature,
                                                Consumer<String> chunkConsumer) {
        log.info("[LLM] chatStreaming() 开始, prompt长度={}", userPrompt == null ? 0 : userPrompt.length());
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                long start = System.currentTimeMillis();
                AtomicLong firstTokenAt = new AtomicLong(0);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("system_prompt", systemPrompt);
                args.put("user_prompt", userPrompt);
                args.put("temperature", temperature);
                args.put("stream", true);

                Consumer<String> wrappedChunk = chunk -> {
                    if (firstTokenAt.get() == 0) {
                        firstTokenAt.set(System.currentTimeMillis());
                    }
                    if (chunkConsumer != null) {
                        chunkConsumer.accept(chunk);
                    }
                };
                McpToolResult result = mcpClientManager.callToolStreamingWithMeta(
                        "llm", "llm_chat", args, wrappedChunk);
                long elapsed = System.currentTimeMillis() - start;
                Long firstTokenMs = firstTokenAt.get() == 0 ? null : firstTokenAt.get() - start;
                LlmCallResult call = toLlmCallResult(result, start, firstTokenMs);
                telemetryService.recordLlmCall(call);
                log.info("[LLM] chatStreaming 完成, 耗时={}ms, ttft={}ms, 响应长度={}, usage={}",
                        elapsed, firstTokenMs, call.getText() == null ? 0 : call.getText().length(),
                        Map.of("prompt", call.getPromptTokens(), "completion", call.getCompletionTokens(),
                                "total", call.getTotalTokens()));
                return call;
            } catch (Exception e) {
                lastException = e;
                log.warn("[LLM] chatStreaming attempt {} 失败: {}", attempt + 1, e.getMessage());
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
     * v2.6: 通过 MCP 协议调用 llm_chat_with_image 工具。
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
                LlmCallResult call = toLlmCallResult(result, start, null);
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

    private LlmCallResult toLlmCallResult(McpToolResult result, long startMs, Long firstTokenMs) {
        LlmCallResult.LlmCallResultBuilder builder = LlmCallResult.builder()
                .text(result == null ? null : result.getText())
                .durationMs(System.currentTimeMillis() - startMs)
                .firstTokenMs(firstTokenMs)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0);
        if (result != null && result.getMetadata() != null && result.getMetadata().has("usage")) {
            JsonNode usage = result.getMetadata().path("usage");
            builder.promptTokens(usage.path("prompt_tokens").asInt(0));
            builder.completionTokens(usage.path("completion_tokens").asInt(0));
            builder.totalTokens(usage.path("total_tokens").asInt(0));
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
     * v2.6: 测试 MCP Server 连接。
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("model", model);
        result.put("mcpAvailable", mcpClientManager.isAvailable("llm"));
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
}
