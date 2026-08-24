package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v8.1: 需求 ↔ 接口 LLM 辅助映射——补充 Git diff 可能遗漏的范围项
 * （如只改配置/常量但语义上属于本期需求的接口）。失败降级为空结果，不阻断草稿创建。
 */
@Component
public class ScopeMappingAgent {

    private static final Logger log = LoggerFactory.getLogger(ScopeMappingAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int MAX_PRD_LENGTH = 8000;
    private static final int MAX_ENDPOINTS = 120;

    @Autowired
    private LlmService llmService;

    /**
     * @param prdText    PRD 全文（调用方负责拼接，内部再截断）
     * @param endpoints  接口清单 [{method,path,description,businessLogic}]
     * @return 映射建议 [{method,path,reason}]；任何失败返回空列表
     */
    public List<Map<String, Object>> map(String prdText, List<Map<String, Object>> endpoints) {
        if (prdText == null || prdText.isBlank() || endpoints == null || endpoints.isEmpty()) {
            return List.of();
        }
        try {
            String systemPrompt = """
                    你是测试范围分析助手。根据需求文档判断哪些接口属于本期需求范围。
                    只输出 JSON 数组，不要输出任何其他文字：
                    [{"method":"GET","path":"/admin/order/list","reason":"一句话理由"}]
                    规则：
                    - 只能从给定接口清单中选择，禁止编造接口；
                    - 只选择需求明确涉及或强相关的接口，宁缺勿滥；
                    - 无相关接口时输出 []。
                    """;
            String userPrompt = buildUserPrompt(prdText, endpoints);
            String response = llmService.chat(systemPrompt, userPrompt, 0.2);
            return parse(response);
        } catch (Exception e) {
            log.warn("[Scope] LLM 辅助映射失败（跳过）: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildUserPrompt(String prdText, List<Map<String, Object>> endpoints) {
        String prd = prdText.length() > MAX_PRD_LENGTH
                ? prdText.substring(0, MAX_PRD_LENGTH) + "\n...(已截断)" : prdText;
        StringBuilder sb = new StringBuilder();
        sb.append("## 需求文档\n").append(prd).append("\n\n## 接口清单\n");
        int count = 0;
        for (Map<String, Object> ep : endpoints) {
            if (count >= MAX_ENDPOINTS) {
                break;
            }
            String method = str(ep.get("method")).toUpperCase();
            String path = str(ep.get("path"));
            if (method.isBlank() || path.isBlank()) {
                continue;
            }
            sb.append(method).append(' ').append(path);
            String desc = str(ep.get("description"));
            if (!desc.isBlank()) {
                sb.append(" — ").append(truncate(desc, 60));
            }
            String logic = str(ep.get("businessLogic"));
            if (!logic.isBlank()) {
                sb.append(" | ").append(truncate(logic, 80));
            }
            sb.append('\n');
            count++;
        }
        return sb.toString();
    }

    private List<Map<String, Object>> parse(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("[Scope] LLM 映射响应不含 JSON 数组");
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response.substring(start, end + 1));
        } catch (Exception e) {
            log.warn("[Scope] LLM 映射响应解析失败: {}", e.getMessage());
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (!root.isArray()) {
            return List.of();
        }
        for (JsonNode node : root) {
            String method = node.path("method").asText("").trim().toUpperCase();
            String path = node.path("path").asText("").trim();
            if (method.isBlank() || path.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("method", method);
            item.put("path", path);
            item.put("reason", truncate(node.path("reason").asText(""), 200));
            result.add(item);
        }
        return result;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
