package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.runtime.CancellationSignal;
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

import com.testagent.common.GenerationCancelledException;

@Component
public class TestGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(TestGeneratorAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // v1.6: 进度回调接口，供调用方感知分模块生成进度
    @FunctionalInterface
    public interface ProgressCallback {
        void update(String message);
    }

    // v3.2: 用例流式回调接口，每生成一条用例立即回调一次（用于 SSE 推送）
    @FunctionalInterface
    public interface CaseCallback {
        void onCase(TestCase tc);
    }

    /**
     * v3.7: 流式 JSON 数组解析器。
     * 积累文本 chunk，跟踪花括号深度检测完整用例对象后立即回调 caseCb。
     * 状态机：SEARCHING_ARRAY → IN_ARRAY → IN_OBJECT → (完整对象) → IN_ARRAY → ...
     */
    public class StreamingTestCaseParser {
        private final StringBuilder buffer = new StringBuilder();
        private final CaseCallback caseCb;
        private int scanPos = 0;
        private int arrayStart = -1;
        private int objStart = -1;
        private int braceDepth = 0;
        private boolean inString = false;
        private boolean escaped = false;
        private int parsedCount = 0;

        public StreamingTestCaseParser(CaseCallback caseCb) {
            this.caseCb = caseCb;
        }

        public void append(String chunk) {
            buffer.append(chunk);
            scan();
        }

        private void scan() {
            String text = buffer.toString();
            int len = text.length();
            for (int i = scanPos; i < len; i++) {
                char c = text.charAt(i);

                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }

                if (arrayStart == -1) {
                    if (c == '[') {
                        arrayStart = i;
                    }
                    continue;
                }

                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    if (braceDepth == 0) {
                        objStart = i;
                    }
                    braceDepth++;
                } else if (c == '}') {
                    braceDepth--;
                    if (braceDepth == 0 && objStart >= 0) {
                        String objJson = text.substring(objStart, i + 1);
                        try {
                            parseAndCallback(objJson);
                        } catch (Exception e) {
                            log.warn("流式解析用例对象失败, 跳过: {}", e.getMessage());
                        }
                        objStart = -1;
                    }
                } else if (c == ']' && braceDepth == 0) {
                    break;
                }
            }
            scanPos = len;
        }

        private void parseAndCallback(String objJson) throws Exception {
            JsonNode node = objectMapper.readTree(objJson);
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
            parsedCount++;
            if (caseCb != null) {
                try { caseCb.onCase(tc); } catch (Exception ex) {
                    log.warn("caseCallback failed, continue: {}", ex.getMessage());
                }
            }
        }

        public int getParsedCount() {
            return parsedCount;
        }
    }

    // v3.3: 取消检查。cancelled 为 null（非流式调用）或 false 时跳过。
    private void checkCancelled(CancellationSignal cancelled) {
        if (cancelled != null && cancelled.isCancelled()) {
            throw new GenerationCancelledException("用例生成已取消");
        }
    }

    // v1.4: 结构化分段 system prompt
    // v3.4: 拆分为 HEADER + 动态数量引导段 + FOOTER，medium 档文本与原 SYSTEM_PROMPT 完全一致
    private static final String SYSTEM_PROMPT_HEADER = """
            # 角色
            你是资深测试工程师，擅长生成结构化、AI 可执行的测试用例。

            # 任务
            根据以下状态机和接口信息，为每个状态转换生成测试用例。

            # 生成要求
            ## 数量引导
            """;

    private static final String SYSTEM_PROMPT_FOOTER = """

            ## 测试数据要求
            - testData 必须包含具体字段值，不能为空对象 {}
            - 数值字段：填入真实值和边界值（如 amount: 0, amount: -1, amount: 99999999）
            - 字符串字段：填入正常值、空字符串、超长字符串（256字符）
            - 枚举字段：填入合法值和非法枚举值
            - 必填字段：测试缺失该字段的情况

            ## structuredSteps 要求（必须严格遵守）
            - structuredSteps 必须是非空数组，按真实操作顺序 3-10 步展开：
              进入页面 → 定位元素 → 输入/点击 → 断言结果，禁止把多个操作合并成一句
            - ui_action 类型步骤（点击/输入/选择/滚动）必须携带 uiSelector：{type, value}
              - type 取 id / ref / data-testid / aria-label / text / path
              - value 从下方 frontendSelectors 中选取与操作最匹配的真实选择器；
                找不到精确选择器时，target 写按钮/输入框的可见文案，uiSelector 用 {type:"text", value:"按钮文案"}
            - 输入类操作必须携带 data：{字段名: 具体输入值}
            - state_assert 类型步骤的 expected 必须写可验证断言（页面 URL / 元素文本 / 状态提示）
            - api_call 类型步骤的 target 必须用真实接口路径，data 为该接口的入参
            - 每步的 target、expected 都不能为空

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
    // v3.4: 拆分为 HEADER + 动态数量引导段 + FOOTER，medium 档文本与原 SYSTEM_PROMPT_PRD_DRIVEN 完全一致
    private static final String SYSTEM_PROMPT_PRD_HEADER = """
            # 角色
            你是资深测试工程师，以需求为源生成结构化、AI 可执行的测试用例。

            # 任务
            根据【需求上下文（PRD 解析结果）】为主，【代码上下文（状态机/接口/业务规则）】为辅，生成测试用例。

            # 生成要求
            ## 以需求为纲
            """;

    private static final String SYSTEM_PROMPT_PRD_FOOTER = """

            ## 代码信息用于补充（不作为用例来源，只增强可执行性）
            - endpoints：用例 structuredSteps 的 target 用真实接口路径（如 POST /api/order/create）
            - stateMachines：用例的 stateMachineRef 引用真实状态流转
            - businessRules：补充为前置条件或异常场景
            - frontendForms：testData 填入真实表单字段名和校验规则（required/min/max）
            - frontendSelectors：structuredSteps 的 ui_action 类型步骤可附 uiSelector（{type, value}）
            - frontendPageFlows：生成页面跳转验证用例（from→to，验证导航需求）
            - frontendComponentStates：生成 UI 交互用例（弹窗打开/关闭、分步流程）

            ## structuredSteps / testData / executionHints 要求（必须严格遵守）
            - structuredSteps 必须是非空数组，按真实操作顺序 3-10 步展开：进入页面→定位元素→输入/点击→断言
            - 页面操作优先用 ui_action 类型步骤描述（点哪个按钮、输入什么），不要只写接口调用
            - ui_action 步骤必须携带 uiSelector：{type, value}
              - type 取 id / ref / data-testid / aria-label / text / path
              - value 从 frontendSelectors 中选最匹配的真实选择器；无精确匹配时用 {type:"text", value:"可见文案"}
            - 输入类步骤 data 必须含具体字段值（按 frontendForms 的字段名）
            - state_assert 的 expected 写可验证断言；api_call 的 target 用真实接口路径
            - target、expected 都不能为空；testData 含具体字段值

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
                "title": "登录-正常流程（UI 操作）",
                "module": "系统管理",
                "type": "positive",
                "priority": "P0",
                "preconditions": ["已打开登录页"],
                "steps": ["打开登录页", "输入用户名密码", "点击登录", "验证进入首页"],
                "expectedResults": ["页面跳转首页", "显示欢迎语"],
                "structuredSteps": [
                  {"order":1,"action":"打开登录页","target":"/login","expected":"出现登录表单","data":{},"type":"ui_action","uiSelector":{"type":"path","value":"/login"}},
                  {"order":2,"action":"输入用户名","target":"用户名输入框","expected":"输入成功","data":{"username":"admin"},"type":"ui_action","uiSelector":{"type":"id","value":"username"}},
                  {"order":3,"action":"输入密码","target":"密码输入框","expected":"输入成功","data":{"password":"admin123"},"type":"ui_action","uiSelector":{"type":"id","value":"password"}},
                  {"order":4,"action":"点击登录按钮","target":"登录按钮","expected":"提交登录","data":{},"type":"ui_action","uiSelector":{"type":"text","value":"登录"}},
                  {"order":5,"action":"断言登录成功","target":"页面","expected":"URL 跳转首页且出现欢迎语","data":{},"type":"state_assert"}
                ],
                "apiEndpoints": [{"method":"POST","path":"/admin/auth/login","description":"登录"}],
                "testData": {"username":"admin","password":"admin123"},
                "executionHints": {"approach":"ui","notes":"UI 操作登录并断言跳转","prerequisites":["已打开登录页"]},
                "stateMachineRef": {"states":[],"transitions":[],"forbiddenTransitions":[]}
              },
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

    // ==================== v3.4: 动态 prompt + temperature 参数化 ====================

    // v3.4: 根据 caseDensity 拼接状态机驱动的数量引导段
    private String buildQuantityGuide(String caseDensity) {
        if ("low".equals(caseDensity)) {
            return """
                    - 正向用例（positive）：每个合法状态转换至少 1 条
                    - 异常用例（negative）：每个状态转换至少 1 条非法输入/非法转换
                    - 边界值用例（boundary）：涉及数值/长度字段的至少 1 条
                    - 数据驱动用例（data）：可选""";
        } else if ("high".equals(caseDensity)) {
            return """
                    - 正向用例（positive）：每个合法状态转换至少 2 条
                    - 异常用例（negative）：每个状态转换至少 2 条非法输入/非法转换
                    - 边界值用例（boundary）：涉及数值/长度字段的至少 3 条（上界+下界+越界）
                    - 数据驱动用例（data）：多参数组合场景至少 2 条""";
        }
        // medium = 当前行为（与 v3.3 SYSTEM_PROMPT 完全一致）
        return """
                - 正向用例（positive）：每个合法状态转换至少 1 条
                - 异常用例（negative）：每个状态转换至少 1 条非法输入/非法转换
                - 边界值用例（boundary）：涉及数值/长度字段的至少 2 条（上界+下界）
                - 数据驱动用例（data）：多参数组合场景至少 1 条""";
    }

    // v3.4: 根据 caseDensity 拼接 PRD 驱动的"以需求为纲"段
    private String buildPrdQuantityGuide(String caseDensity) {
        if ("low".equals(caseDensity)) {
            return """
                    - 遍历 requirements 数组，每个需求项至少生成 1 条正向用例
                    - 异常用例可选；边界值用例可选
                    - 优先级遵循需求项的 priority；未标注的按 P1
                    - module 取自 PRD 的 modules；type 取值 positive/negative/boundary/data""";
        } else if ("high".equals(caseDensity)) {
            return """
                    - 遍历 requirements 数组，每个需求项至少生成 2 条正向用例 + 2 条异常用例
                    - 涉及数值/长度字段的，补充边界值用例（上界 + 下界 + 越界）
                    - 数据驱动用例：多参数组合场景至少 2 条
                    - 优先级遵循需求项的 priority；未标注的按 P1
                    - module 取自 PRD 的 modules；type 取值 positive/negative/boundary/data""";
        }
        // medium = 当前行为（与 v3.3 SYSTEM_PROMPT_PRD_DRIVEN 完全一致）
        return """
                - 遍历 requirements 数组，每个需求项至少生成 1 条正向用例 + 1 条异常用例
                - 涉及数值/长度字段的，补充边界值用例（上界 + 下界）
                - 优先级遵循需求项的 priority；未标注的按 P1
                - module 取自 PRD 的 modules；type 取值 positive/negative/boundary/data""";
    }

    // v3.4: 动态构建状态机驱动 system prompt（替换数量引导段）
    private String buildSystemPrompt(GenerationParams params) {
        String density = (params != null && params.getCaseDensity() != null) ? params.getCaseDensity() : "medium";
        return SYSTEM_PROMPT_HEADER + buildQuantityGuide(density) + SYSTEM_PROMPT_FOOTER;
    }

    // v3.4: 动态构建 PRD 驱动 system prompt
    private String buildPrdDrivenPrompt(GenerationParams params) {
        String density = (params != null && params.getCaseDensity() != null) ? params.getCaseDensity() : "medium";
        return SYSTEM_PROMPT_PRD_HEADER + buildPrdQuantityGuide(density) + SYSTEM_PROMPT_PRD_FOOTER;
    }

    // v3.4: 从 params 读取 temperature，null/越界时默认 0.4（与 v3.3 行为一致）
    private double resolveTemperature(GenerationParams params) {
        if (params != null && params.getTemperature() != null) {
            double t = params.getTemperature();
            if (t >= 0.0 && t <= 1.0) return t;
        }
        return 0.4;
    }

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
        // v1.11: 委托给新增 frontendResult 的重载
        return generate(prdResult, stateMachines, backendResult, null, progressCallback);
    }

    // v1.11: 新增 frontendResult 重载。前端上下文作为辅助信息注入用例生成
    // v3.4: 委托给新增 params 重载（params=null 向后兼容）
    public List<TestCase> generate(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                   BackendResult backendResult, FrontendResult frontendResult,
                                   ProgressCallback progressCallback) {
        return generate(prdResult, stateMachines, backendResult, frontendResult, progressCallback, null);
    }

    // v3.4: 新增 params 重载。根据 GenerationParams 动态拼接 system prompt + 调整 LLM temperature。
    // params 为 null 时退化为 v3.3 行为（medium/0.4）。
    public List<TestCase> generate(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                   BackendResult backendResult, FrontendResult frontendResult,
                                   ProgressCallback progressCallback, GenerationParams params) {
        List<TestCase> result = new ArrayList<>();

        // v1.10: PRD 驱动分支
        if (prdResult != null && !prdResult.isEmpty()) {
            if (progressCallback != null) {
                progressCallback.update("基于 PRD 生成用例...");
            }
            try {
                result = generateByLlmWithPrd(prdResult, stateMachines, backendResult, frontendResult, null, null, params);
            } catch (Exception e) {
                log.warn("PRD-driven generation failed, fallback to code-driven: {}", e.getMessage());
                result = new ArrayList<>();
            }
        }

        // 代码驱动分支（PRD 为空或 PRD 生成失败时）
        if (result.isEmpty()) {
            result = generateCodeDrivenCases(stateMachines, backendResult, frontendResult, progressCallback, null, null, params);
        }

        // v3.13: 聚焦类型过滤（focusTypes 非空时仅保留指定类型）
        result = filterByFocusTypes(params, result);
        // 前端选择器补齐：为 ui_action 步骤匹配真实 uiSelector
        for (TestCase tc : result) {
            enrichStructuredSteps(frontendResult, tc);
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

    // v3.2: 流式生成重载。与 generate 行为一致（PRD 驱动/代码驱动分支、去重、质量评分、编号），
    // 额外通过 caseCb 在每条用例解析完成时回调（去重前），用于 SSE 推送
    // v3.3: 新增 cancelled 参数，在 LLM 调用前/状态机循环迭代前检查取消标志
    // v3.4: 新增 params 参数，动态拼接 prompt + 调整 temperature
    public List<TestCase> generateStreaming(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                             BackendResult backendResult, FrontendResult frontendResult,
                                             ProgressCallback progressCallback, CaseCallback caseCb,
                                             CancellationSignal cancelled, GenerationParams params) {
        List<TestCase> result = new ArrayList<>();
        // v3.13: 包装回调，仅透传聚焦类型（SSE 推送与落库一致）
        CaseCallback effectiveCb = wrapFocusFilter(params, caseCb);

        if (prdResult != null && !prdResult.isEmpty()) {
            checkCancelled(cancelled);  // v3.3: PRD 驱动分支前检查
            if (progressCallback != null) {
                progressCallback.update("基于 PRD 生成用例...");
            }
            try {
                result = generateByLlmWithPrd(prdResult, stateMachines, backendResult, frontendResult, effectiveCb, cancelled, params);
            } catch (GenerationCancelledException e) {
                throw e;  // v3.3: 取消异常向上传播，不触发 fallback
            } catch (Exception e) {
                log.warn("PRD-driven streaming generation failed, fallback to code-driven: {}", e.getMessage());
                result = new ArrayList<>();
            }
        }

        if (result.isEmpty()) {
            result = generateCodeDrivenCases(stateMachines, backendResult, frontendResult, progressCallback, effectiveCb, cancelled, params);
        }

        // v3.13: 聚焦类型过滤
        result = filterByFocusTypes(params, result);
        // 前端选择器补齐：为 ui_action 步骤匹配真实 uiSelector
        for (TestCase tc : result) {
            enrichStructuredSteps(frontendResult, tc);
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

    // v3.13: 聚焦类型过滤（focusTypes 为空 = 全部类型）
    private List<TestCase> filterByFocusTypes(GenerationParams params, List<TestCase> result) {
        if (params == null || params.getFocusTypes() == null || params.getFocusTypes().isEmpty()) {
            return result;
        }
        return result.stream()
                .filter(tc -> params.getFocusTypes().contains(tc.getType()))
                .toList();
    }

    // v3.13: 包装 caseCb，仅透传聚焦类型
    private CaseCallback wrapFocusFilter(GenerationParams params, CaseCallback caseCb) {
        if (caseCb == null || params == null || params.getFocusTypes() == null || params.getFocusTypes().isEmpty()) {
            return caseCb;
        }
        return tc -> {
            if (params.getFocusTypes().contains(tc.getType())) {
                caseCb.onCase(tc);
            }
        };
    }

    /**
     * 前端选择器补齐：LLM 未给 uiSelector 时，根据 action/target 文案从前端分析结果中
     * 匹配最可能的 DOM 选择器/表单字段，补到 ui_action 步骤上，让执行更精确。
     */
    private void enrichStructuredSteps(FrontendResult frontendResult, TestCase tc) {
        List<Map<String, Object>> steps = JsonHelper.parseListMap(tc.getStructuredSteps());
        if (steps.isEmpty() || frontendResult == null) return;

        // 汇总可选选择器池（DOM 选择器 + 表单字段）
        List<Map<String, Object>> pool = new ArrayList<>();
        if (frontendResult.getDomSelectors() != null) {
            for (Map<String, Object> sel : frontendResult.getDomSelectors()) {
                Object sels = sel.get("selectors");
                if (sels instanceof List) {
                    for (Object s : (List<?>) sels) {
                        if (s instanceof Map) {
                            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) s);
                            m.putIfAbsent("component", sel.get("component"));
                            pool.add(m);
                        }
                    }
                }
            }
        }
        if (frontendResult.getForms() != null) {
            for (Map<String, Object> form : frontendResult.getForms()) {
                Object fields = form.get("fields");
                if (fields instanceof List) {
                    for (Object f : (List<?>) fields) {
                        if (f instanceof Map) {
                            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) f);
                            m.putIfAbsent("component", form.get("component"));
                            pool.add(m);
                        }
                    }
                }
            }
        }
        if (pool.isEmpty()) return;

        for (Map<String, Object> step : steps) {
            if (!"ui_action".equals(step.get("type"))) continue;
            Object selObj = step.get("uiSelector");
            if (selObj instanceof Map
                    && ((Map<?, ?>) selObj).get("value") != null
                    && !String.valueOf(((Map<?, ?>) selObj).get("value")).isBlank()) {
                continue; // 已有选择器
            }
            String action = step.get("action") == null ? "" : String.valueOf(step.get("action"));
            String target = step.get("target") == null ? "" : String.valueOf(step.get("target"));
            Map<String, Object> best = bestSelector(pool, action + " " + target);
            if (best != null) {
                Map<String, Object> uiSelector = new LinkedHashMap<>();
                uiSelector.put("type", best.get("type"));
                uiSelector.put("value", best.get("value"));
                step.put("uiSelector", uiSelector);
                // 表单字段：给 data 补一个字段占位，便于执行端知道输入目标
                if (best.containsKey("name") && step.get("data") == null) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put(String.valueOf(best.get("name")), "");
                    step.put("data", data);
                }
            }
        }
        tc.setStructuredSteps(toJson(steps));
    }

    // 按关键词包含匹配最合适的 DOM 选择器/表单字段
    private Map<String, Object> bestSelector(List<Map<String, Object>> pool, String text) {
        String lower = text.toLowerCase();
        Map<String, Object> best = null;
        int bestScore = 0;
        for (Map<String, Object> s : pool) {
            String value = s.get("value") == null ? "" : String.valueOf(s.get("value"));
            String element = s.get("element") == null ? "" : String.valueOf(s.get("element"));
            String component = s.get("component") == null ? "" : String.valueOf(s.get("component"));
            String name = s.get("name") == null ? "" : String.valueOf(s.get("name"));
            String label = s.get("label") == null ? "" : String.valueOf(s.get("label"));
            String haystack = (value + " " + element + " " + component + " " + name + " " + label).toLowerCase();
            int score = 0;
            for (String token : lower.split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+")) {
                if (token.length() >= 2 && haystack.contains(token)) {
                    score += token.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return bestScore >= 2 ? best : null;
    }

    // v1.10: 原代码驱动生成逻辑（状态机/endpoint），从 generate 抽出供 PRD 失败时复用
    // v1.11: 新增 frontendResult 参数
    // v3.2: 新增 caseCb 参数，规则回退与 LLM 解析均透传回调
    // v3.3: 新增 cancelled 参数，每模块循环迭代前检查取消标志
    // v3.4: 新增 params 参数，透传给 generateByLlmForStateMachine 用于动态 prompt + temperature
    private List<TestCase> generateCodeDrivenCases(List<StateMachine> stateMachines, BackendResult backendResult,
                                                    FrontendResult frontendResult,
                                                    ProgressCallback progressCallback, CaseCallback caseCb,
                                                    CancellationSignal cancelled, GenerationParams params) {
        List<TestCase> result = new ArrayList<>();

        if (stateMachines != null && !stateMachines.isEmpty()) {
            int total = stateMachines.size();
            for (int i = 0; i < total; i++) {
                checkCancelled(cancelled);  // v3.3: 每模块前检查
                StateMachine sm = stateMachines.get(i);
                if (progressCallback != null) {
                    progressCallback.update(String.format("正在生成第 %d/%d 个模块: %s", i + 1, total, sm.getName()));
                }
                List<TestCase> moduleCases;
                try {
                    moduleCases = generateByLlmForStateMachine(sm, backendResult, frontendResult, caseCb, cancelled, params);
                    if (moduleCases == null || moduleCases.isEmpty()) {
                        log.warn("LLM returned empty for state machine {}, using rules", sm.getName());
                        moduleCases = generateByRulesForStateMachine(sm, backendResult, caseCb);
                    }
                } catch (GenerationCancelledException e) {
                    throw e;  // v3.3: 取消异常向上传播，不触发 rules fallback
                } catch (Exception e) {
                    log.warn("LLM generation failed for state machine {}, falling back to rules: {}",
                            sm.getName(), e.getMessage());
                    moduleCases = generateByRulesForStateMachine(sm, backendResult, caseCb);
                }
                result.addAll(moduleCases);
            }
        }

        // 无状态机时按 endpoints 生成
        if (result.isEmpty() && backendResult != null && backendResult.getEndpoints() != null) {
            if (progressCallback != null) {
                progressCallback.update("无状态机，按接口生成用例...");
            }
            result = generateByEndpoints(backendResult, caseCb);
        }
        return result;
    }

    // ==================== LLM 生成（分模块） ====================

    // v1.11: 新增 frontendResult 参数
    // v3.2: 新增 caseCb 参数，透传给 parseTestCases 用于流式回调
    // v3.3: 新增 cancelled 参数，LLM 调用前检查取消标志
    // v3.4: 新增 params 参数，动态拼接 system prompt + 调整 temperature
    private List<TestCase> generateByLlmForStateMachine(StateMachine sm, BackendResult backendResult,
                                                         FrontendResult frontendResult, CaseCallback caseCb,
                                                         CancellationSignal cancelled, GenerationParams params) {
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

        // v1.11: 前端上下文
        putFrontendContext(context, frontendResult);

        // v3.4: 动态构建 system prompt（根据 caseDensity 拼接数量引导段）
        String systemPrompt = buildSystemPrompt(params);

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

        checkCancelled(cancelled);  // v3.3: LLM 调用前检查（耗时操作，最关键的取消点）
        // v3.4: temperature 参数化（从 params 读取，null/越界默认 0.4）
        // v3.7: caseCb 非空时启用流式调用 + 增量解析
        if (caseCb != null) {
            StreamingTestCaseParser parser = new StreamingTestCaseParser(caseCb);
            String response = llmService.chatStreaming(
                    systemPrompt, userPrompt, resolveTemperature(params), parser::append);
            String json = extractJsonArray(response);
            List<TestCase> all = parseTestCases(json, null);
            for (int i = parser.getParsedCount(); i < all.size(); i++) {
                try { caseCb.onCase(all.get(i)); } catch (Exception ex) {
                    log.warn("兜底推送失败: {}", ex.getMessage());
                }
            }
            return all;
        }
        // caseCb 为 null（非流式场景）：原有逻辑
        String response = llmService.chat(systemPrompt, userPrompt, resolveTemperature(params));
        String json = extractJsonArray(response);
        return parseTestCases(json, null);
    }

    // v1.10: PRD 驱动的 LLM 生成（PRD 为主、代码为辅）
    // v1.11: 新增 frontendResult 参数，前端上下文作为辅助信息
    // v3.2: 新增 caseCb 参数，透传给 parseTestCases 用于流式回调
    // v3.3: 新增 cancelled 参数，LLM 调用前检查取消标志
    // v3.4: 新增 params 参数，动态拼接 PRD system prompt + 调整 temperature
    private List<TestCase> generateByLlmWithPrd(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                                 BackendResult backendResult,
                                                 FrontendResult frontendResult,
                                                 CaseCallback caseCb, CancellationSignal cancelled,
                                                 GenerationParams params) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();

        // PRD 为主上下文
        context.put("prd", objectMapper.convertValue(prdResult, Map.class));
        // v5.4: RAG 语义检索上下文
        context.put("ragContexts",
                prdResult.getRagContexts() == null ? List.of() : prdResult.getRagContexts());

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

        // v1.11: 前端上下文（辅助）
        putFrontendContext(context, frontendResult);

        String userPrompt = "上下文信息：\n" + objectMapper.writeValueAsString(context)
                + "\n\n" + FEW_SHOT_EXAMPLES
                + "\n\n请以 PRD 需求为纲生成测试用例，代码信息用于补充接口路径与前置状态。";
        checkCancelled(cancelled);  // v3.3: LLM 调用前检查（耗时操作，最关键的取消点）
        // v3.4: 动态构建 PRD system prompt + temperature 参数化
        // v3.7: caseCb 非空时启用流式调用 + 增量解析
        if (caseCb != null) {
            StreamingTestCaseParser parser = new StreamingTestCaseParser(caseCb);
            String response = llmService.chatStreaming(
                    buildPrdDrivenPrompt(params), userPrompt, resolveTemperature(params), parser::append);
            // 兜底：用完整响应重新解析，推送流式期间未推送的用例
            String json = extractJsonArray(response);
            List<TestCase> all = parseTestCases(json, null);
            for (int i = parser.getParsedCount(); i < all.size(); i++) {
                try { caseCb.onCase(all.get(i)); } catch (Exception ex) {
                    log.warn("兜底推送失败: {}", ex.getMessage());
                }
            }
            return all;
        }
        // caseCb 为 null（非流式场景）：原有逻辑
        String response = llmService.chat(buildPrdDrivenPrompt(params), userPrompt, resolveTemperature(params));
        String json = extractJsonArray(response);
        return parseTestCases(json, null);
    }

    // v1.11: 将前端上下文注入 context Map，截断避免 token 超限
    @SuppressWarnings("unchecked")
    private void putFrontendContext(Map<String, Object> context, FrontendResult frontendResult) {
        if (frontendResult == null) return;

        // 表单字段（精简：只保留字段名+类型+校验规则）
        if (frontendResult.getForms() != null && !frontendResult.getForms().isEmpty()) {
            List<Map<String, Object>> forms = new ArrayList<>();
            for (Map<String, Object> form : frontendResult.getForms()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("component", form.get("component"));
                f.put("fields", form.get("fields"));
                forms.add(f);
            }
            context.put("frontendForms", forms);
        }

        // DOM 选择器（精简：只保留 type+value+element）
        if (frontendResult.getDomSelectors() != null && !frontendResult.getDomSelectors().isEmpty()) {
            List<Map<String, Object>> selectors = new ArrayList<>();
            for (Map<String, Object> sel : frontendResult.getDomSelectors()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("component", sel.get("component"));
                s.put("selectors", sel.get("selectors"));
                selectors.add(s);
            }
            context.put("frontendSelectors", selectors);
        }

        // 组件交互状态
        if (frontendResult.getComponentStates() != null && !frontendResult.getComponentStates().isEmpty()) {
            context.put("frontendComponentStates", frontendResult.getComponentStates());
        }

        // 页面跳转关系
        if (frontendResult.getPageFlows() != null && !frontendResult.getPageFlows().isEmpty()) {
            context.put("frontendPageFlows", frontendResult.getPageFlows());
        }
    }

    private List<TestCase> parseTestCases(String json) {
        return parseTestCases(json, null);
    }

    // v3.2: 流式解析重载。caseCb 非空时，每解析出一条用例立即回调（用于 SSE 推送）
    private List<TestCase> parseTestCases(String json, CaseCallback caseCb) {
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
                    // v3.2: 解析出一条立即回调，不等去重
                    if (caseCb != null) {
                        try { caseCb.onCase(tc); } catch (Exception ex) {
                            log.warn("caseCallback failed, continue: {}", ex.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse LLM test case response", e);
            throw new RuntimeException("Failed to parse LLM response", e);
        }
        return result;
    }

    // ==================== 规则生成（单模块回退） ====================

    // v3.2: 新增 caseCb 参数，每条规则用例构建后立即回调
    private List<TestCase> generateByRulesForStateMachine(StateMachine sm, BackendResult backendResult,
                                                          CaseCallback caseCb) {
        List<TestCase> result = new ArrayList<>();
        List<Map<String, Object>> states = JsonHelper.parseListMap(sm.getStates());
        List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
        List<Map<String, Object>> matchedEndpoints = matchEndpoints(backendResult, sm.getName());

        TestCase positive = buildPositiveTest(sm, transitions, matchedEndpoints);
        result.add(positive);
        if (caseCb != null) { try { caseCb.onCase(positive); } catch (Exception ignored) {} }
        TestCase negative = buildNegativeTest(sm, states, transitions);
        result.add(negative);
        if (caseCb != null) { try { caseCb.onCase(negative); } catch (Exception ignored) {} }
        TestCase boundary = buildBoundaryTest(sm, states, transitions);
        result.add(boundary);
        if (caseCb != null) { try { caseCb.onCase(boundary); } catch (Exception ignored) {} }
        return result;
    }

    // v3.2: 新增 caseCb 参数，每个接口用例构建后立即回调
    private List<TestCase> generateByEndpoints(BackendResult backendResult, CaseCallback caseCb) {
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
            if (caseCb != null) { try { caseCb.onCase(tc); } catch (Exception ignored) {} }
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
