package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.EnumInfo;
import com.testagent.analyzer.result.EnumValue;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.JsonHelper;
import com.testagent.entity.StateMachine;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
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

    @Autowired
    private PromptSkillLoader promptSkillLoader;

    public List<StateMachine> extract(BackendResult backendResult) {
        return extract(backendResult, null);
    }

    // v5.13: 状态机以前端 pageFlows/apiCalls/componentStates 为旁证做 LLM 增强
    public List<StateMachine> extract(BackendResult backendResult, FrontendResult frontendResult) {
        // v6.2: 状态机提取 + 前端增强合并为 1 次 LLM 调用（原先是 2 次串行调用）。
        List<StateMachine> result;
        try {
            result = extractByLlm(backendResult, frontendResult);
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

    private boolean hasFrontendEvidence(FrontendResult frontendResult) {
        return frontendResult != null
                && (isNotEmpty(frontendResult.getPageFlows())
                || isNotEmpty(frontendResult.getApiCalls())
                || isNotEmpty(frontendResult.getComponentStates()));
    }

    private boolean isNotEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    // v7.4(A19): 已删除 enhanceWithFrontend / mergeFrontendEnhancements / toMap——
    // v6.2 将状态机提取与前端增强合并为单次 LLM 调用后，这批方法（约 120 行）无调用方。

    private Map<String, String> readStateCodeMap(StateMachine sm) {
        Map<String, String> codes = new LinkedHashMap<>();
        for (Map<String, Object> state : JsonHelper.parseListMap(sm.getStates())) {
            String code = firstNonBlank(text(state, "code"), text(state, "name"), text(state, "value"));
            if (!code.isEmpty()) {
                codes.putIfAbsent(code.trim().toLowerCase(), code.trim());
            }
        }
        return codes;
    }

    private String normalizeState(String state) {
        return state == null ? "" : state.trim().toLowerCase();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private List<StateMachine> extractByLlm(BackendResult backendResult, FrontendResult frontendResult) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enums", backendResult.getEnums());
        summary.put("entities", backendResult.getEntities());

        boolean withFrontend = hasFrontendEvidence(frontendResult);
        if (withFrontend) {
            summary.put("frontendEvidence", Map.of(
                    "pageFlows", frontendResult.getPageFlows() == null ? List.of() : frontendResult.getPageFlows(),
                    "apiCalls", frontendResult.getApiCalls() == null ? List.of() : frontendResult.getApiCalls(),
                    "componentStates", frontendResult.getComponentStates() == null ? List.of() : frontendResult.getComponentStates()));
        }

        String systemPrompt = promptSkillLoader.load("state-machine-extraction",
                "你是状态机提取专家。后端枚举值是状态机的 ground truth，前端 pageFlows/apiCalls/componentStates 只是旁证。"
                + "根据提供的后端代码分析结果（枚举和常量）提取状态机信息。"
                + "请返回JSON数组，每个元素包含：name(状态机名称), description(描述), "
                + "states(状态数组，每个状态对象包含：name(中文名，便于测试人员理解，如'已支付')，"
                + "code(英文枚举原值，如'PAID'或'STATUS_PAID'，保持与代码一致)，"
                + "type(initial/normal/final)，description(描述)), "
                + "transitions(状态转换数组，每个转换的 from/to 必须使用对应状态的 code（英文枚举原值），"
                + (withFrontend
                        ? "可用 frontendEvidence 推断 trigger(中文动词，如'支付'/'发货')/condition/endpoint(METHOD /path)/order，但不得虚构状态；"
                        : "trigger 用中文动词描述，如'支付'/'发货'；")
                + "只返回该状态机 states 中已存在的 code)。"
                + "只返回JSON数组，不要包含其他文字。");

        String userPrompt;
        try {
            userPrompt = "后端代码分析结果" + (withFrontend ? "与前端证据" : "") + "：\n"
                    + objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            userPrompt = "后端代码分析结果：\n" + summary.toString();
        }

        String response = llmService.chatWithAnalysis(systemPrompt, userPrompt, 0.3);
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
        validateTransitions(result, withFrontend);
        return result;
    }

    // v6.2: 确定性校验/去重——transitions 的 from/to 必须落在该状态机 states 的 code 内，并归一化为规范 code。
    private void validateTransitions(List<StateMachine> stateMachines, boolean withFrontend) {
        for (StateMachine sm : stateMachines) {
            Map<String, String> codes = readStateCodeMap(sm);
            List<Map<String, Object>> valid = new ArrayList<>();
            for (Map<String, Object> t : JsonHelper.parseListMap(sm.getTransitions())) {
                String fromRaw = text(t, "from").trim();
                String toRaw = text(t, "to").trim();
                String fromKey = normalizeState(fromRaw);
                String toKey = normalizeState(toRaw);
                if (fromRaw.isEmpty() || toRaw.isEmpty()
                        || !codes.containsKey(fromKey) || !codes.containsKey(toKey)) {
                    log.warn("Drop state machine transition with unknown state in {}: {} -> {}",
                            sm.getName(), fromRaw, toRaw);
                    continue;
                }
                String from = codes.get(fromKey);
                String to = codes.get(toKey);
                String trigger = text(t, "trigger");
                boolean duplicate = valid.stream().anyMatch(v ->
                        from.equals(text(v, "from"))
                                && to.equals(text(v, "to"))
                                && trigger.equalsIgnoreCase(text(v, "trigger")));
                if (duplicate) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("from", from);
                item.put("to", to);
                if (!trigger.isEmpty()) {
                    item.put("trigger", trigger);
                }
                String condition = text(t, "condition");
                if (!condition.isEmpty()) {
                    item.put("condition", condition);
                }
                String endpoint = text(t, "endpoint");
                if (!endpoint.isEmpty()) {
                    item.put("endpoint", endpoint);
                }
                if (t.get("order") instanceof Number order) {
                    item.put("order", order.intValue());
                }
                valid.add(item);
            }
            sm.setTransitions(toJson(valid));
            List<String> sources = new ArrayList<>(List.of("llm"));
            if (withFrontend) {
                sources.add("frontend");
            }
            sm.setSources(toJson(sources));
        }
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
                        // v4.5: 双字段——code 与 name 一致（英文枚举），展示层按需翻译
                        state.put("code", ev.getName());
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
