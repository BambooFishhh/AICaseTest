package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v5.4: embedding 服务，通过 MCP llm_embedding 工具生成文本向量。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private McpClientManager mcpClientManager;

    public boolean isConfigured() {
        return mcpClientManager.isAvailable("llm-embedding");
    }

    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (!isConfigured()) {
            log.debug("LLM MCP unavailable, skip embedding");
            return List.of();
        }
        try {
            String response = mcpClientManager.callTool("llm-embedding", "llm_embedding", Map.of("input", text));
            String json = extractJsonArray(response);
            JsonNode arr = objectMapper.readTree(json);
            List<Float> result = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    result.add(n.floatValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Embedding failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonArray(String text) {
        if (text == null || text.isBlank()) {
            return "[]";
        }
        String trimmed = text.trim();
        if (trimmed.contains("```")) {
            int fenceStart = trimmed.indexOf("```");
            int contentStart = trimmed.indexOf("\n", fenceStart);
            if (contentStart != -1) {
                int fenceEnd = trimmed.indexOf("```", contentStart);
                if (fenceEnd != -1) {
                    trimmed = trimmed.substring(contentStart + 1, fenceEnd).trim();
                }
            }
        }
        int first = trimmed.indexOf('[');
        int last = trimmed.lastIndexOf(']');
        if (first != -1 && last != -1 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return "[]";
    }
}
