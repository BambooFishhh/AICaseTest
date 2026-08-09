package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.EnumInfo;
import com.testagent.analyzer.result.EnumValue;
import com.testagent.entity.StateMachine;
import com.testagent.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StateMachineAgent {

    private static final Logger log = LoggerFactory.getLogger(StateMachineAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    public List<StateMachine> extract(BackendResult backendResult) {
        List<StateMachine> result;
        try {
            result = extractByLlm(backendResult);
            if (result == null || result.isEmpty()) {
                log.warn("LLM state machine extraction returned empty, falling back to rule-based");
                result = extractByRules(backendResult);
            }
        } catch (Exception e) {
            log.warn("LLM state machine extraction failed, falling back to rule-based: {}", e.getMessage());
            result = extractByRules(backendResult);
        }
        for (StateMachine sm : result) {
            sm.setCreatedAt(LocalDateTime.now());
        }
        return result;
    }

    private List<StateMachine> extractByLlm(BackendResult backendResult) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enums", backendResult.getEnums());
        summary.put("entities", backendResult.getEntities());

        String systemPrompt = "你是状态机提取专家。根据提供的后端代码分析结果（枚举和常量），提取状态机信息。"
                + "请返回JSON数组，每个元素包含：name(状态机名称), description(描述), "
                + "states(状态数组，每个状态是包含name和description字段的对象), "
                + "transitions(状态转换数组，每个转换是包含from、to、trigger字段的对象)。"
                + "只返回JSON数组，不要包含其他文字。";

        String userPrompt;
        try {
            userPrompt = "后端代码分析结果：\n" + objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            userPrompt = "后端代码分析结果：\n" + summary.toString();
        }

        String response = llmService.chat(systemPrompt, userPrompt, 0.3);
        String json = extractJsonArray(response);

        List<StateMachine> result = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(json);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    StateMachine sm = new StateMachine();
                    sm.setId(UUID.randomUUID().toString().substring(0, 8));
                    sm.setName(node.path("name").asText("未命名状态机"));
                    sm.setDescription(node.path("description").asText(""));
                    sm.setStates(nodeToString(node.path("states"), "[]"));
                    sm.setTransitions(nodeToString(node.path("transitions"), "[]"));
                    sm.setForbiddenTransitions("[]");
                    sm.setConfidence(0.8);
                    sm.setSources(toJson(List.of("llm")));
                    result.add(sm);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse LLM state machine response", e);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
        return result;
    }

    private List<StateMachine> extractByRules(BackendResult backendResult) {
        List<StateMachine> result = new ArrayList<>();
        if (backendResult == null || backendResult.getEnums() == null) {
            return result;
        }

        for (EnumInfo enumInfo : backendResult.getEnums()) {
            String name = enumInfo.getName();
            if (name == null) {
                continue;
            }
            String lowerName = name.toLowerCase();
            if (lowerName.contains("status") || lowerName.contains("state")) {
                StateMachine sm = new StateMachine();
                sm.setId(UUID.randomUUID().toString().substring(0, 8));
                sm.setName(name + "StateMachine");
                sm.setDescription("基于枚举 " + name + " 自动提取的状态机");

                List<Map<String, Object>> states = new ArrayList<>();
                if (enumInfo.getValues() != null) {
                    for (EnumValue ev : enumInfo.getValues()) {
                        Map<String, Object> state = new LinkedHashMap<>();
                        state.put("name", ev.getName());
                        state.put("value", ev.getValue());
                        state.put("description", ev.getDescription());
                        states.add(state);
                    }
                }
                sm.setStates(toJson(states));
                sm.setTransitions("[]");
                sm.setForbiddenTransitions("[]");
                sm.setConfidence(0.5);
                sm.setSources(toJson(List.of("rule_based")));
                result.add(sm);
            }
        }
        return result;
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
                    return trimmed.substring(contentStart + 1, fenceEnd).trim();
                }
            }
        }

        int firstBracket = trimmed.indexOf('[');
        int lastBracket = trimmed.lastIndexOf(']');
        if (firstBracket != -1 && lastBracket != -1 && lastBracket > firstBracket) {
            return trimmed.substring(firstBracket, lastBracket + 1);
        }

        return trimmed;
    }

    private String nodeToString(JsonNode node, String defaultValue) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultValue;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
