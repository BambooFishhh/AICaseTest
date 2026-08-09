package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.dto.JsonHelper;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
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

@Component
public class TestGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(TestGeneratorAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    public List<TestCase> generate(List<StateMachine> stateMachines, BackendResult backendResult) {
        List<TestCase> result;
        try {
            result = generateByLlm(stateMachines, backendResult);
            if (result == null || result.isEmpty()) {
                log.warn("LLM test generation returned empty, falling back to rule-based");
                result = generateByRules(stateMachines, backendResult);
            }
        } catch (Exception e) {
            log.warn("LLM test generation failed, falling back to rule-based: {}", e.getMessage());
            result = generateByRules(stateMachines, backendResult);
        }

        int counter = 1;
        for (TestCase tc : result) {
            tc.setId(String.format("TC-%03d", counter++));
            tc.setCreatedAt(LocalDateTime.now());
        }
        return result;
    }

    private List<TestCase> generateByLlm(List<StateMachine> stateMachines, BackendResult backendResult) {
        Map<String, Object> context = new LinkedHashMap<>();

        List<Map<String, Object>> smList = new ArrayList<>();
        for (StateMachine sm : stateMachines) {
            Map<String, Object> smMap = new LinkedHashMap<>();
            smMap.put("name", sm.getName());
            smMap.put("description", sm.getDescription());
            smMap.put("states", JsonHelper.parseListMap(sm.getStates()));
            smMap.put("transitions", JsonHelper.parseListMap(sm.getTransitions()));
            smList.add(smMap);
        }
        context.put("stateMachines", smList);

        if (backendResult != null) {
            List<Map<String, Object>> endpointList = new ArrayList<>();
            if (backendResult.getEndpoints() != null) {
                for (EndpointInfo ep : backendResult.getEndpoints()) {
                    Map<String, Object> epMap = new LinkedHashMap<>();
                    epMap.put("method", ep.getMethod());
                    epMap.put("path", ep.getPath());
                    epMap.put("function", ep.getFunction());
                    endpointList.add(epMap);
                }
            }
            context.put("endpoints", endpointList);

            List<Map<String, Object>> ruleList = new ArrayList<>();
            if (backendResult.getBusinessRules() != null) {
                for (BusinessRule br : backendResult.getBusinessRules()) {
                    Map<String, Object> brMap = new LinkedHashMap<>();
                    brMap.put("function", br.getFunction());
                    brMap.put("rule", br.getRule());
                    brMap.put("ruleType", br.getRuleType());
                    ruleList.add(brMap);
                }
            }
            context.put("businessRules", ruleList);
        }

        String systemPrompt = "你是测试用例生成专家。根据提供的后端代码分析结果和状态机信息，生成全面的测试用例。"
                + "请返回JSON数组，每个测试用例包含：title(标题), module(模块), "
                + "type(类型: positive/negative/boundary/data), priority(优先级: P0/P1/P2/P3), "
                + "preconditions(前置条件字符串数组), steps(测试步骤字符串数组), expectedResults(预期结果字符串数组)。"
                + "只返回JSON数组，不要包含其他文字。";

        String userPrompt;
        try {
            userPrompt = "上下文信息：\n" + objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            userPrompt = "上下文信息：\n" + context.toString();
        }

        String response = llmService.chat(systemPrompt, userPrompt, 0.4);
        String json = extractJsonArray(response);

        List<TestCase> result = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(json);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    TestCase tc = new TestCase();
                    tc.setTitle(node.path("title").asText("未命名测试用例"));
                    tc.setModule(node.path("module").asText("未分类"));
                    tc.setType(node.path("type").asText("positive"));
                    tc.setPriority(node.path("priority").asText("P1"));
                    tc.setPreconditions(serializeStringArray(node.path("preconditions")));
                    tc.setSteps(serializeStringArray(node.path("steps")));
                    tc.setExpectedResults(serializeStringArray(node.path("expectedResults")));
                    tc.setStateMachineRef("{}");
                    tc.setSource("ai_generation");
                    tc.setConfidence(0.8);
                    result.add(tc);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse LLM test case response", e);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
        return result;
    }

    private List<TestCase> generateByRules(List<StateMachine> stateMachines, BackendResult backendResult) {
        List<TestCase> result = new ArrayList<>();

        if (stateMachines != null) {
            for (StateMachine sm : stateMachines) {
                List<Map<String, Object>> states = JsonHelper.parseListMap(sm.getStates());
                List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());

                result.add(buildPositiveTest(sm, transitions));
                result.add(buildNegativeTest(sm, states));
                result.add(buildBoundaryTest(sm, states));
            }
        }

        if (result.isEmpty() && backendResult != null && backendResult.getEndpoints() != null) {
            for (EndpointInfo endpoint : backendResult.getEndpoints()) {
                TestCase tc = new TestCase();
                tc.setTitle("验证接口 " + endpoint.getMethod() + " " + endpoint.getPath());
                tc.setModule("接口测试");
                tc.setType("positive");
                tc.setPriority("P1");
                tc.setPreconditions(toJsonList("服务正常运行"));
                tc.setSteps(toJsonList("调用接口 " + endpoint.getMethod() + " " + endpoint.getPath()));
                tc.setExpectedResults(toJsonList("接口应返回成功响应"));
                tc.setStateMachineRef("{}");
                tc.setSource("rule_based");
                tc.setConfidence(0.5);
                result.add(tc);
            }
        }

        return result;
    }

    private TestCase buildPositiveTest(StateMachine sm, List<Map<String, Object>> transitions) {
        TestCase tc = new TestCase();
        tc.setTitle("验证" + sm.getName() + "正常状态流转");
        tc.setModule("状态机测试");
        tc.setType("positive");
        tc.setPriority("P1");
        tc.setPreconditions(toJsonList("系统处于初始状态"));

        List<String> steps = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (Map<String, Object> t : transitions) {
            String from = String.valueOf(t.getOrDefault("from", ""));
            String to = String.valueOf(t.getOrDefault("to", ""));
            String trigger = String.valueOf(t.getOrDefault("trigger", ""));
            steps.add("触发状态转换(" + trigger + "): " + from + " -> " + to);
            expected.add("状态应从 " + from + " 变为 " + to);
        }
        if (steps.isEmpty()) {
            steps.add("验证系统初始状态");
            expected.add("系统应处于正确的初始状态");
        }

        tc.setSteps(toJsonList(steps));
        tc.setExpectedResults(toJsonList(expected));
        tc.setStateMachineRef("{}");
        tc.setSource("rule_based");
        tc.setConfidence(0.5);
        return tc;
    }

    private TestCase buildNegativeTest(StateMachine sm, List<Map<String, Object>> states) {
        TestCase tc = new TestCase();
        tc.setTitle("验证" + sm.getName() + "非法状态转换被拒绝");
        tc.setModule("状态机测试");
        tc.setType("negative");
        tc.setPriority("P1");
        tc.setPreconditions(toJsonList("系统处于某个已定义状态"));
        tc.setSteps(toJsonList("尝试执行非法的状态转换"));
        tc.setExpectedResults(toJsonList("系统应拒绝非法转换并保持原状态不变"));
        tc.setStateMachineRef("{}");
        tc.setSource("rule_based");
        tc.setConfidence(0.5);
        return tc;
    }

    private TestCase buildBoundaryTest(StateMachine sm, List<Map<String, Object>> states) {
        TestCase tc = new TestCase();
        tc.setTitle("验证" + sm.getName() + "边界状态处理");
        tc.setModule("状态机测试");
        tc.setType("boundary");
        tc.setPriority("P2");
        tc.setPreconditions(toJsonList("系统处于边界状态"));

        List<String> steps = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        if (!states.isEmpty()) {
            Map<String, Object> firstState = states.get(0);
            steps.add("验证初始边界状态: " + firstState.getOrDefault("name", ""));
            expected.add("系统应正确处于初始状态");
        }
        if (states.size() > 1) {
            Map<String, Object> lastState = states.get(states.size() - 1);
            steps.add("验证终态边界状态: " + lastState.getOrDefault("name", ""));
            expected.add("系统应正确处于终态");
        }
        if (steps.isEmpty()) {
            steps.add("验证边界状态下的系统行为");
            expected.add("系统应正确处理边界情况");
        }

        tc.setSteps(toJsonList(steps));
        tc.setExpectedResults(toJsonList(expected));
        tc.setStateMachineRef("{}");
        tc.setSource("rule_based");
        tc.setConfidence(0.5);
        return tc;
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

    private String serializeStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return "[]";
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(item.asText());
        }
        return toJsonList(list);
    }

    private String toJsonList(String... items) {
        return toJsonList(java.util.Arrays.asList(items));
    }

    private String toJsonList(List<String> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }
}
