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
        if (hasFrontendEvidence(frontendResult)) {
            try {
                enhanceWithFrontend(result, frontendResult);
            } catch (Exception e) {
                log.warn("Frontend state machine enhancement failed, keeping backend-only result: {}", e.getMessage());
            }
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

    private void enhanceWithFrontend(List<StateMachine> stateMachines, FrontendResult frontendResult) {
        if (stateMachines == null || stateMachines.isEmpty()) {
            return;
        }
        List<Map<String, Object>> stateMachineBriefs = new ArrayList<>();
        for (StateMachine sm : stateMachines) {
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("name", sm.getName());
            brief.put("states", JsonHelper.parseListMap(sm.getStates()));
            brief.put("transitions", JsonHelper.parseListMap(sm.getTransitions()));
            stateMachineBriefs.add(brief);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stateMachines", stateMachineBriefs);
        context.put("frontendEvidence", Map.of(
                "pageFlows", frontendResult.getPageFlows() == null ? List.of() : frontendResult.getPageFlows(),
                "apiCalls", frontendResult.getApiCalls() == null ? List.of() : frontendResult.getApiCalls(),
                "componentStates", frontendResult.getComponentStates() == null ? List.of() : frontendResult.getComponentStates()));

        String systemPrompt = promptSkillLoader.load("state-machine-frontend-enhancement", """
                你是状态机增强专家。后端枚举值是状态机的 ground truth，前端 pageFlows/apiCalls/componentStates 只是旁证。

                输入包含：
                - stateMachines：已有状态机的 states 和 transitions
                - frontendEvidence：页面跳转、接口调用、组件交互状态

                任务：
                1. 为每个状态机补充 transitions，补充 from/to/trigger/condition/endpoint（格式 METHOD /path）/order。
                2. from/to 只能使用该状态机 states 中已存在的 code，禁止新增 state。
                3. 前端证据只能用来推断 trigger、转换顺序和关联接口，不能虚构状态。
                4. 没有可补充内容的返回空数组。

                只返回纯 JSON 数组，不要 markdown 代码块，不要其他文字：
                [{"name":"状态机名","transitions":[{"from":"CREATED","to":"PAID","trigger":"支付","condition":"订单已创建","endpoint":"POST /api/orders/{id}/pay","order":1}]}]
                """);
        String userPrompt;
        try {
            userPrompt = "状态机与前端证据：\n" + objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            userPrompt = "状态机与前端证据：\n" + context.toString();
        }

        String response = llmService.chatWithAnalysis(systemPrompt, userPrompt, 0.3);
        mergeFrontendEnhancements(stateMachines, response);
    }

    private void mergeFrontendEnhancements(List<StateMachine> stateMachines, String response) {
        JsonNode array;
        try {
            array = objectMapper.readTree(extractJsonArray(response));
        } catch (Exception e) {
            log.warn("Failed to parse frontend state machine enhancement JSON: {}", e.getMessage());
            return;
        }
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            String name = node.path("name").asText("").trim();
            StateMachine sm = stateMachines.stream()
                    .filter(s -> name.equals(s.getName()))
                    .findFirst()
                    .orElse(null);
            if (sm == null || !node.has("transitions") || !node.get("transitions").isArray()) {
                continue;
            }
            List<Map<String, Object>> merged = new ArrayList<>(JsonHelper.parseListMap(sm.getTransitions()));
            Map<String, String> stateCodes = readStateCodeMap(sm);
            boolean changed = false;
            for (JsonNode transitionNode : node.get("transitions")) {
                Map<String, Object> transition = toMap(transitionNode);
                String fromRaw = text(transition, "from").trim();
                String toRaw = text(transition, "to").trim();
                String fromKey = normalizeState(fromRaw);
                String toKey = normalizeState(toRaw);
                if (fromRaw.isEmpty() || toRaw.isEmpty()) {
                    continue;
                }
                if (!stateCodes.containsKey(fromKey) || !stateCodes.containsKey(toKey)) {
                    log.warn("Drop frontend transition with unknown state: {} -> {}", fromKey, toKey);
                    continue;
                }
                String from = stateCodes.get(fromKey);
                String to = stateCodes.get(toKey);
                String trigger = text(transition, "trigger");
                boolean duplicate = merged.stream().anyMatch(t ->
                        fromKey.equals(normalizeState(text(t, "from")))
                                && toKey.equals(normalizeState(text(t, "to")))
                                && trigger.equalsIgnoreCase(text(t, "trigger")));
                if (duplicate) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("from", from);
                item.put("to", to);
                if (!trigger.isEmpty()) {
                    item.put("trigger", trigger);
                }
                String condition = text(transition, "condition");
                if (!condition.isEmpty()) {
                    item.put("condition", condition);
                }
                String endpoint = text(transition, "endpoint");
                if (!endpoint.isEmpty()) {
                    item.put("endpoint", endpoint);
                }
                if (transition.get("order") instanceof Number order) {
                    item.put("order", order.intValue());
                }
                merged.add(item);
                changed = true;
            }
            if (changed) {
                sm.setTransitions(toJson(merged));
                sm.setSources(toJson(List.of("backend", "frontend", "llm")));
                log.info("Frontend enhanced state machine: {}", sm.getName());
            }
        }
    }

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        return objectMapper.convertValue(node, Map.class);
    }

    private List<StateMachine> extractByLlm(BackendResult backendResult) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("enums", backendResult.getEnums());
        summary.put("entities", backendResult.getEntities());

        String systemPrompt = promptSkillLoader.load("state-machine-extraction",
                "你是状态机提取专家。根据提供的后端代码分析结果（枚举和常量），提取状态机信息。"
                + "请返回JSON数组，每个元素包含：name(状态机名称), description(描述), "
                + "states(状态数组，每个状态对象包含：name(中文名，便于测试人员理解，如'已支付')，"
                + "code(英文枚举原值，如'PAID'或'STATUS_PAID'，保持与代码一致)，"
                + "type(initial/normal/final)，description(描述)), "
                + "transitions(状态转换数组，每个转换的 from/to 必须使用对应状态的 code（英文枚举原值），"
                + "trigger 用中文动词描述，如'支付'/'发货')。"
                + "只返回JSON数组，不要包含其他文字。");

        String userPrompt;
        try {
            userPrompt = "后端代码分析结果：\n" + objectMapper.writeValueAsString(summary);
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
