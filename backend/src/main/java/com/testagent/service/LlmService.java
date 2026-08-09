package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import jakarta.annotation.PostConstruct;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${llm.provider:openai}")
    private String provider;

    @Value("${llm.model:gpt-4o}")
    private String model;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    private OkHttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    // v1.12: 供 VueAnalyzer 等组件判断是否可调用 LLM
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    // v1.4: 重试配置
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    public String chat(String systemPrompt, String userPrompt, double temperature) {
        Exception lastException = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return callLlmApi(systemPrompt, userPrompt, temperature);
            } catch (BusinessException e) {
                // 400/401/403 等客户端错误不重试
                if (e.getHttpStatus() == HttpStatus.BAD_REQUEST
                        || e.getHttpStatus() == HttpStatus.UNAUTHORIZED
                        || e.getHttpStatus() == HttpStatus.FORBIDDEN) {
                    throw e;
                }
                lastException = e;
                log.warn("LLM call attempt {} failed: {} (status={}), will retry",
                        attempt + 1, e.getMessage(), e.getHttpStatus());
            } catch (Exception e) {
                lastException = e;
                log.warn("LLM call attempt {} failed: {}, will retry",
                        attempt + 1, e.getMessage());
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

    private String callLlmApi(String systemPrompt, String userPrompt, double temperature) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", 8192);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("LLM API error: status={}, body={}", response.code(), responseBody);
                HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
                if (response.code() == 400) status = HttpStatus.BAD_REQUEST;
                else if (response.code() == 401) status = HttpStatus.UNAUTHORIZED;
                else if (response.code() == 403) status = HttpStatus.FORBIDDEN;
                throw new BusinessException(50002,
                        "LLM API调用失败: HTTP " + response.code(),
                        status);
            }
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isEmpty()) {
                throw new BusinessException(50002,
                        "LLM返回内容为空",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return contentNode.asText();
        }
    }

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

    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("model", model);
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
