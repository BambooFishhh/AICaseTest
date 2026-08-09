package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TestGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(TestGeneratorAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // v1.6: 进度回调接口，供调用方感知分模块生成进度
    @FunctionalInterface
    public interface ProgressCallback {
        void update(String message);
    }

    // v1.4: 结构化分段 system prompt
    private static final String SYSTEM_PROMPT = """
            # 角色
            你是资深测试工程师，擅长生成结构化、AI 可执行的测试用例。

            # 任务
            根据以下状态机和接口信息，为每个状态转换生成测试用例。

            # 生成要求
            ## 数量引导
            - 正向用例（positive）：每个合法状态转换至少 1 条
            - 异常用例（negative）：每个状态转换至少 1 条非法输入/非法转换
            - 边界值用例（boundary）：涉及数值/长度字段的至少 2 条（上界+下界）
            - 数据驱动用例（data）：多参数组合场景至少 1 条

            ## 测试数据要求
            - testData 必须包含具体字段值，不能为空对象 {}
            - 数值字段：填入真实值和边界值（如 amount: 0, amount: -1, amount: 99999999）
            - 字符串字段：填入正常值、空字符串、超长字符串（256字符）
            - 枚举字段：填入合法值和非法枚举值
            - 必填字段：测试缺失该字段的情况

            ## structuredSteps 要求
            - 每步的 target 必须是具体操作目标（如 "POST /api/order/create"），不能为空
            - 每步的 expected 必须是可验证的具体结果，不能为空
            - api_call 类型步骤的 data 必须包含该步骤的输入参数

            ## stateMachineRef 要求
            - transitions 数组必须包含本用例测试的状态转换
            - forbiddenTransitions 仅在 negative 类型用例中填写

            # 输出格式
            返回 JSON 数组，字段说明：
            - title: 用例标题（简洁，含测试目标）
            - module: 所属模块
            - type: positive/negative/boundary/data
            - priority: P0/P1/P2/P3
            - preconditions: 前置条件数组
            - steps: 步骤简述数组
            - expectedResults: 预期结果数组
            - structuredSteps: [{order, action, target, expected, data, type}]
            - apiEndpoints: [{method, path, description}]
            - testData: {字段名: 值}
            - executionHints: {approach, notes, prerequisites}
            - stateMachineRef: {states, transitions, forbiddenTransitions}

            只返回 JSON 数组，不要包含其他文字。
            """;

    // v1.10: PRD 驱动 system prompt（PRD 为主、代码为辅）
    private static final String SYSTEM_PROMPT_PRD_DRIVEN = """
            # 角色
            你是资深测试工程师，以需求为源生成结构化、AI 可执行的测试用例。

            # 任务
            根据【需求上下文（PRD 解析结果）】为主，【代码上下文（状态机/接口/业务规则）】为辅，生成测试用例。

            # 生成要求
            ## 以需求为纲
            - 遍历 requirements 数组，每个需求项至少生成 1 条正向用例 + 1 条异常用例
            - 涉及数值/长度字段的，补充边界值用例（上界 + 下界）
            - 优先级遵循需求项的 priority；未标注的按 P1
            - module 取自 PRD 的 modules；type 取值 positive/negative/boundary/data

            ## 代码信息用于补充（不作为用例来源，只增强可执行性）
            - endpoints：用例 structuredSteps 的 target 用真实接口路径（如 POST /api/order/create）
            - stateMachines：用例的 stateMachineRef 引用真实状态流转
            - businessRules：补充为前置条件或异常场景

            ## structuredSteps / testData / executionHints 要求
            - 同 v1.4 质量标准（target 不能为空、expected 可验证、testData 含具体字段值）

            # 输出格式（同 v1.4）
            返回 JSON 数组，字段：title/module/type/priority/preconditions/steps/expectedResults/
            structuredSteps/apiEndpoints/testData/executionHints/stateMachineRef
            只返回 JSON 数组，不要包含其他文字。
            """;

    // v1.4: few-shot 示例（1 正向 + 1 异常）
    private static final String FEW_SHOT_EXAMPLES = """
            # 示例（参考质量标准，不要原样复制）
            [
              {
                "title": "创建订单-正常流程",
                "module": "订单管理",
                "type": "positive",
                "priority": "P0",
                "preconditions": ["用户已登录", "购物车有商品"],
                "steps": ["调用创建订单接口", "验证返回订单号", "验证订单状态为待支付"],
                "expectedResults": ["接口返回201和订单号", "订单状态=PENDING_PAYMENT"],
                "structuredSteps": [
                  {"order":1,"action":"创建订单","target":"POST /api/order/create","expected":"返回201和orderId","data":{"userId":"U001","items":[{"skuId":"SKU001","quantity":2}],"amount":99.90},"type":"api_call"},
                  {"order":2,"action":"验证订单状态","target":"GET /api/order/{orderId}","expected":"status=PENDING_PAYMENT","data":{},"type":"state_assert"}
                ],
                "apiEndpoints": [{"method":"POST","path":"/api/order/create","description":"创建订单"}],
                "testData": {"userId":"U001","amount":99.90},
                "executionHints": {"approach":"api_call","notes":"先创建再查询验证状态","prerequisites":["用户已登录"]},
                "stateMachineRef": {"states":[],"transitions":[{"from":"NONE","to":"PENDING_PAYMENT","trigger":"create"}],"forbiddenTransitions":[]}
              },
              {
                "title": "创建订单-金额为负数",
                "module": "订单管理",
                "type": "negative",
                "priority": "P1",
                "preconditions": ["用户已登录"],
                "steps": ["传入负数金额创建订单", "验证接口拒绝"],
                "expectedResults": ["接口返回400","错误消息提示金额非法"],
                "structuredSteps": [
                  {"order":1,"action":"传入负数金额创建订单","target":"POST /api/order/create","expected":"返回400错误","data":{"userId":"U001","amount":-1},"type":"api_call"}
                ],
                "apiEndpoints": [{"method":"POST","path":"/api/order/create","description":"创建订单"}],
                "testData": {"userId":"U001","amount":-1},
                "executionHints": {"approach":"api_call","notes":"验证金额校验逻辑","prerequisites":["用户已登录"]},
                "stateMachineRef": {"states":[],"transitions":[],"forbiddenTransitions":[{"from":"PENDING_PAYMENT","to":"NONE","reason":"金额非法不可创建"}]}
              }
            ]
            """;

    @Autowired
    private LlmService llmService;

    // ==================== 主生成流程（v1.2 分模块生成） ====================

    public List<TestCase> generate(List<StateMachine> stateMachines, BackendResult backendResult) {
        return generate(stateMachines, backendResult, null);
    }

    // v1.6: 支持 ProgressCallback 的重载，调用方可感知分模块生成进度
    public List<TestCase> generate(List<StateMachine> stateMachines, BackendResult backendResult,
                                   ProgressCallback progressCallback) {
        // v1.10: 委托给 PRD 驱动重载（prdResult=null 时退化为原代码驱动逻辑）
        return generate(null, stateMachines, backendResult, progressCallback);
    }

    // v1.10: PRD 驱动重载。prdResult 非空时以 PRD 为主生成；为空时退化为代码驱动
    public List<TestCase> generate(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                   BackendResult backendResult, ProgressCallback progressCallback) {
        List<TestCase> result = new ArrayList<>();

        // v1.10: PRD 驱动分支
        if (prdResult != null && !prdResult.isEmpty()) {
            if (progressCallback != null) {
                progressCallback.update("基于 PRD 生成用例...");
            }
            try {
                result = generateByLlmWithPrd(prdResult, stateMachines, backendResult);
            } catch (Exception e) {
                log.warn("PRD-driven generation failed, fallback to code-driven: {}", e.getMessage());
                result = new ArrayList<>();
            }
        }

        // 代码驱动分支（PRD 为空或 PRD 生成失败时）
        if (result.isEmpty()) {
            result = generateCodeDrivenCases(stateMachines, backendResult, progressCallback);
        }

        if (progressCallback != null) {
            progressCallback.update("正在质量评分与去重...");
        }
        calculateQualityScores(result);
        result = deduplicate(result);

        int counter = 1;
        for (TestCase tc : result) {
            tc.setId(String.format("TC-%03d", counter++));
            tc.setCreatedAt(LocalDateTime.now());
        }
        return result;
    }

    // v1.10: 原代码驱动生成逻辑（状态机/endpoint），从 generate 抽出供 PRD 失败时复用
    private List<TestCase> generateCodeDrivenCases(List<StateMachine> stateMachines, BackendResult backendResult,
                                                    ProgressCallback progressCallback) {
        List<TestCase> result = new ArrayList<>();

        if (stateMachines != null && !stateMachines.isEmpty()) {
            int total = stateMachines.size();
            for (int i = 0; i < total; i++) {
                StateMachine sm = stateMachines.get(i);
                if (progressCallback != null) {
                    progressCallback.update(String.format("正在生成第 %d/%d 个模块: %s", i + 1, total, sm.getName()));
                }
                List<TestCase> moduleCases;
                try {
                    moduleCases = generateByLlmForStateMachine(sm, backendResult);
                    if (moduleCases == null || moduleCases.isEmpty()) {
                        log.warn("LLM returned empty for state machine {}, using rules", sm.getName());
                        moduleCases = generateByRulesForStateMachine(sm, backendResult);
                    }
                } catch (Exception e) {
                    log.warn("LLM generation failed for state machine {}, falling back to rules: {}",
                            sm.getName(), e.getMessage());
                    moduleCases = generateByRulesForStateMachine(sm, backendResult);
                }
                result.addAll(moduleCases);
            }
        }

        // 无状态机时按 endpoints 生成
        if (result.isEmpty() && backendResult != null && backendResult.getEndpoints() != null) {
            if (progressCallback != null) {
                progressCallback.update("无状态机，按接口生成用例...");
            }
            result = generateByEndpoints(backendResult);
        }
        return result;
    }

    // ==================== LLM 生成（分模块） ====================

    private List<TestCase> generateByLlmForStateMachine(StateMachine sm, BackendResult backendResult) {
        Map<String, Object> context = new LinkedHashMap<>();

        Map<String, Object> smMap = new LinkedHashMap<>();
        smMap.put("name", sm.getName());
        smMap.put("description", sm.getDescription());
        smMap.put("states", JsonHelper.parseListMap(sm.getStates()));
        smMap.put("transitions", JsonHelper.parseListMap(sm.getTransitions()));
        context.put("stateMachine", smMap);

        // 按模块名匹配相关端点
        List<Map<String, Object>> matchedEndpoints = matchEndpoints(backendResult, sm.getName());
        context.put("endpoints", matchedEndpoints);

        // business rules
        List<Map<String, Object>> ruleList = new ArrayList<>();
        if (backendResult != null && backendResult.getBusinessRules() != null) {
            for (BusinessRule br : backendResult.getBusinessRules()) {
                Map<String, Object> brMap = new LinkedHashMap<>();
                brMap.put("function", br.getFunction());
                brMap.put("rule", br.getRule());
                brMap.put("ruleType", br.getRuleType());
                ruleList.add(brMap);
            }
        }
        context.put("businessRules", ruleList);

        String systemPrompt = SYSTEM_PROMPT;

        String userPrompt;
        try {
            userPrompt = "上下文信息：\n" + objectMapper.writeValueAsString(context)
                    + "\n\n" + FEW_SHOT_EXAMPLES
                    + "\n\n请基于上下文生成测试用例。";
        } catch (Exception e) {
            userPrompt = "上下文信息：\n" + context.toString()
                    + "\n\n" + FEW_SHOT_EXAMPLES
                    + "\n\n请基于上下文生成测试用例。";
        }

        String response = llmService.chat(systemPrompt, userPrompt, 0.4);
        String json = extractJsonArray(response);

        return parseTestCases(json);
    }

    // v1.10: PRD 驱动的 LLM 生成（PRD 为主、代码为辅）
    private List<TestCase> generateByLlmWithPrd(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                                 BackendResult backendResult) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();

        // PRD 为主上下文
        context.put("prd", objectMapper.convertValue(prdResult, Map.class));

        // 代码侧为辅（精简，避免 token 超限）
        List<Map<String, Object>> smList = new ArrayList<>();
        if (stateMachines != null) {
            for (StateMachine sm : stateMachines) {
                Map<String, Object> smMap = new LinkedHashMap<>();
                smMap.put("name", sm.getName());
                smMap.put("states", JsonHelper.parseListMap(sm.getStates()));
                smMap.put("transitions", JsonHelper.parseListMap(sm.getTransitions()));
                smList.add(smMap);
            }
        }
        context.put("stateMachines", smList);

        List<Map<String, Object>> epList = new ArrayList<>();
        if (backendResult != null && backendResult.getEndpoints() != null) {
            for (EndpointInfo ep : backendResult.getEndpoints()) {
                Map<String, Object> epMap = new LinkedHashMap<>();
                epMap.put("method", ep.getMethod());
                epMap.put("path", ep.getPath());
                epMap.put("description", ep.getFunction());
                epList.add(epMap);
            }
        }
        context.put("endpoints", epList);

        List<Map<String, Object>> ruleList = new ArrayList<>();
        if (backendResult != null && backendResult.getBusinessRules() != null) {
            for (BusinessRule br : backendResult.getBusinessRules()) {
                Map<String, Object> brMap = new LinkedHashMap<>();
                brMap.put("function", br.getFunction());
                brMap.put("rule", br.getRule());
                brMap.put("ruleType", br.getRuleType());
                ruleList.add(brMap);
            }
        }
        context.put("businessRules", ruleList);

        String userPrompt = "上下文信息：\n" + objectMapper.writeValueAsString(context)
                + "\n\n" + FEW_SHOT_EXAMPLES
                + "\n\n请以 PRD 需求为纲生成测试用例，代码信息用于补充接口路径与前置状态。";
        String response = llmService.chat(SYSTEM_PROMPT_PRD_DRIVEN, userPrompt, 0.4);
        String json = extractJsonArray(response);
        return parseTestCases(json);
    }

    private List<TestCase> parseTestCases(String json) {
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

    // ==================== 规则生成（单模块回退） ====================

    private List<TestCase> generateByRulesForStateMachine(StateMachine sm, BackendResult backendResult) {
        List<TestCase> result = new ArrayList<>();
        List<Map<String, Object>> states = JsonHelper.parseListMap(sm.getStates());
        List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
        List<Map<String, Object>> matchedEndpoints = matchEndpoints(backendResult, sm.getName());

        result.add(buildPositiveTest(sm, transitions, matchedEndpoints));
        result.add(buildNegativeTest(sm, states, transitions));
        result.add(buildBoundaryTest(sm, states, transitions));
        return result;
    }

    private List<TestCase> generateByEndpoints(BackendResult backendResult) {
        List<TestCase> result = new ArrayList<>();
        for (EndpointInfo endpoint : backendResult.getEndpoints()) {
            TestCase tc = new TestCase();
            tc.setTitle("验证接口 " + endpoint.getMethod() + " " + endpoint.getPath());
            tc.setModule("接口测试");
            tc.setType("positive");
            tc.setPriority("P1");
            tc.setPreconditions(toJsonList("服务正常运行"));
            tc.setSteps(toJsonList("调用接口 " + endpoint.getMethod() + " " + endpoint.getPath()));
            tc.setExpectedResults(toJsonList("接口应返回成功响应"));

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

    // ==================== 去重（v1.2） ====================

    private List<TestCase> deduplicate(List<TestCase> cases) {
        List<TestCase> result = new ArrayList<>();
        for (TestCase tc : cases) {
            boolean isDup = false;
            for (int i = 0; i < result.size(); i++) {
                TestCase existing = result.get(i);
                if (isDuplicate(tc, existing)) {
                    // 保留 qualityScore 更高者
                    int tcScore = tc.getQualityScore() == null ? 0 : tc.getQualityScore();
                    int existScore = existing.getQualityScore() == null ? 0 : existing.getQualityScore();
                    if (tcScore > existScore) {
                        result.set(i, tc);
                    }
                    isDup = true;
                    break;
                }
            }
            if (!isDup) {
                result.add(tc);
            }
        }
        log.info("Deduplication: {} cases -> {} cases", cases.size(), result.size());
        return result;
    }

    private boolean isDuplicate(TestCase a, TestCase b) {
        String titleA = a.getTitle() == null ? "" : a.getTitle().trim();
        String titleB = b.getTitle() == null ? "" : b.getTitle().trim();
        if (titleA.isEmpty() || titleB.isEmpty()) {
            return false;
        }
        // 标题完全相同
        if (titleA.equals(titleB)) {
            return true;
        }
        // 同模块才判重
        String modA = a.getModule() == null ? "" : a.getModule();
        String modB = b.getModule() == null ? "" : b.getModule();
        if (modA.equals(modB)) {
            // 子串包含关系
            if (titleA.contains(titleB) || titleB.contains(titleA)) {
                return true;
            }
            // 短标题字符重叠率 > 80%
            if (titleA.length() <= 20 && titleB.length() <= 20) {
                Set<Character> setA = new HashSet<>();
                for (char c : titleA.toCharArray()) setA.add(c);
                Set<Character> setB = new HashSet<>();
                for (char c : titleB.toCharArray()) setB.add(c);
                Set<Character> intersection = new HashSet<>(setA);
                intersection.retainAll(setB);
                int maxLen = Math.max(setA.size(), setB.size());
                if (maxLen > 0 && (double) intersection.size() / maxLen > 0.8) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 质量评分（v1.2） ====================

    private void calculateQualityScores(List<TestCase> cases) {
        for (TestCase tc : cases) {
            tc.setQualityScore(calculateQualityScore(tc));
        }
    }

    private int calculateQualityScore(TestCase tc) {
        int score = 0;

        // 结构化步骤完整 30
        List<Map<String, Object>> sSteps = JsonHelper.parseListMap(tc.getStructuredSteps());
        if (!sSteps.isEmpty()) {
            boolean allComplete = true;
            for (Map<String, Object> s : sSteps) {
                if (s.get("action") == null || s.get("target") == null || s.get("expected") == null) {
                    allComplete = false;
                    break;
                }
            }
            score += allComplete ? 30 : 15;
        }

        // 关联接口 20
        List<Map<String, Object>> eps = JsonHelper.parseListMap(tc.getApiEndpoints());
        if (!eps.isEmpty()) {
            score += 20;
        }

        // 测试数据 15
        Map<String, Object> td = JsonHelper.parseMap(tc.getTestData());
        if (!td.isEmpty()) {
            score += 15;
        }

        // 执行提示 15
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        if (hints.containsKey("approach") && hints.get("approach") != null) {
            score += 15;
        }

        // 步骤数量 10
        if (sSteps.size() >= 2) {
            score += 10;
        }

        // 预期结果 10
        List<String> exp = JsonHelper.parseListString(tc.getExpectedResults());
        if (!exp.isEmpty()) {
            score += 10;
        }

        return score;
    }

    // ==================== 辅助方法 ====================

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

    private List<Map<String, Object>> buildForbiddenTransitions(List<Map<String, Object>> states,
                                                                List<Map<String, Object>> transitions) {
        List<Map<String, Object>> forbidden = new ArrayList<>();
        if (transitions != null && !transitions.isEmpty()) {
            Map<String, Object> first = transitions.get(0);
            Map<String, Object> reverse = new LinkedHashMap<>();
            reverse.put("from", first.getOrDefault("to", ""));
            reverse.put("to", first.getOrDefault("from", ""));
            reverse.put("reason", "反向转换通常不被允许");
            forbidden.add(reverse);
        }
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
