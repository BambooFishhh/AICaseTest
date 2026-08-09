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

    // ==================== LLM 生成 ====================

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

        String systemPrompt = "你是测试用例生成专家。根据提供的后端代码分析结果和状态机信息，生成全面的、AI可执行的测试用例。"
                + "请返回JSON数组，每个测试用例包含："
                + "title(标题), module(模块), type(类型: positive/negative/boundary/data), "
                + "priority(优先级: P0/P1/P2/P3), "
                + "preconditions(前置条件字符串数组), steps(测试步骤简短描述字符串数组), "
                + "expectedResults(预期结果字符串数组), "
                + "structuredSteps(结构化步骤数组,每步含 order序号/action动作/target操作目标如API路径/expected该步预期/data输入数据对象/type步骤类型: api_call|ui_action|state_assert|manual), "
                + "apiEndpoints(关联的API端点数组,每项含 method/path/description), "
                + "testData(测试数据键值对对象), "
                + "executionHints(执行提示对象,含 approach: api_call|browser|manual, notes说明, prerequisites前置数组), "
                + "stateMachineRef(状态机引用对象,含 states数组/transitions数组(每项from/to/trigger)/forbiddenTransitions数组(每项from/to/reason)). "
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
                    // v1.1 结构化字段
                    tc.setStructuredSteps(nodeToJson(node.path("structuredSteps"), "[]"));
                    tc.setApiEndpoints(nodeToJson(node.path("apiEndpoints"), "[]"));
                    tc.setTestData(nodeToJson(node.path("testData"), "{}"));
                    tc.setExecutionHints(nodeToJson(node.path("executionHints"), "{}"));
                    tc.setStateMachineRef(nodeToJson(node.path("stateMachineRef"), "{}"));
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

    // ==================== 规则生成（LLM 失败回退） ====================

    private List<TestCase> generateByRules(List<StateMachine> stateMachines, BackendResult backendResult) {
        List<TestCase> result = new ArrayList<>();

        if (stateMachines != null) {
            for (StateMachine sm : stateMachines) {
                List<Map<String, Object>> states = JsonHelper.parseListMap(sm.getStates());
                List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
                List<Map<String, Object>> matchedEndpoints = matchEndpoints(backendResult, sm.getName());

                result.add(buildPositiveTest(sm, transitions, matchedEndpoints));
                result.add(buildNegativeTest(sm, states, transitions));
                result.add(buildBoundaryTest(sm, states, transitions));
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

                // v1.1 结构化字段
                List<Map<String, Object>> steps = new ArrayList<>();
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("order", 1);
                step.put("action", "调用接口");
                step.put("target", endpoint.getMethod() + " " + endpoint.getPath());
                step.put("expected", "接口返回成功响应");
                step.put("data", new LinkedHashMap<>());
                step.put("type", "api_call");
                steps.add(step);
                tc.setStructuredSteps(toJson(steps));

                List<Map<String, Object>> eps = new ArrayList<>();
                Map<String, Object> ep = new LinkedHashMap<>();
                ep.put("method", endpoint.getMethod());
                ep.put("path", endpoint.getPath());
                ep.put("description", endpoint.getFunction());
                eps.add(ep);
                tc.setApiEndpoints(toJson(eps));

                tc.setTestData("{}");
                Map<String, Object> hints = new LinkedHashMap<>();
                hints.put("approach", "api_call");
                hints.put("notes", "直接调用该接口验证");
                hints.put("prerequisites", toJsonList("服务正常运行"));
                tc.setExecutionHints(toJson(hints));
                tc.setStateMachineRef("{}");
                tc.setSource("rule_based");
                tc.setConfidence(0.5);
                result.add(tc);
            }
        }

        return result;
    }

    private TestCase buildPositiveTest(StateMachine sm, List<Map<String, Object>> transitions,
                                       List<Map<String, Object>> matchedEndpoints) {
        TestCase tc = new TestCase();
        tc.setTitle("验证" + sm.getName() + "正常状态流转");
        tc.setModule("状态机测试");
        tc.setType("positive");
        tc.setPriority("P1");
        tc.setPreconditions(toJsonList("系统处于初始状态"));

        List<String> steps = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        List<Map<String, Object>> structuredSteps = new ArrayList<>();
        int order = 1;
        for (Map<String, Object> t : transitions) {
            String from = String.valueOf(t.getOrDefault("from", ""));
            String to = String.valueOf(t.getOrDefault("to", ""));
            String trigger = String.valueOf(t.getOrDefault("trigger", ""));
            steps.add("触发状态转换(" + trigger + "): " + from + " -> " + to);
            expected.add("状态应从 " + from + " 变为 " + to);

            Map<String, Object> sStep = new LinkedHashMap<>();
            sStep.put("order", order++);
            sStep.put("action", "触发" + trigger);
            sStep.put("target", "状态转换 " + from + " -> " + to);
            sStep.put("expected", "状态从 " + from + " 变为 " + to);
            sStep.put("data", new LinkedHashMap<>());
            sStep.put("type", "state_assert");
            structuredSteps.add(sStep);
        }
        if (steps.isEmpty()) {
            steps.add("验证系统初始状态");
            expected.add("系统应处于正确的初始状态");
            Map<String, Object> sStep = new LinkedHashMap<>();
            sStep.put("order", 1);
            sStep.put("action", "验证系统初始状态");
            sStep.put("target", "系统初始状态");
            sStep.put("expected", "系统处于正确的初始状态");
            sStep.put("data", new LinkedHashMap<>());
            sStep.put("type", "state_assert");
            structuredSteps.add(sStep);
        }

        tc.setSteps(toJsonList(steps));
        tc.setExpectedResults(toJsonList(expected));
        tc.setStructuredSteps(toJson(structuredSteps));
        tc.setApiEndpoints(toJson(matchedEndpoints));
        tc.setTestData("{}");
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("approach", matchedEndpoints.isEmpty() ? "manual" : "api_call");
        hints.put("notes", "按状态机正向流转依次触发各状态转换");
        hints.put("prerequisites", toJsonList("系统处于初始状态"));
        tc.setExecutionHints(toJson(hints));
        tc.setStateMachineRef(buildStateMachineRef(sm, transitions, false));
        tc.setSource("rule_based");
        tc.setConfidence(0.5);
        return tc;
    }

    private TestCase buildNegativeTest(StateMachine sm, List<Map<String, Object>> states,
                                       List<Map<String, Object>> transitions) {
        TestCase tc = new TestCase();
        tc.setTitle("验证" + sm.getName() + "非法状态转换被拒绝");
        tc.setModule("状态机测试");
        tc.setType("negative");
        tc.setPriority("P1");
        tc.setPreconditions(toJsonList("系统处于某个已定义状态"));

        // 构造若干反向/非法转换作为 forbiddenTransitions
        List<Map<String, Object>> forbidden = buildForbiddenTransitions(states, transitions);

        List<Map<String, Object>> structuredSteps = new ArrayList<>();
        int order = 1;
        for (Map<String, Object> f : forbidden) {
            Map<String, Object> sStep = new LinkedHashMap<>();
            sStep.put("order", order++);
            sStep.put("action", "尝试非法转换: " + f.get("from") + " -> " + f.get("to"));
            sStep.put("target", "状态转换 " + f.get("from") + " -> " + f.get("to"));
            sStep.put("expected", "系统应拒绝该转换: " + f.getOrDefault("reason", ""));
            sStep.put("data", new LinkedHashMap<>());
            sStep.put("type", "state_assert");
            structuredSteps.add(sStep);
        }
        if (structuredSteps.isEmpty()) {
            Map<String, Object> sStep = new LinkedHashMap<>();
            sStep.put("order", 1);
            sStep.put("action", "尝试执行非法的状态转换");
            sStep.put("target", "非法状态转换");
            sStep.put("expected", "系统应拒绝非法转换并保持原状态不变");
            sStep.put("data", new LinkedHashMap<>());
            sStep.put("type", "state_assert");
            structuredSteps.add(sStep);
        }

        tc.setSteps(toJsonList("尝试执行非法的状态转换"));
        tc.setExpectedResults(toJsonList("系统应拒绝非法转换并保持原状态不变"));
        tc.setStructuredSteps(toJson(structuredSteps));
        tc.setApiEndpoints("[]");
        tc.setTestData("{}");
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("approach", "manual");
        hints.put("notes", "构造非法状态转换验证系统拒绝能力");
        hints.put("prerequisites", toJsonList("系统处于某个已定义状态"));
        tc.setExecutionHints(toJson(hints));
        tc.setStateMachineRef(buildStateMachineRef(sm, transitions, true, forbidden));
        tc.setSource("rule_based");
        tc.setConfidence(0.5);
        return tc;
    }

    private TestCase buildBoundaryTest(StateMachine sm, List<Map<String, Object>> states,
                                       List<Map<String, Object>> transitions) {
        TestCase tc = new TestCase();
        tc.setTitle("验证" + sm.getName() + "边界状态处理");
        tc.setModule("状态机测试");
        tc.setType("boundary");
        tc.setPriority("P2");
        tc.setPreconditions(toJsonList("系统处于边界状态"));

        List<String> steps = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        List<Map<String, Object>> structuredSteps = new ArrayList<>();
        int order = 1;
        if (!states.isEmpty()) {
            Map<String, Object> firstState = states.get(0);
            steps.add("验证初始边界状态: " + firstState.getOrDefault("name", ""));
            expected.add("系统应正确处于初始状态");
            Map<String, Object> sStep = new LinkedHashMap<>();
            sStep.put("order", order++);
            sStep.put("action", "验证初始边界状态");
            sStep.put("target", "初始状态: " + firstState.getOrDefault("name", ""));
            sStep.put("expected", "系统应正确处于初始状态");
            sStep.put("data", new LinkedHashMap<>());
            sStep.put("type", "state_assert");
            structuredSteps.add(sStep);
        }
        if (states.size() > 1) {
            Map<String, Object> lastState = states.get(states.size() - 1);
            steps.add("验证终态边界状态: " + lastState.getOrDefault("name", ""));
            expected.add("系统应正确处于终态");
            Map<String, Object> sStep = new LinkedHashMap<>();
            sStep.put("order", order++);
            sStep.put("action", "验证终态边界状态");
            sStep.put("target", "终态: " + lastState.getOrDefault("name", ""));
            sStep.put("expected", "系统应正确处于终态");
            sStep.put("data", new LinkedHashMap<>());
            sStep.put("type", "state_assert");
            structuredSteps.add(sStep);
        }
        if (steps.isEmpty()) {
            steps.add("验证边界状态下的系统行为");
            expected.add("系统应正确处理边界情况");
            Map<String, Object> sStep = new LinkedHashMap<>();
            sStep.put("order", 1);
            sStep.put("action", "验证边界状态下的系统行为");
            sStep.put("target", "边界状态");
            sStep.put("expected", "系统应正确处理边界情况");
            sStep.put("data", new LinkedHashMap<>());
            sStep.put("type", "state_assert");
            structuredSteps.add(sStep);
        }

        tc.setSteps(toJsonList(steps));
        tc.setExpectedResults(toJsonList(expected));
        tc.setStructuredSteps(toJson(structuredSteps));
        tc.setApiEndpoints("[]");
        tc.setTestData("{}");
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("approach", "manual");
        hints.put("notes", "验证状态机的初始与终态边界处理");
        hints.put("prerequisites", toJsonList("系统处于边界状态"));
        tc.setExecutionHints(toJson(hints));
        tc.setStateMachineRef(buildStateMachineRef(sm, transitions, false));
        tc.setSource("rule_based");
        tc.setConfidence(0.5);
        return tc;
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造状态机引用 JSON。includeForbidden 为 true 时附带 forbiddenTransitions。
     */
    private String buildStateMachineRef(StateMachine sm, List<Map<String, Object>> transitions,
                                        boolean includeForbidden) {
        return buildStateMachineRef(sm, transitions, includeForbidden, new ArrayList<>());
    }

    private String buildStateMachineRef(StateMachine sm, List<Map<String, Object>> transitions,
                                        boolean includeForbidden, List<Map<String, Object>> forbidden) {
        Map<String, Object> ref = new LinkedHashMap<>();
        List<Map<String, Object>> states = JsonHelper.parseListMap(sm.getStates());
        ref.put("states", states);
        ref.put("transitions", transitions);
        if (includeForbidden) {
            ref.put("forbiddenTransitions", forbidden);
        }
        return toJson(ref);
    }

    /**
     * 基于状态机状态构造若干反向/非法转换（简单启发式：反转已有转换并标记为 forbidden）。
     */
    private List<Map<String, Object>> buildForbiddenTransitions(List<Map<String, Object>> states,
                                                                List<Map<String, Object>> transitions) {
        List<Map<String, Object>> forbidden = new ArrayList<>();
        if (transitions != null && !transitions.isEmpty()) {
            // 取首条转换的反向作为示例非法转换
            Map<String, Object> first = transitions.get(0);
            Map<String, Object> reverse = new LinkedHashMap<>();
            reverse.put("from", first.getOrDefault("to", ""));
            reverse.put("to", first.getOrDefault("from", ""));
            reverse.put("reason", "反向转换通常不被允许");
            forbidden.add(reverse);
        }
        // 若有终态，构造从终态出发的非法转换
        if (states != null) {
            for (Map<String, Object> s : states) {
                Object isTerminal = s.get("is_terminal");
                if (Boolean.TRUE.equals(isTerminal) || "true".equals(String.valueOf(isTerminal))) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("from", s.getOrDefault("id", s.getOrDefault("name", "")));
                    f.put("to", states.get(0).getOrDefault("id", states.get(0).getOrDefault("name", "")));
                    f.put("reason", "终态不可再转换");
                    forbidden.add(f);
                    break;
                }
            }
        }
        return forbidden;
    }

    /**
     * 按状态机名称关键词匹配后端 API 端点。
     */
    private List<Map<String, Object>> matchEndpoints(BackendResult backendResult, String smName) {
        List<Map<String, Object>> matched = new ArrayList<>();
        if (backendResult == null || backendResult.getEndpoints() == null || smName == null) {
            return matched;
        }
        String keyword = smName.replace("状态机", "").replace("状态", "").trim();
        for (EndpointInfo ep : backendResult.getEndpoints()) {
            String function = ep.getFunction() == null ? "" : ep.getFunction();
            String path = ep.getPath() == null ? "" : ep.getPath();
            if (!keyword.isEmpty()
                    && (function.toLowerCase().contains(keyword.toLowerCase())
                    || path.toLowerCase().contains(keyword.toLowerCase()))) {
                Map<String, Object> epMap = new LinkedHashMap<>();
                epMap.put("method", ep.getMethod());
                epMap.put("path", ep.getPath());
                epMap.put("description", ep.getFunction());
                matched.add(epMap);
            }
        }
        return matched;
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

    private String nodeToJson(JsonNode node, String defaultJson) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return defaultJson;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return defaultJson;
        }
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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
