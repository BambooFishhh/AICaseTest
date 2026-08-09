package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

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
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return mcpClientManager.callTool("llm", "llm_chat", Map.of(
                        "system_prompt", systemPrompt,
                        "user_prompt", userPrompt,
                        "temperature", temperature
                ));
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM call attempt {} failed: {}", attempt + 1, e.getMessage());
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
                "LLM调用失败（已重试" + MAX_RETRIES + "次）: " + (lastException != null ? lastException.getMessage() : "unknown"),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * v2.6: 通过 MCP 协议调用 llm_chat_with_image 工具。
     */
    public String chatWithImage(String systemPrompt, String userText, String imageBase64) {
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return mcpClientManager.callTool("llm", "llm_chat_with_image", Map.of(
                        "system_prompt", systemPrompt,
                        "user_text", userText,
                        "image_base64", imageBase64
                ));
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
