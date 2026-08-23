package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.analyzer.result.OperationDep;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.common.BusinessComponentPolicy;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.runtime.CancellationSignal;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import com.testagent.service.SemanticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.testagent.common.BusinessException;
import com.testagent.common.GenerationCancelledException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

@Component
public class TestGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(TestGeneratorAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // v5.14: 自动多轮补齐上限，避免无限调用 LLM
    private static final int MAX_GENERATION_ROUNDS = 4;
    private static final int MAX_GENERATED_CASES = 60;

    // v7.14(G25): context.endpoints/businessRules 完整详情容量上限——G17 弱过滤（>0 即过）
    // 全放行后无总量控制，大项目 220 接口 × 全量详情 = 128KB 灌 prompt。字段初始化默认值
    // 兜底：单测直接 new 不走容器，纯 @Value 下 int 为 0 会把上下文截没
    @Value("${app.generation.endpoints-context-max:80}")
    private int endpointsContextMax = 80;

    @Value("${app.generation.rules-context-max:100}")
    private int rulesContextMax = 100;

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
     * v7.1(G2/G5): 生成链路一致性报告——记录各阶段丢弃数量与真实降级信号。
     * SSE 流式推送的是"草稿"（去重/评审/补齐前），与最终落库结果存在差异；
     * 本报告把差异的"数量+原因分类"暴露给服务层（complete 事件）与任务系统（markDegraded），
     * 推送≠落库不再静默。report 为 null 时内部照常生成，只是不采集。
     */
    public static class GenerationReport {
        /** LLM 生成总数（聚焦类型过滤前，多轮累计） */
        public int generated;
        /** 聚焦类型过滤丢弃数 */
        public int focusDropped;
        /** 评审阶段丢弃数（规则: 无 structuredSteps；LLM: reject）——由 TestCaseReviewAgent 记录 */
        public int reviewDropped;
        /** 标题/步骤指纹去重丢弃数 */
        public int dedupDropped;
        /** 批内语义去重丢弃数（v7.1 G14） */
        public int semanticDropped;
        /** 最终返回（将落库）数量 */
        public int finalCount;
        /** 多轮补齐耗尽仍有覆盖缺口（未达生成上限）——真实降级信号 */
        public boolean roundsNotConverged;
        /** 评审 LLM 失败降级为纯规则评审——真实降级信号 */
        public boolean reviewDegraded;
        /** 流式响应发生截断（最后一条 JSON 未闭合，可能丢用例）——v7.3(L8) 真实降级信号 */
        public boolean streamTruncated;
        /** 截断后局部补全抢救成功的用例数（字段可能不完整） */
        public int truncatedRecovered;
        /** 因 60 条生成上限提前退出且仍有覆盖缺口——v7.7(G10) 容量事实（非降级信号） */
        public boolean coverageCappedByLimit;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("generated", generated);
            map.put("focusDropped", focusDropped);
            map.put("reviewDropped", reviewDropped);
            map.put("dedupDropped", dedupDropped);
            map.put("semanticDropped", semanticDropped);
            map.put("finalCount", finalCount);
            map.put("roundsNotConverged", roundsNotConverged);
            map.put("reviewDegraded", reviewDegraded);
            map.put("streamTruncated", streamTruncated);
            map.put("truncatedRecovered", truncatedRecovered);
            map.put("coverageCappedByLimit", coverageCappedByLimit);
            return map;
        }
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
        // v7.10(G8): 解析结果收集列表——流式解析器是唯一解析真源，
        // 调用方直接取收集结果，消除"流式/全量双解析索引错位"（重复推/漏推）
        private final List<TestCase> collected = new ArrayList<>();

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
            tc.setExecutionHints(mergeCoverageRefs(node.path("coverageRefs"),
                    nodeToJson(node.path("executionHints"), "{}")));
            tc.setStateMachineRef(nodeToJson(node.path("stateMachineRef"), "{}"));
            tc.setSource("ai_generation");
            tc.setConfidence(0.8);
            parsedCount++;
            collected.add(tc);   // v7.10(G8): 收集即返回源
            if (caseCb != null) {
                try { caseCb.onCase(tc); } catch (Exception ex) {
                    log.warn("caseCallback failed, continue: {}", ex.getMessage());
                }
            }
        }

        public int getParsedCount() {
            return parsedCount;
        }

        /** v7.10(G8): 流式解析收集的全部用例（含 L8 截断抢救条目） */
        public List<TestCase> getCollected() {
            return collected;
        }

        // ==================== v7.3(L8): 截断检测与局部补全 ====================

        private boolean truncated = false;
        private int recovered = 0;

        /**
         * v7.3(L8): 流结束后调用。检测最后一条对象未闭合（braceDepth 不归零）时：
         * ① 置截断标志（调用方记录 warning + report）；② 尝试截到最后一个安全逗号、
         * 补齐闭合括号后重试解析，抢救字段基本完整的最后一条。返回是否发生截断。
         */
        public boolean finish() {
            if (braceDepth > 0 && objStart >= 0) {
                truncated = true;
                String tail = buffer.substring(objStart);
                String completed = completeTruncated(tail);
                if (completed != null) {
                    try {
                        parseAndCallback(completed);
                        recovered = 1;
                        log.warn("流式响应截断：已局部补全抢救最后一条用例（字段可能不完整）: {}",
                                tail.length() > 120 ? tail.substring(0, 120) + "..." : tail);
                    } catch (Exception e) {
                        log.warn("流式响应截断：局部补全解析失败，最后一条用例丢失: {}", e.getMessage());
                    }
                } else {
                    log.warn("流式响应截断（maxTokens 不足或连接中断）：最后一条用例不完整已丢弃");
                }
            }
            return truncated;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public int getRecovered() {
            return recovered;
        }

        /**
         * 截到最后一个字符串外逗号（不含），按括号栈补齐闭合符。
         * 找不到安全截断点（如整段都处在一个未闭合字符串内）返回 null。
         */
        private String completeTruncated(String tail) {
            int len = tail.length();
            int lastComma = -1;
            boolean inStr = false;
            boolean esc = false;
            for (int i = 0; i < len; i++) {
                char c = tail.charAt(i);
                if (inStr) {
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        inStr = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inStr = true;
                } else if (c == ',') {
                    lastComma = i;
                }
            }
            // 截断发生在字符串内部时，仍可回退到最后一个字符串外逗号（安全点）；
            // 仅当整段从头就无安全逗号（如 {"title":"半截 且之前无逗号）才放弃
            // 截断点：最后一个字符串外逗号（截掉尾逗号）；无逗号且不在字符串内则整段
            int cut;
            if (lastComma > 0) {
                cut = lastComma;
            } else if (!inStr) {
                cut = len;
            } else {
                return null;
            }
            String partial = tail.substring(0, cut);
            if (partial.isBlank() || partial.length() <= 1) {
                return null;
            }
            // 重新扫描 partial 的括号栈，逆序补闭合符
            java.util.Deque<Character> restack = new java.util.ArrayDeque<>();
            inStr = false;
            esc = false;
            for (int i = 0; i < partial.length(); i++) {
                char c = partial.charAt(i);
                if (inStr) {
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        inStr = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inStr = true;
                } else if (c == '{' || c == '[') {
                    restack.push(c);
                } else if (c == '}' || c == ']') {
                    if (!restack.isEmpty()) {
                        restack.pop();
                    }
                }
            }
            StringBuilder sb = new StringBuilder(partial);
            while (!restack.isEmpty()) {
                sb.append(restack.pop() == '{' ? '}' : ']');
            }
            return sb.toString();
        }
    }

    // v3.3: 取消检查。cancelled 为 null（非流式调用）或 false 时跳过。
    private void checkCancelled(CancellationSignal cancelled) {
        if (cancelled != null && cancelled.isCancelled()) {
            throw new GenerationCancelledException("用例生成已取消");
        }
    }

    // v7.4(A19): 已删除 SYSTEM_PROMPT_HEADER / SYSTEM_PROMPT_FOOTER / buildSystemPrompt /
    // buildQuantityGuide——v5.13 起 PRD 必需、v7.1 删除代码驱动生成链后无调用方的遗留死代码。

    // v1.10: PRD 驱动 system prompt（PRD 为主、代码为辅）
    // v3.4: 拆分为 HEADER + 动态数量引导段 + FOOTER，medium 档文本与原 SYSTEM_PROMPT_PRD_DRIVEN 完全一致
    private static final String SYSTEM_PROMPT_PRD_HEADER = """
            # 角色
            你是资深测试工程师，以需求为源生成结构化、AI 可执行的测试用例。

            # 任务
            根据【需求上下文（PRD 文档解析结果 + 上下文文档 + 补充需求）】为主，【代码上下文（状态机/接口/业务规则）】为辅，生成测试用例。

            # 生成要求
            ## 以需求为纲
            """;

    private static final String SYSTEM_PROMPT_PRD_FOOTER = """

            ## ragContexts / ragFailures（v6.4 补充）
            - ragContexts：检索到的相关需求/上下文切片，作为 PRD 之外的补充约束
            - ragFailures：历史执行失败经验；生成时避免重复失败路径，必要时增加对应校验与断言

            ## 代码信息用于补充（不作为用例来源，只增强可执行性）
            - endpoints：用例 structuredSteps 的 target 用真实接口路径（如 POST /api/order/create）
            - stateMachines：用例的 stateMachineRef 引用真实状态流转
            - stateMachines[].source（v7.4）："rule" 表示规则兜底提取（仅状态枚举可信，无转换数据）——
              其 stateMachineRef.transitions 可为空数组，禁止为兜底状态机虚构转换；"llm" 来源正常引用
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

            ## 预期结果语言规范（v7.3，必须严格遵守）
            - expected / expectedResults 必须描述用户在页面上可感知的现象：
              可见文案、toast/消息提示内容、页面跳转目标、元素出现/消失/禁用状态变化
            - 禁止写 HTTP 状态码（如"返回401"）、后端字段名/变量名（如 errorMsg、orderId）、
              机器常量（如 status=PENDING_PAYMENT）、响应体键名
            - 仅 api_call 类型步骤的 expected 允许描述接口行为（如"接口返回 400"）；
              含 UI 步骤的用例，最终断言步骤必须回到页面可感知现象
            - v7.6: 上下文中的 userFeedbackTexts 是从被测系统源码提取的真实提示文案对照表
              （前端 ElMessage / 后端异常消息）——编写 expected 时必须优先使用其中的原文，
              禁止自行编造提示文案

            ## coverageRefs 覆盖要求（v5.12）
            - 每条用例必须携带 coverageRefs：{"requirementIds":[],"transitionIds":[],"endpointIds":[],"ruleIds":[]}
            - id 只能从 coverageChecklist 中选取真实存在的项：
              transitionIds 用 "from->to"；endpointIds 用 "METHOD /path"；ruleIds 用 "rule-N"；requirementIds 原样使用 coverageChecklist.requirements[].id
            - 优先覆盖 coverageGaps 列出的缺口；整体用例集必须让每个 transition/endpoint/rule 至少被一条用例引用
            - 单次只输出 8-15 条用例，不要尝试一次性输出全部缺口

            # 输出格式（同 v1.4）
            返回 JSON 数组，字段：title/module/type/priority/preconditions/steps/expectedResults/
            structuredSteps/apiEndpoints/testData/executionHints/stateMachineRef/coverageRefs
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
                "stateMachineRef": {"states":[],"transitions":[],"forbiddenTransitions":[]},
                "coverageRefs": {"requirementIds":[],"transitionIds":[],"endpointIds":["POST /admin/auth/login"],"ruleIds":[]}
              },
              {
                "title": "创建订单-正常流程",
                "module": "订单管理",
                "type": "positive",
                "priority": "P0",
                "preconditions": ["用户已登录", "购物车有商品"],
                "steps": ["调用创建订单接口", "验证返回订单号", "验证订单状态为待支付"],
                "expectedResults": ["页面提示'下单成功'并显示订单号", "订单列表中该订单显示为'待支付'"],
                "structuredSteps": [
                  {"order":1,"action":"创建订单","target":"POST /api/order/create","expected":"接口返回201和订单号","data":{"userId":"U001","items":[{"skuId":"SKU001","quantity":2}],"amount":99.90},"type":"api_call"},
                  {"order":2,"action":"验证订单状态","target":"订单列表页","expected":"该订单行显示'待支付'状态","data":{},"type":"state_assert"}
                ],
                "apiEndpoints": [{"method":"POST","path":"/api/order/create","description":"创建订单"}],
                "testData": {"userId":"U001","amount":99.90},
                "executionHints": {"approach":"api_call","notes":"先创建再查询验证状态","prerequisites":["用户已登录"]},
                "stateMachineRef": {"states":[],"transitions":[{"from":"NONE","to":"PENDING_PAYMENT","trigger":"create"}],"forbiddenTransitions":[]},
                "coverageRefs": {"requirementIds":["req-1"],"transitionIds":["NONE->PENDING_PAYMENT"],"endpointIds":["POST /api/order/create"],"ruleIds":["rule-1"]}
              },
              {
                "title": "创建订单-金额为负数",
                "module": "订单管理",
                "type": "negative",
                "priority": "P1",
                "preconditions": ["用户已登录"],
                "steps": ["传入负数金额创建订单", "验证接口拒绝"],
                "expectedResults": ["页面提示'金额非法，请重新输入'", "订单未创建，列表无新增记录"],
                "structuredSteps": [
                  {"order":1,"action":"传入负数金额创建订单","target":"POST /api/order/create","expected":"接口返回400，页面出现'金额非法'错误提示","data":{"userId":"U001","amount":-1},"type":"api_call"}
                ],
                "apiEndpoints": [{"method":"POST","path":"/api/order/create","description":"创建订单"}],
                "testData": {"userId":"U001","amount":-1},
                "executionHints": {"approach":"api_call","notes":"验证金额校验逻辑","prerequisites":["用户已登录"]},
                "stateMachineRef": {"states":[],"transitions":[],"forbiddenTransitions":[{"from":"PENDING_PAYMENT","to":"NONE","reason":"金额非法不可创建"}]},
                "coverageRefs": {"requirementIds":["req-1"],"transitionIds":[],"endpointIds":["POST /api/order/create"],"ruleIds":["rule-2"]}
              }
            ]
            """;

    @Autowired
    private LlmService llmService;

    @Autowired
    private BusinessComponentPolicy businessComponentPolicy;

    @Autowired
    private TestCaseReviewAgent testCaseReviewAgent;

    @Autowired
    private PromptSkillLoader promptSkillLoader;

    // v7.1(G14): 全量生成批内语义去重（此前仅追加路径有语义去重）
    @Autowired
    private SemanticService semanticService;

    // v7.11(T1): 用例 ID 全局唯一分配器——生成的批内编号从 TC-001 连续重编改为
    // 全库唯一递增，防止跨项目 merge 覆盖（详见风险清单 T1）
    @Autowired
    private com.testagent.service.TestCaseIdAllocator idAllocator;

    // ==================== v3.4: 动态 prompt + temperature 参数化 ====================

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

    // v3.4: 动态构建 PRD 驱动 system prompt
    private String buildPrdDrivenPrompt(GenerationParams params) {
        String density = (params != null && params.getCaseDensity() != null) ? params.getCaseDensity() : "medium";
        return promptSkillLoader.load("test-generation-prd-header", SYSTEM_PROMPT_PRD_HEADER)
                + buildPrdQuantityGuide(density)
                + promptSkillLoader.load("test-generation-prd-footer", SYSTEM_PROMPT_PRD_FOOTER);
    }

    // v5.12: 构建覆盖清单与缺口（需求/转换/接口/规则），供生成与评审使用
    // v7.10(G7): 包级可见，供单测直接验证需求 ID 内容 hash 稳定性
    Map<String, Object> buildCoverageChecklist(PrdAnalysisResult prdResult,
                                               List<StateMachine> stateMachines,
                                               BackendResult backendResult) {
        List<Map<String, Object>> requirements = new ArrayList<>();
        // v7.10(G7): 需求 ID 内容 hash 稳定化——旧实现 "req-" + i++ 是解析顺序临时编号，
        // PRD 局部修改即全量漂移（追加生成时旧用例 coverageRefs.req-3 与新 checklist 的 req-3 可能指向不同需求）。
        // 新 id = "req-" + SHA-256(title + '\u0001' + description) 前 10 位十六进制：
        // 同一需求内容在任意解析顺序/轮次/任务中 id 一致，局部修改只影响变更项。
        Set<String> seenReqIds = new HashSet<>();
        if (prdResult != null && prdResult.getRequirements() != null) {
            for (Map<String, Object> req : prdResult.getRequirements()) {
                String title = req.get("title") == null ? "" : String.valueOf(req.get("title"));
                String description = req.get("description") == null ? "" : String.valueOf(req.get("description"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "req-" + contentHash(title, description));
                item.put("title", req.get("title"));
                item.put("description", req.get("description"));
                if (seenReqIds.add(String.valueOf(item.get("id")))) {
                    requirements.add(item);   // 同内容重复需求合并为一条
                }
            }
        }
        // v7.7(G16): RAG 检索切片并入考点清单——PRD 解析截断/漂移丢失的需求点通过切片找回；
        // 三重限制控噪声：最短标题 4 字符 + token 重叠 ≥3 视为已覆盖（不重复加）+ 上限 20 条
        // v7.10(G7): rag-req-N 序号编号同款改为内容 hash（rag- 前缀保留 source 区分）
        if (prdResult != null && prdResult.getRagContexts() != null) {
            int ragCount = 0;
            for (String slice : prdResult.getRagContexts()) {
                if (ragCount >= 20) break;
                if (slice == null || slice.isBlank()) continue;
                String title = extractRagTitle(slice);
                if (title.length() < 4) continue;
                if (maxSimilarityScore(title, requirements) >= 3) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "rag-" + contentHash(title, slice));
                item.put("title", title);
                item.put("description", slice.length() > 200 ? slice.substring(0, 200) + "..." : slice);
                item.put("source", "rag");
                if (seenReqIds.add(String.valueOf(item.get("id")))) {
                    requirements.add(item);
                    ragCount++;
                }
            }
        }

        List<Map<String, Object>> transitions = new ArrayList<>();
        if (stateMachines != null) {
            for (StateMachine sm : stateMachines) {
                for (Map<String, Object> t : JsonHelper.parseListMap(sm.getTransitions())) {
                    String from = String.valueOf(t.getOrDefault("from", ""));
                    String to = String.valueOf(t.getOrDefault("to", ""));
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", from + "->" + to);
                    item.put("from", from);
                    item.put("to", to);
                    item.put("trigger", t.get("trigger"));
                    item.put("condition", t.get("condition"));
                    item.put("stateMachine", sm.getName());
                    transitions.add(item);
                }
            }
        }

        List<Map<String, Object>> endpoints = new ArrayList<>();
        if (backendResult != null && backendResult.getEndpoints() != null) {
            for (EndpointInfo ep : backendResult.getEndpoints()) {
                // v7.14(G24): 覆盖清单只放对账标识字段——完整详情已在 context.endpoints 注入过一次，
                // 旧实现 putAll(toContextMap()) 等于把全量接口详情重复灌进 prompt（实测 159KB 冗余，
                // 432KB prompt 触发 300k 保险丝的直接元凶）。消费方核实：remainingGaps 与
                // TestCaseReviewAgent 只读 id/method/path；prompt 侧 checklist 用于 coverageRefs 对账
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", (ep.getMethod() == null ? "" : ep.getMethod().toUpperCase()) + " " + ep.getPath());
                item.put("method", ep.getMethod());
                item.put("path", ep.getPath());
                item.put("function", ep.getFunction());
                endpoints.add(item);
            }
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        if (backendResult != null && backendResult.getBusinessRules() != null) {
            int i = 1;
            for (BusinessRule br : backendResult.getBusinessRules()) {
                // v7.14(G24): 规则全文在 context.businessRules（G25 top-N 完整详情），清单只留识别字段
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", "rule-" + i++);
                item.put("ruleType", br.getRuleType());
                String ruleText = br.getRule() == null ? "" : br.getRule();
                item.put("rule", ruleText.length() > 80 ? ruleText.substring(0, 80) + "..." : ruleText);
                rules.add(item);
            }
        }

        // v6.1 (前端 Agentic RAG + 后端 SAINT): 前端命中组件与后端操作依赖并入覆盖清单。
        // v6.1fix: 覆盖清单同样只保留业务分非负组件，避免第 2+ 轮补齐时把 BackToTop 等公共组件
        // 当作覆盖缺口再次喂给 LLM，造成公共组件用例泄漏。
        List<Map<String, Object>> components = new ArrayList<>();
        List<Map<String, Object>> businessComps = filterBusinessComponents(
                prdResult != null ? prdResult.getFrontendComponents() : null);
        if (!businessComps.isEmpty()) {
            for (Map<String, Object> c : businessComps) {
                // v7.14(G24): 组件完整 map 在 context.frontendComponents 注入，清单只留识别字段
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", c.getOrDefault("id", ""));
                item.put("component", c.getOrDefault("component", ""));
                String summary = c.get("summary") == null ? "" : String.valueOf(c.get("summary"));
                item.put("summary", summary.length() > 80 ? summary.substring(0, 80) + "..." : summary);
                components.add(item);
            }
        }
        List<Map<String, Object>> dependencies = new ArrayList<>();
        if (backendResult != null && backendResult.getDependencyGraph() != null) {
            for (OperationDep od : backendResult.getDependencyGraph()) {
                // v7.14(G24): 依赖明细（dependsOn 调用链）在 context.operationDependencies 注入，
                // 清单只留对账键 id（= operation 全名）
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", od.getOperation());
                dependencies.add(item);
            }
        }

        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("requirements", requirements);
        checklist.put("transitions", transitions);
        checklist.put("endpoints", endpoints);
        checklist.put("businessRules", rules);
        checklist.put("frontendComponents", components);
        checklist.put("operationDependencies", dependencies);

        // v7.7(G10): 缺口清单容量上限——巨型 gaps 列表截断并明示（truncated），
        // 避免 prompt 膨胀与"永远补不完"的轮次空转；coverage 语义与 prompt 注入分离
        Map<String, Object> gaps = new LinkedHashMap<>();
        boolean gapsTruncated = false;
        gapsTruncated |= capIdsInto(gaps, "requirementIds", requirements, 40);
        gapsTruncated |= capIdsInto(gaps, "transitionIds", transitions, 60);
        gapsTruncated |= capIdsInto(gaps, "endpointIds", endpoints, 80);
        gapsTruncated |= capIdsInto(gaps, "ruleIds", rules, 60);
        gapsTruncated |= capIdsInto(gaps, "componentIds", components, 60);
        gapsTruncated |= capIdsInto(gaps, "dependencyIds", dependencies, 60);
        if (gapsTruncated) {
            gaps.put("truncated", true);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checklist", checklist);
        result.put("gaps", gaps);
        return result;
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
        return generate(prdResult, stateMachines, backendResult, frontendResult,
                progressCallback, params, null);
    }

    // v7.1(G2/G5): 报告重载——采集各阶段丢弃数量与降级信号，供编排层/服务层使用
    public List<TestCase> generate(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                   BackendResult backendResult, FrontendResult frontendResult,
                                   ProgressCallback progressCallback, GenerationParams params,
                                   GenerationReport report) {
        return runPrdPipeline(prdResult, stateMachines, backendResult, frontendResult,
                progressCallback, null, null, params, report);
    }

    // v3.2: 流式生成重载。与 generate 行为一致（PRD 驱动、去重、质量评分、编号），
    // 额外通过 caseCb 在每条用例解析完成时回调（去重/评审前，即"草稿"），用于 SSE 推送
    // v3.3: 新增 cancelled 参数，在 LLM 调用前/状态机循环迭代前检查取消标志
    // v3.4: 新增 params 参数，动态拼接 prompt + 调整 temperature
    public List<TestCase> generateStreaming(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                             BackendResult backendResult, FrontendResult frontendResult,
                                             ProgressCallback progressCallback, CaseCallback caseCb,
                                             CancellationSignal cancelled, GenerationParams params) {
        return generateStreaming(prdResult, stateMachines, backendResult, frontendResult,
                progressCallback, caseCb, cancelled, params, null);
    }

    // v7.1(G2/G5): 报告重载——流式路径同样采集丢弃/降级信息
    public List<TestCase> generateStreaming(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                             BackendResult backendResult, FrontendResult frontendResult,
                                             ProgressCallback progressCallback, CaseCallback caseCb,
                                             CancellationSignal cancelled, GenerationParams params,
                                             GenerationReport report) {
        return runPrdPipeline(prdResult, stateMachines, backendResult, frontendResult,
                progressCallback, caseCb, cancelled, params, report);
    }

    /**
     * v7.1: 统一 PRD 生成管线（原 generate/generateStreaming 两份重复代码合并）。
     * caseCb 为 null 即非流式路径；cancelled 为 null 即无取消信号。
     * 管线顺序：LLM 多轮生成 → 聚焦类型过滤(G11) → 选择器补齐(G3) → 评审(G2/G5)
     * → 质量评分 → 标题/指纹去重 → 批内语义去重(G14) → 编号。
     */
    private List<TestCase> runPrdPipeline(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                          BackendResult backendResult, FrontendResult frontendResult,
                                          ProgressCallback progressCallback, CaseCallback caseCb,
                                          CancellationSignal cancelled, GenerationParams params,
                                          GenerationReport report) {
        GenerationReport r = report != null ? report : new GenerationReport();
        // v3.13: 包装回调，仅透传聚焦类型（SSE 推送与落库一致）
        CaseCallback effectiveCb = wrapFocusFilter(params, caseCb);

        // v5.13: 生成必须基于 PRD，代码只作为辅助上下文
        if (prdResult == null || prdResult.isEmpty()) {
            throw BusinessException.invalidParam("请先添加 PRD 文档");
        }
        checkCancelled(cancelled);
        if (progressCallback != null) {
            progressCallback.update("基于 PRD 生成用例...");
        }
        List<TestCase> result;
        try {
            result = generateByLlmWithPrd(prdResult, stateMachines, backendResult, frontendResult,
                    effectiveCb, cancelled, params, progressCallback, r);
        } catch (GenerationCancelledException e) {
            throw e;  // v3.3: 取消异常向上传播，不触发 fallback
        } catch (Exception e) {
            log.error("PRD-driven generation failed: {}", e.getMessage());
            throw new BusinessException(50016, "PRD 生成用例失败: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (result == null || result.isEmpty()) {
            throw new BusinessException(50016, "PRD 生成用例失败：未生成任何用例",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        r.generated = result.size();

        // v3.13: 聚焦类型过滤（focusTypes 非空时仅保留指定类型）
        int beforeFocus = result.size();
        result = filterByFocusTypes(params, result);
        r.focusDropped = beforeFocus - result.size();
        // v7.1(G11): 区分"未生成任何用例"与"生成后被聚焦类型过滤为空"——后者误导排查方向
        if (result.isEmpty()) {
            throw new BusinessException(50016, "PRD 生成用例失败：已生成 " + beforeFocus
                    + " 条用例，但聚焦类型 " + params.getFocusTypes()
                    + " 过滤后为 0 条（请调整聚焦类型后重新生成）",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 前端选择器补齐：为 ui_action 步骤匹配真实 uiSelector
        for (TestCase tc : result) {
            enrichStructuredSteps(frontendResult, tc);
        }

        // v5.12: 覆盖缺口评审 + coverageRefs 补全（先评审后评分/去重）
        // v7.1(G2/G5): 评审丢弃数与 LLM 评审降级信号由 TestCaseReviewAgent 写入报告
        result = testCaseReviewAgent.review(
                result, buildCoverageChecklist(prdResult, stateMachines, backendResult),
                "generation", r);

        if (progressCallback != null) {
            progressCallback.update("正在质量评分与去重...");
        }
        calculateQualityScores(result);
        int beforeDedup = result.size();
        result = deduplicate(result);
        r.dedupDropped = beforeDedup - result.size();

        // v7.1(G14): 全量路径批内语义去重——同语义不同标题的重复用例不再全量保留
        //（此前仅追加路径有语义去重能力）；Milvus/embedding 未配置时自动跳过
        int beforeSemantic = result.size();
        result = semanticService.deduplicateBatch(result);
        r.semanticDropped = beforeSemantic - result.size();

        // v7.11(T1): 批内编号改走全局唯一分配器（原 TC-001 起连续编号会与
        // 其他项目存量用例跨库撞号，JPA merge 静默覆盖）；单测未注入分配器时回退旧编号
        int counter = 1;
        for (TestCase tc : result) {
            if (idAllocator != null) {
                tc.setId(idAllocator.nextId());
            } else {
                tc.setId(String.format("TC-%03d", counter++));
            }
            tc.setCreatedAt(LocalDateTime.now());
        }
        r.finalCount = result.size();
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

        // 汇总可选选择器池——v7.12(G22): 只收 DOM 选择器。
        // 表单字段 {name, type: 输入框类型, label} 没有可执行 value，混池打分胜出后
        // 会写入 uiSelector = {type: "text"(input 类型), value: null} 的废选择器固化进用例资产
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
                // v7.1(G3): 不再补 {字段名: ""} 空占位——空字符串会让必填字段校验必败，
                // 正向用例被静默改成必败用例；且 DOM 执行路径取 inputValue 而非 data，
                // Agent 模式的输入值由 LLM 决定，分析器目前也无默认值/示例值来源，宁缺勿错
            }
        }
        tc.setStructuredSteps(toJson(steps));
    }

    // 按关键词包含匹配最合适的 DOM 选择器/表单字段
    // v7.10(L12): 阈值 2→3 且要求唯一最高分——旧实现单个 2 字 token 命中（score≥2）即匹配，
    // "删除"会匹配到"批量删除"按钮；并列最高分时取先遍历者，错误被固化进用例资产。
    // 无匹配/并列宁留空，由 Agent 模式执行时 LLM 自定位。
    // v7.10(L12): 包级可见，供单测直接验证阈值与唯一最高分语义
    Map<String, Object> bestSelector(List<Map<String, Object>> pool, String text) {
        String lower = text.toLowerCase();
        Map<String, Object> best = null;
        int bestScore = 0;
        int bestCount = 0;
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
                bestCount = 1;
            } else if (score == bestScore && score > 0) {
                bestCount++;
            }
        }
        if (bestScore < 3 || bestCount > 1) {
            return null;   // 分数不足或并列最高：宁留空不赌错
        }
        return best;
    }

    // v7.1(G5): 删除代码驱动生成链（generateCodeDrivenCases / generateByLlmForStateMachine /
    // generateByRulesForStateMachine / generateByEndpoints / build*Test / buildStateMachineRef /
    // buildForbiddenTransitions / matchEndpoints，约 700 行）——v5.13 PRD 强制后无任何调用方，
    // 是死代码；其遗留的 rule_based source 永不产生，导致 TestCaseService.markDegraded 判定失效。

    // v1.10: PRD 驱动的 LLM 生成（PRD 为主、代码为辅）
    // v1.11: 新增 frontendResult 参数，前端上下文作为辅助信息
    // v3.2: 新增 caseCb 参数，透传给 parseTestCases 用于流式回调
    // v3.3: 新增 cancelled 参数，LLM 调用前检查取消标志
    // v3.4: 新增 params 参数，动态拼接 PRD system prompt + 调整 temperature
    // v5.14: 自动多轮补齐——每轮根据剩余 coverageGaps 继续生成，直到缺口补齐或达到轮数/条数上限
    // v7.1(G5): 新增 report 参数，轮次耗尽仍有缺口时记录未收敛降级信号
    private List<TestCase> generateByLlmWithPrd(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                                 BackendResult backendResult,
                                                 FrontendResult frontendResult,
                                                 CaseCallback caseCb, CancellationSignal cancelled,
                                                 GenerationParams params,
                                                 ProgressCallback progressCallback,
                                                 GenerationReport report) throws Exception {
        Map<String, Object> coverage = buildCoverageChecklist(prdResult, stateMachines, backendResult);
        List<TestCase> all = new ArrayList<>();
        int maxRounds = "high".equals(params != null ? params.getCaseDensity() : null)
                ? MAX_GENERATION_ROUNDS : 3;

        for (int round = 1; round <= maxRounds; round++) {
            checkCancelled(cancelled);
            Map<String, Object> gaps = remainingGaps(all, coverage);
            if (!hasRemainingGaps(gaps) || all.size() >= MAX_GENERATED_CASES) {
                break;
            }
            if (progressCallback != null) {
                progressCallback.update(round == 1 ? "基于 PRD 生成用例..." : "第 " + round + " 轮补齐覆盖缺口...");
            }
            List<TestCase> roundCases = generatePrdRound(prdResult, stateMachines, backendResult, frontendResult,
                    caseCb, cancelled, params, coverage, gaps, round, report, all);
            if (roundCases.isEmpty()) {
                break;
            }
            all.addAll(roundCases);
        }
        // v7.1(G5): 轮次耗尽仍有缺口且未达生成上限 → 真实降级信号（达上限属 G10 容量问题，不算未收敛）
        if (report != null && all.size() < MAX_GENERATED_CASES
                && hasRemainingGaps(remainingGaps(all, coverage))) {
            report.roundsNotConverged = true;
            log.warn("Generation rounds exhausted with coverage gaps remaining: {} cases", all.size());
        }
        // v7.7(G10): 达生成上限且仍有缺口——容量事实（非降级信号，不触发 markDegraded），
        // 进度明示 + 报告收录，供 complete 事件告知前端"缺口未补齐是上限所致"
        if (report != null && all.size() >= MAX_GENERATED_CASES
                && hasRemainingGaps(remainingGaps(all, coverage))) {
            report.coverageCappedByLimit = true;
            if (progressCallback != null) {
                progressCallback.update("已达生成上限(" + MAX_GENERATED_CASES + ")，剩余覆盖缺口未补齐");
            }
        }
        return all;
    }

    // v7.3(L8): 新增 report 参数，流式截断时记录 streamTruncated/truncatedRecovered
    // v7.7(G4): 新增 previousCases 参数（本轮之前已生成的全部用例），轮间摘要注入用
    private List<TestCase> generatePrdRound(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                             BackendResult backendResult, FrontendResult frontendResult,
                                             CaseCallback caseCb, CancellationSignal cancelled,
                                             GenerationParams params, Map<String, Object> coverage,
                                             Map<String, Object> gaps, int round, GenerationReport report,
                                             List<TestCase> previousCases) throws Exception {
        Map<String, Object> context = new LinkedHashMap<>();

        // PRD 为主上下文
        // v7.14(G24): prd 序列化剥离 ragContexts 原始切片——策展版 context.ragContexts
        // （truncateStrings 6×1200）已单独注入，原始切片全量随 prd 再灌一遍是重复；
        // prompt 模板已核实只引用顶层 ragContexts 键，无 prd.ragContexts 路径引用
        Map<String, Object> prdMap = objectMapper.convertValue(prdResult, Map.class);
        prdMap.remove("ragContexts");
        context.put("prd", prdMap);
        // v5.4: RAG 语义检索上下文（v6.4 切片化后按切片限流，避免大块上下文撑爆 prompt）
        context.put("ragContexts", truncateStrings(prdResult.getRagContexts(), 1200, 6));
        // v6.4: 历史失败经验注入，避免生成时重复已知失败路径
        context.put("ragFailures", truncateStrings(prdResult.getRagFailures(), 800, 3));
        // v5.10/v5.11: 补充需求与 PRD/上下文文档（随需求上下文一起注入，来源保持区分）
        // v7.10(G12): 第 2+ 轮不再注入原文——补齐轮所需信息（结构化 prd 摘要/checklist/gaps/
        // 已生成摘要）已完整，重复注入大块原文是纯 token 消耗；首轮保留以建立全局理解
        if (round == 1) {
            if (prdResult.getOtherContextInfo() != null && !prdResult.getOtherContextInfo().isBlank()) {
                context.put("supplementaryRequirements", prdResult.getOtherContextInfo());
                context.put("otherContextInfo", prdResult.getOtherContextInfo());
            }
            if (prdResult.getPrdDocs() != null && !prdResult.getPrdDocs().isEmpty()) {
                context.put("prdDocs", truncateDocs(prdResult.getPrdDocs(), 3000, 3));
            }
            if (prdResult.getContextDocs() != null && !prdResult.getContextDocs().isEmpty()) {
                context.put("contextDocs", truncateDocs(prdResult.getContextDocs(), 3000, 3));
            }
        }

        // 代码侧为辅（精简，避免 token 超限）
        List<Map<String, Object>> smList = new ArrayList<>();
        if (stateMachines != null) {
            for (StateMachine sm : stateMachines) {
                Map<String, Object> smMap = new LinkedHashMap<>();
                smMap.put("name", sm.getName());
                // v7.4(A20): 附带 source 标记——rule 表示规则兜底提取（仅状态枚举可信，transitions 为空），
                // 生成侧按来源调整信任度，避免对空数据虚构转换
                smMap.put("source", stateMachineSource(sm));
                smMap.put("states", JsonHelper.parseListMap(sm.getStates()));
                smMap.put("transitions", JsonHelper.parseListMap(sm.getTransitions()));
                smList.add(smMap);
            }
        }
        context.put("stateMachines", smList);

        // v7.7(G17): 后端上下文按需求关键词过滤——明显无关的接口/规则不进 prompt，降低 token 噪声；
        // 过滤后为空时兜底全量（宁多勿丢）；checklist 不动（coverage 语义与 prompt 注入分离）
        List<EndpointInfo> eps = backendResult != null && backendResult.getEndpoints() != null
                ? backendResult.getEndpoints() : List.of();
        List<BusinessRule> bizRules = backendResult != null && backendResult.getBusinessRules() != null
                ? backendResult.getBusinessRules() : List.of();
        String keywordText = String.join(" ", requirementKeywords(prdResult));
        List<EndpointInfo> relevantEps = eps;
        List<BusinessRule> relevantRules = bizRules;
        if (!keywordText.isBlank() && (!eps.isEmpty() || !bizRules.isEmpty())) {
            List<EndpointInfo> epsHit = eps.stream()
                    .filter(ep -> scoreTextOverlap(endpointText(ep), keywordText) > 0).toList();
            List<BusinessRule> rulesHit = bizRules.stream()
                    .filter(br -> scoreTextOverlap(ruleText(br), keywordText) > 0).toList();
            if (!epsHit.isEmpty()) {
                relevantEps = epsHit;
            }
            if (!rulesHit.isEmpty()) {
                relevantRules = rulesHit;
            }
            log.info("[G17] backend context filtered by requirement keywords: endpoints {}/{}, rules {}/{}",
                    relevantEps.size(), eps.size(), relevantRules.size(), bizRules.size());
        }
        // v7.14(G25): 弱过滤后的总量控制层——超上限按相关性降序保留 top-N（稳定排序，同分保持原序），
        // 未入选接口仍在 coverageChecklist 摘要中可引用（id/method/path/function），只是无 schema 详情
        int relevantEpCount = relevantEps.size();
        int relevantRuleCount = relevantRules.size();
        relevantEps = capEndpointsByRelevance(relevantEps, keywordText);
        relevantRules = capRulesByRelevance(relevantRules, keywordText);
        List<Map<String, Object>> epList = new ArrayList<>();
        for (EndpointInfo ep : relevantEps) {
            epList.add(ep.toContextMap());
        }
        if (relevantEps.size() < relevantEpCount) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("note", "endpoints 已按需求相关性保留前 " + relevantEps.size() + "/" + relevantEpCount
                    + " 条完整详情，其余接口见 coverageChecklist.endpoints 摘要");
            epList.add(note);
        }
        context.put("endpoints", epList);

        List<Map<String, Object>> ruleList = new ArrayList<>();
        for (BusinessRule br : relevantRules) {
            ruleList.add(br.toContextMap());
        }
        if (relevantRules.size() < relevantRuleCount) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("note", "businessRules 已按需求相关性保留前 " + relevantRules.size() + "/" + relevantRuleCount
                    + " 条完整详情，其余规则见 coverageChecklist.businessRules 摘要");
            ruleList.add(note);
        }
        context.put("businessRules", ruleList);

        // v7.6(G20层3): 错误→用户文案对照表——前端 ElMessage 文案 + 后端异常消息字面量，
        // 让 expected 用页面真实提示文案而非 HTTP 码/字段名（配合 v7.3 prompt 约束治本）
        List<Map<String, Object>> feedbackTexts = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        if (frontendResult != null && frontendResult.getUserFeedbackTexts() != null) {
            for (Map<String, Object> ft : frontendResult.getUserFeedbackTexts()) {
                String text = String.valueOf(ft.getOrDefault("text", ""));
                if (!text.isBlank() && seenTexts.add(text)) {
                    feedbackTexts.add(ft);
                }
            }
        }
        if (backendResult != null && backendResult.getErrorMessages() != null) {
            for (Map<String, Object> em : backendResult.getErrorMessages()) {
                String message = String.valueOf(em.getOrDefault("message", ""));
                if (message.isBlank() || !seenTexts.add(message)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "backend_error");
                item.put("text", message);
                item.put("source", em.getOrDefault("exception", ""));
                feedbackTexts.add(item);
            }
        }
        if (!feedbackTexts.isEmpty()) {
            context.put("userFeedbackTexts", feedbackTexts.size() > 60
                    ? new ArrayList<>(feedbackTexts.subList(0, 60)) : feedbackTexts);
        }

        // v1.11: 前端上下文（辅助）
        // v6.1fix: 只把 RAG 检索命中的组件对应 UI 元素注入 prompt；未命中时兜底全量，避免丢 UI 步骤
        // v6.1fix: 先按业务分过滤命中组件（丢掉 BackToTop/Breadcrumb 等公共组件），
        // 再同时用于 UI 元素过滤与语义摘要注入，避免两处不一致导致公共组件泄漏
        List<Map<String, Object>> hitComponents = prdResult.getFrontendComponents();
        List<Map<String, Object>> businessComponents = filterBusinessComponents(hitComponents);
        FrontendResult frontendForPrompt = frontendResult;
        if (!businessComponents.isEmpty()) {
            Set<String> relevantComponents = new HashSet<>();
            for (Map<String, Object> c : businessComponents) {
                Object comp = c == null ? null : c.get("component");
                if (comp != null && !String.valueOf(comp).isBlank()) {
                    relevantComponents.add(String.valueOf(comp));
                }
            }
            FrontendResult filtered = filterFrontendByComponents(frontendResult, relevantComponents);
            if (filtered != null && hasFrontendUi(filtered)) {
                frontendForPrompt = filtered;
            }
        }
        putFrontendContext(context, frontendForPrompt);
        // v6.1 (前端 Agentic RAG): 需求命中的组件语义摘要，供端到端用例融合 UI 交互步骤
        if (!businessComponents.isEmpty()) {
            context.put("frontendComponents", businessComponents);
        }
        // v6.1 (后端 SAINT): 操作依赖图，供端到端用例按调用链组织后端断言
        // v7.7(G17): 仅保留与过滤后接口相关的依赖——od 类名出现在任一保留 endpoint 的 function 中，空则全量
        List<OperationDep> opDepsSrc = backendResult != null && backendResult.getDependencyGraph() != null
                ? backendResult.getDependencyGraph() : List.of();
        List<OperationDep> relevantDeps = opDepsSrc;
        if (relevantEps != eps && !opDepsSrc.isEmpty()) {
            Set<String> retainedFunctions = new HashSet<>();
            for (EndpointInfo ep : relevantEps) {
                if (ep.getFunction() != null && !ep.getFunction().isBlank()) {
                    retainedFunctions.add(ep.getFunction());
                }
            }
            List<OperationDep> depsHit = opDepsSrc.stream()
                    .filter(od -> {
                        String op = od.getOperation() == null ? "" : od.getOperation();
                        String cls = op.contains(".") ? op.substring(0, op.lastIndexOf('.')) : op;
                        return !cls.isBlank() && retainedFunctions.stream().anyMatch(f -> f.contains(cls));
                    }).toList();
            if (!depsHit.isEmpty()) {
                relevantDeps = depsHit;
            }
        }
        List<Map<String, Object>> opDeps = new ArrayList<>();
        for (OperationDep od : relevantDeps) {
            opDeps.add(od.toContextMap());
        }
        if (!opDeps.isEmpty()) {
            context.put("operationDependencies", opDeps);
        }

        // v5.12: 覆盖清单与当前轮剩余缺口
        // v7.7(G10): checklist.endpoints 注入 prompt 前截断到 150，超限在尾部追加说明条目
        context.put("coverageChecklist", capChecklistForPrompt(coverage.get("checklist")));
        context.put("coverageGaps", gaps);

        // v7.10(C2): 证据链对账结果注入——需求资料晚于代码分析（staleness）或
        // PRD 状态流与代码状态机无对应状态（stateFlowConflicts）时显式标注，
        // 让 LLM 知道两条证据链的分歧点（以代码为准，需人工确认），不再静默分叉
        if (prdResult.isEvidenceStale()
                || (prdResult.getEvidenceInconsistencies() != null && !prdResult.getEvidenceInconsistencies().isEmpty())) {
            Map<String, Object> evidenceConsistency = new LinkedHashMap<>();
            if (prdResult.isEvidenceStale()) {
                evidenceConsistency.put("staleness", "需求资料在代码分析后有更新，代码上下文可能过期，建议重新分析");
            }
            if (prdResult.getEvidenceInconsistencies() != null && !prdResult.getEvidenceInconsistencies().isEmpty()) {
                evidenceConsistency.put("stateFlowConflicts", prdResult.getEvidenceInconsistencies());
            }
            context.put("evidenceConsistency", evidenceConsistency);
        }

        // v7.7(G4): 轮间摘要注入——前几轮已生成用例的标题/类型列表，配合 roundNote 禁止重复
        if (round > 1 && previousCases != null && !previousCases.isEmpty()) {
            List<Map<String, String>> summary = new ArrayList<>();
            for (TestCase tc : previousCases) {
                if (summary.size() >= 60) break;
                Map<String, String> s = new LinkedHashMap<>();
                String title = tc.getTitle() == null ? "" : tc.getTitle();
                s.put("title", title.length() > 60 ? title.substring(0, 60) : title);
                s.put("type", tc.getType() == null ? "" : tc.getType());
                summary.add(s);
            }
            context.put("generatedCasesSummary", summary);
        }

        String roundNote = round > 1
                ? "\n\n这是第 " + round + " 轮补齐：以下 coverageGaps 仍未覆盖，请优先为这些缺口生成用例，不要重复已有场景。"
                  + (context.containsKey("generatedCasesSummary")
                        ? "以下场景已生成过（见 generatedCasesSummary），禁止重复。" : "")
                : "";
        String userPrompt = "上下文信息：\n" + objectMapper.writeValueAsString(context)
                + "\n\n" + FEW_SHOT_EXAMPLES
                + "\n\n请以 PRD 文档为纲生成测试用例；上下文文档和补充需求用于补充约束与场景，代码信息用于补充接口路径与前置状态。"
                + roundNote;
        checkCancelled(cancelled);  // v3.3: LLM 调用前检查（耗时操作，最关键的取消点）
        // v3.4: 动态构建 PRD system prompt + temperature 参数化
        // v3.7: caseCb 非空时启用流式调用 + 增量解析
        if (caseCb != null) {
            StreamingTestCaseParser parser = new StreamingTestCaseParser(caseCb);
            // v7.3(L1): 取消信号 per-request 传入，避免全局取消误杀并发流
            String response = llmService.chatStreaming(
                    buildPrdDrivenPrompt(params), userPrompt, resolveTemperature(params), parser::append,
                    cancelled == null ? null : cancelled::isCancelled);
            // v7.3(L8): 流结束后检测截断——braceDepth 不归零时告警 + 局部补全抢救最后一条
            if (parser.finish() && report != null) {
                report.streamTruncated = true;
                report.truncatedRecovered = parser.getRecovered();
            }
            // v7.10(G8): 流式解析结果为唯一返回源（消除双解析索引错位）；
            // 解析数为 0 时（数组起点检测失败等边角）兜底全量重解析并推送全部
            List<TestCase> roundCases = parser.getCollected();
            if (roundCases.isEmpty()) {
                List<TestCase> reparsed = parseTestCases(extractJsonArray(response), null);
                for (TestCase tc : reparsed) {
                    try { caseCb.onCase(tc); } catch (Exception ex) {
                        log.warn("兜底推送失败: {}", ex.getMessage());
                    }
                }
                return reparsed;
            }
            return roundCases;
        }
        // caseCb 为 null（非流式场景）：原有逻辑
        String response = llmService.chat(buildPrdDrivenPrompt(params), userPrompt, resolveTemperature(params));
        String json = extractJsonArray(response);
        return parseTestCases(json, null);
    }

    // v7.11(G21): componentIds/dependencyIds 不参与收敛判定——用例侧 coverageRefs
    // 按约定只有 4 类 key，组件/依赖缺口无消减通道（恒为缺口），若纳入判定会导致
    // 多轮循环永不收敛（烧满 maxRounds 且 roundsNotConverged 恒误报降级）。
    // 这两类保留在 gaps 中作为参考清单供 LLM 提示，但从"可验证覆盖项"降级为"参考上下文"。
    private boolean hasRemainingGaps(Map<String, Object> gaps) {
        if (gaps == null) {
            return false;
        }
        for (String key : List.of("requirementIds", "transitionIds", "endpointIds", "ruleIds")) {
            Object value = gaps.get(key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> remainingGaps(List<TestCase> cases, Map<String, Object> coverage) {
        Map<String, Object> gaps = new LinkedHashMap<>();
        Object rawGaps = coverage.get("gaps");
        if (rawGaps instanceof Map<?, ?> raw) {
            for (String key : List.of("requirementIds", "transitionIds", "endpointIds", "ruleIds",
                    "componentIds", "dependencyIds")) {
                gaps.put(key, new ArrayList<>(readIdList(raw, key)));
            }
        }
        if (cases == null || cases.isEmpty()) {
            return gaps;
        }

        // v7.7(G4): requirementIds 兜底匹配用——checklist 需求项（含 G16 并入的 rag-req-*）
        List<Map<String, Object>> checklistRequirements = new ArrayList<>();
        Object rawChecklist = coverage.get("checklist");
        if (rawChecklist instanceof Map<?, ?> cl && cl.get("requirements") instanceof List<?> reqs) {
            for (Object r : reqs) {
                if (r instanceof Map<?, ?> req) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rm = (Map<String, Object>) req;
                    checklistRequirements.add(rm);
                }
            }
        }

        Set<String> coveredRequirements = new HashSet<>();
        Set<String> coveredTransitions = new HashSet<>();
        Set<String> coveredEndpoints = new HashSet<>();
        Set<String> coveredRules = new HashSet<>();
        for (TestCase tc : cases) {
            Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
            Object refsObj = hints.get("coverageRefs");
            boolean hasRequirementRefs = false;
            if (refsObj instanceof Map<?, ?> refs) {
                hasRequirementRefs = refs.get("requirementIds") instanceof List<?> l && !l.isEmpty();
                addCoveredIds(coveredRequirements, refs.get("requirementIds"));
                addCoveredIds(coveredTransitions, refs.get("transitionIds"));
                addCoveredIds(coveredEndpoints, refs.get("endpointIds"));
                addCoveredIds(coveredRules, refs.get("ruleIds"));
            }
            // v7.7(G4): LLM 未填 requirementIds 时按标题语义兜底匹配 checklist 需求——
            // 阈值 4（比 G16 的 3 严）：误判"已覆盖"会让需求永久假覆盖，漏判只是多跑一轮
            if (!hasRequirementRefs && !checklistRequirements.isEmpty()) {
                String title = tc.getTitle() == null ? "" : tc.getTitle();
                String bestId = null;
                int best = 0;
                for (Map<String, Object> req : checklistRequirements) {
                    String text = (req.get("title") == null ? "" : String.valueOf(req.get("title"))) + " "
                            + (req.get("description") == null ? "" : String.valueOf(req.get("description")));
                    int s = scoreTextOverlap(title, text);
                    if (s > best) {
                        best = s;
                        bestId = String.valueOf(req.get("id"));
                    }
                }
                if (best >= 4 && bestId != null) {
                    coveredRequirements.add(bestId);
                }
            }
            // 兜底：LLM 未填 coverageRefs 时，从用例的接口/状态机引用推断已覆盖项
            for (Map<String, Object> ep : JsonHelper.parseListMap(tc.getApiEndpoints())) {
                String method = String.valueOf(ep.getOrDefault("method", "")).trim().toUpperCase();
                String path = String.valueOf(ep.getOrDefault("path", "")).trim();
                if (!method.isBlank() && !path.isBlank()) {
                    coveredEndpoints.add(method + " " + path);
                }
            }
            Map<String, Object> smRef = JsonHelper.parseMap(tc.getStateMachineRef());
            Object transitionsObj = smRef.get("transitions");
            if (transitionsObj instanceof List<?> transitions) {
                for (Object item : transitions) {
                    if (item instanceof Map<?, ?> t) {
                        coveredTransitions.add(String.valueOf(t.get("from")) + "->" + String.valueOf(t.get("to")));
                    }
                }
            }
        }
        removeCovered(gaps, "requirementIds", coveredRequirements);
        removeCovered(gaps, "transitionIds", coveredTransitions);
        removeCovered(gaps, "endpointIds", coveredEndpoints);
        removeCovered(gaps, "ruleIds", coveredRules);
        return gaps;
    }

    private List<String> readIdList(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    ids.add(String.valueOf(item));
                }
            }
            return ids;
        }
        return new ArrayList<>();
    }

    private void addCoveredIds(Set<String> target, Object value) {
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    target.add(String.valueOf(item));
                }
            }
        }
    }

    private void removeCovered(Map<String, Object> gaps, String key, Set<String> covered) {
        Object value = gaps.get(key);
        if (value instanceof List<?> list) {
            List<Object> remaining = new ArrayList<>();
            for (Object item : list) {
                if (!covered.contains(String.valueOf(item))) {
                    remaining.add(item);
                }
            }
            gaps.put(key, remaining);
        }
    }

    // ==================== v7.7: 上下文精准投喂工具方法 ====================

    /**
     * v7.7(G16/G17/G4): token 重叠打分——双方按非字母数字汉字切词（token ≥2 字符），
     * 一方 token 在另一方文本（lowercase contains）中出现 → +token 长度；双方向取最大值。
     * 对称设计兼顾中英文：中文无空格整句成 token，反向包含可命中短语。
     */
    private int scoreTextOverlap(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0;
        }
        return Math.max(containedScore(a, b), containedScore(b, a));
    }

    private int containedScore(String tokenSource, String text) {
        String lower = text.toLowerCase();
        int score = 0;
        for (String token : tokenSource.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+")) {
            if (token.length() >= 2 && lower.contains(token)) {
                score += token.length();
            }
        }
        return score;
    }

    /**
     * v7.10(G7): 内容 hash——SHA-256(各部分以 '\u0001' 连接) 前 10 位十六进制。
     * 分隔符用不可见控制字符，避免 "ab"+"c" 与 "a"+"bc" 拼接歧义。
     * 包级可见，供单测直接验证 id 稳定语义。
     */
    String contentHash(String... parts) {
        try {
            String joined = String.join("\u0001", parts);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必然可用；极端情况下退化为内容 hashcode，仍满足"同内容同 id"
            return Integer.toHexString(String.join("\u0001", parts).hashCode());
        }
    }

    // v7.7(G16): RAG 切片标题——首个非空行剥离 "#*-" 等标记前缀，截 60 字符
    private String extractRagTitle(String slice) {
        if (slice == null) {
            return "";
        }
        for (String line : slice.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                t = t.replaceAll("^[#*\\-\\s]+", "");
                return t.length() > 60 ? t.substring(0, 60) : t;
            }
        }
        return "";
    }

    // v7.7(G16): 标题与既有需求列表的最大相似度（token 重叠分）
    private int maxSimilarityScore(String title, List<Map<String, Object>> requirements) {
        int max = 0;
        for (Map<String, Object> req : requirements) {
            String text = (req.get("title") == null ? "" : String.valueOf(req.get("title"))) + " "
                    + (req.get("description") == null ? "" : String.valueOf(req.get("description")));
            int s = scoreTextOverlap(title, text);
            if (s > max) {
                max = s;
            }
        }
        return max;
    }

    // v7.7(G10): 提取条目 id 列表并截断到上限；发生截断返回 true
    private boolean capIdsInto(Map<String, Object> gaps, String key, List<Map<String, Object>> items, int limit) {
        List<Object> ids = items.stream().map(i -> i.get("id")).toList();
        if (ids.size() <= limit) {
            gaps.put(key, ids);
            return false;
        }
        gaps.put(key, new ArrayList<>(ids.subList(0, limit)));
        return true;
    }

    // v7.7(G10): checklist.endpoints 超过 150 条时截断并在尾部追加说明条目
    //（仅影响 prompt 注入，不动 coverage 语义）
    @SuppressWarnings("unchecked")
    private Object capChecklistForPrompt(Object checklistObj) {
        if (!(checklistObj instanceof Map<?, ?> checklist)) {
            return checklistObj;
        }
        Object epsObj = checklist.get("endpoints");
        if (!(epsObj instanceof List<?> eps) || eps.size() <= 150) {
            return checklistObj;
        }
        Map<String, Object> capped = new LinkedHashMap<>((Map<String, Object>) checklist);
        List<Object> newList = new ArrayList<>(eps.subList(0, 150));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", "endpoints-truncated");
        note.put("path", "(接口清单超过 150 条已截断，仅展示前 150 条)");
        newList.add(note);
        capped.put("endpoints", newList);
        return capped;
    }

    // v7.7(G17): 需求关键词集合——requirements 标题+描述 + ragContexts，每条截 100 字符，上限 60 条
    private List<String> requirementKeywords(PrdAnalysisResult prdResult) {
        List<String> out = new ArrayList<>();
        if (prdResult == null) {
            return out;
        }
        if (prdResult.getRequirements() != null) {
            for (Map<String, Object> req : prdResult.getRequirements()) {
                if (out.size() >= 60) {
                    return out;
                }
                String text = (req.get("title") == null ? "" : String.valueOf(req.get("title"))) + " "
                        + (req.get("description") == null ? "" : String.valueOf(req.get("description")));
                if (!text.isBlank()) {
                    out.add(text.length() > 100 ? text.substring(0, 100) : text);
                }
            }
        }
        if (prdResult.getRagContexts() != null) {
            for (String s : prdResult.getRagContexts()) {
                if (out.size() >= 60) {
                    return out;
                }
                if (s != null && !s.isBlank()) {
                    out.add(s.length() > 100 ? s.substring(0, 100) : s);
                }
            }
        }
        return out;
    }

    // v7.7(G17): endpoint 参与相关性打分的文本——path + function + description + validation 拼接
    private String endpointText(EndpointInfo ep) {
        return String.join(" ",
                ep.getPath() == null ? "" : ep.getPath(),
                ep.getFunction() == null ? "" : ep.getFunction(),
                ep.getDescription() == null ? "" : ep.getDescription(),
                ep.getValidation() == null ? "" : String.join(" ", ep.getValidation()));
    }

    // v7.7(G17): businessRule 参与相关性打分的文本——rule + ruleType 拼接
    private String ruleText(BusinessRule br) {
        return String.join(" ",
                br.getRule() == null ? "" : br.getRule(),
                br.getRuleType() == null ? "" : br.getRuleType());
    }

    /**
     * v7.14(G25): context.endpoints 完整详情容量控制。超上限时按 G17 相关性分数降序保留
     * top-N（List.sort 稳定排序，同分保持原序——确定性）；关键词空白时保序截断。
     * 未超上限原样返回（同实例）。包级可见供单测。
     */
    List<EndpointInfo> capEndpointsByRelevance(List<EndpointInfo> eps, String keywordText) {
        if (eps.size() <= endpointsContextMax) {
            return eps;
        }
        List<EndpointInfo> sorted = new ArrayList<>(eps);
        if (keywordText != null && !keywordText.isBlank()) {
            sorted.sort(Comparator.comparingInt(
                    (EndpointInfo ep) -> scoreTextOverlap(endpointText(ep), keywordText)).reversed());
        }
        List<EndpointInfo> capped = new ArrayList<>(sorted.subList(0, endpointsContextMax));
        log.info("[G25] context endpoints capped: {}/{} (top by relevance, rest in checklist summary)",
                capped.size(), eps.size());
        return capped;
    }

    /** v7.14(G25): capEndpointsByRelevance 的规则同构——上限 rulesContextMax。包级可见供单测。 */
    List<BusinessRule> capRulesByRelevance(List<BusinessRule> rules, String keywordText) {
        if (rules.size() <= rulesContextMax) {
            return rules;
        }
        List<BusinessRule> sorted = new ArrayList<>(rules);
        if (keywordText != null && !keywordText.isBlank()) {
            sorted.sort(Comparator.comparingInt(
                    (BusinessRule br) -> scoreTextOverlap(ruleText(br), keywordText)).reversed());
        }
        List<BusinessRule> capped = new ArrayList<>(sorted.subList(0, rulesContextMax));
        log.info("[G25] context businessRules capped: {}/{} (top by relevance, rest in checklist summary)",
                capped.size(), rules.size());
        return capped;
    }

    // v1.11: 将前端上下文注入 context Map，截断避免 token 超限
    // v6.1 (B 方案): 对注入代生成上下文的长文档/检索片段做降采样，避免 277KB 巨型 prompt。
    private List<String> truncateStrings(List<String> list, int maxLen, int maxCount) {
        if (list == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : list) {
            if (out.size() >= maxCount) {
                break;
            }
            if (s == null) {
                continue;
            }
            out.add(s.length() > maxLen ? s.substring(0, maxLen) + "..." : s);
        }
        return out;
    }

    // v6.1: 文档内容降采样（保留 id/title/sourceType 等元数据，仅裁剪 content）。
    private List<Map<String, Object>> truncateDocs(List<Map<String, Object>> docs, int maxLen, int maxCount) {
        if (docs == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : docs) {
            if (out.size() >= maxCount) {
                break;
            }
            Map<String, Object> copy = new LinkedHashMap<>(d);
            Object content = d.get("content");
            if (content instanceof String c) {
                copy.put("content", c.length() > maxLen ? c.substring(0, maxLen) + "..." : c);
            }
            out.add(copy);
        }
        return out;
    }

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

    // v6.1fix: 按命中的组件名过滤前端确定性结果（forms/selectors/states/flows），只保留与需求相关的 UI 元素
    private FrontendResult filterFrontendByComponents(FrontendResult src, Set<String> components) {
        if (src == null || components == null || components.isEmpty()) {
            return src;
        }
        FrontendResult.FrontendResultBuilder b = FrontendResult.builder()
                .techStack(src.getTechStack())
                .routes(src.getRoutes())
                .apiCalls(src.getApiCalls())
                .componentSummaries(src.getComponentSummaries())
                .fileCount(src.getFileCount())
                .status(src.getStatus());
        b.forms(filterByComponent(src.getForms(), components));
        b.componentStates(filterByComponent(src.getComponentStates(), components));
        b.domSelectors(filterByComponent(src.getDomSelectors(), components));
        b.pageFlows(filterByComponent(src.getPageFlows(), components));
        return b.build();
    }

    private List<Map<String, Object>> filterByComponent(List<Map<String, Object>> items, Set<String> components) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Object comp = it == null ? null : it.get("component");
            if (comp != null && components.contains(String.valueOf(comp))) {
                out.add(it);
            }
        }
        return out;
    }

    // v6.1fix/v6.6: 仅保留业务分严格大于 0 的组件进入覆盖清单，过滤 0 分/负分公共组件与解析失败项
    private List<Map<String, Object>> filterBusinessComponents(List<Map<String, Object>> comps) {
        if (comps == null || comps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : comps) {
            if (businessComponentPolicy.inCoverage(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private boolean hasFrontendUi(FrontendResult fr) {
        return fr != null && (
                (fr.getForms() != null && !fr.getForms().isEmpty())
                        || (fr.getDomSelectors() != null && !fr.getDomSelectors().isEmpty())
                        || (fr.getComponentStates() != null && !fr.getComponentStates().isEmpty())
                        || (fr.getPageFlows() != null && !fr.getPageFlows().isEmpty()));
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
                    tc.setExecutionHints(mergeCoverageRefs(node.path("coverageRefs"),
                            nodeToJson(node.path("executionHints"), "{}")));
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

    // v5.12: 把 LLM 输出的 coverageRefs 合并进 executionHints，避免新增数据库字段
    private String mergeCoverageRefs(JsonNode refsNode, String hintsJson) {
        Map<String, Object> hints = JsonHelper.parseMap(hintsJson);
        if (refsNode != null && refsNode.isObject()) {
            try {
                Map<String, Object> refs = objectMapper.convertValue(refsNode, Map.class);
                hints.put("coverageRefs", refs);
            } catch (Exception e) {
                log.warn("Failed to merge coverageRefs: {}", e.getMessage());
            }
        }
        try {
            return objectMapper.writeValueAsString(hints);
        } catch (Exception e) {
            return hintsJson;
        }
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

    // v7.1(G1): 包级可见，供单测直接验证判重语义（正向/异常成对用例不得误杀）
    boolean isDuplicate(TestCase a, TestCase b) {
        String titleA = a.getTitle() == null ? "" : a.getTitle().trim();
        String titleB = b.getTitle() == null ? "" : b.getTitle().trim();
        if (titleA.isEmpty() || titleB.isEmpty()) {
            return false;
        }
        String typeA = a.getType() == null ? "" : a.getType();
        String typeB = b.getType() == null ? "" : b.getType();
        // v7.1(G1): 标题类判重（完全相同/子串/字符重叠）必须 type 一致——
        // "新增用户-正常" vs "新增用户-异常" 仅差后缀且字符重叠 83% > 旧阈值 80%，
        // 曾系统性误杀正向/异常成对用例；high 密度引导成对生成，误杀高发
        boolean sameType = typeA.equals(typeB);
        // 标题完全相同
        if (titleA.equals(titleB) && sameType) {
            return true;
        }
        // 同模块才判重
        String modA = a.getModule() == null ? "" : a.getModule();
        String modB = b.getModule() == null ? "" : b.getModule();
        if (modA.equals(modB) && sameType) {
            // v7.12(G23): 子串判重加最短门槛——2~3 字通用动词（"登录"/"查询"/"下单"）的
            // 包含关系不构成判重证据（"登录" vs "退出登录后重新登录" 曾误杀）；
            // 4 字及以上同型同模块包含仍判重，漏网真重复由批内语义去重兜底
            int shorter = Math.min(titleA.length(), titleB.length());
            if (shorter >= 4 && (titleA.contains(titleB) || titleB.contains(titleA))) {
                return true;
            }
            // 短标题字符重叠率 > 90%（v7.1: 0.8 → 0.9，宁漏勿杀——漏网真重复由批内语义去重兜底）
            if (titleA.length() <= 20 && titleB.length() <= 20) {
                Set<Character> setA = new HashSet<>();
                for (char c : titleA.toCharArray()) setA.add(c);
                Set<Character> setB = new HashSet<>();
                for (char c : titleB.toCharArray()) setB.add(c);
                Set<Character> intersection = new HashSet<>(setA);
                intersection.retainAll(setB);
                int maxLen = Math.max(setA.size(), setB.size());
                if (maxLen > 0 && (double) intersection.size() / maxLen > 0.9) {
                    return true;
                }
            }
        }
        // v5.14: 类型一致且步骤/接口指纹一致视为重复，覆盖“标题不同但步骤相同”的重复用例
        if (modA.equals(modB) && typeA.equals(typeB)
                && !caseStepsSignature(a).isEmpty()
                && caseStepsSignature(a).equals(caseStepsSignature(b))) {
            return true;
        }
        return false;
    }

    private String caseStepsSignature(TestCase tc) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> step : JsonHelper.parseListMap(tc.getStructuredSteps())) {
            sb.append(step.get("type")).append('|')
                    .append(step.get("action")).append('|')
                    .append(step.get("target")).append(';');
        }
        for (Map<String, Object> ep : JsonHelper.parseListMap(tc.getApiEndpoints())) {
            sb.append(ep.get("method")).append(' ').append(ep.get("path")).append(';');
        }
        return sb.toString();
    }

    // ==================== 质量评分（v1.2） ====================

    // v7.10(G13): 包级可见，供单测直接验证 confidence 派生语义
    void calculateQualityScores(List<TestCase> cases) {
        for (TestCase tc : cases) {
            int score = calculateQualityScore(tc);
            tc.setQualityScore(score);
            // v7.10(G13): confidence 从硬编码 0.8 改为质量分派生（qualityScore/100）——
            // 评分公式已含评审结论（v7.8 G6），confidence 与 qualityScore 同源同刻才有信息量
            tc.setConfidence(Math.round(score) / 100.0);
        }
    }

    /**
     * v7.8(G6): 质量评分并入评审结论——旧实现是纯"形式分"（字段填没填），
     * LLM 编造字段填满 = 高分，去重"保留高分者"时编造越全越容易挤掉真实用例。
     * 新公式：形式分 × 0.7（0-70）+ 评审分（0-30）- UI 语言违规扣分（0-9）。
     * 时序上评审（review）先于评分，hints.aiReview / uiLanguageViolations 已就位。
     * 包级可见，供单测直接验证评分语义。
     */
    int calculateQualityScore(TestCase tc) {
        int formScore = calculateFormScore(tc);
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        int reviewScore = reviewScoreOf(hints);
        int penalty = uiLanguagePenaltyOf(hints);
        // 整数算术（×7/10）而非 double 乘法：避免 85*0.7=59.499… 的浮点误差导致 1 分抖动
        return Math.max(0, Math.min(100, formScore * 7 / 10 + reviewScore - penalty));
    }

    /** v7.8(G6): 原 6 项形式检查原样保留为形式分（0-100），口径不变 */
    private int calculateFormScore(TestCase tc) {
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

    /**
     * v7.8(G6): 评审结论折算 0-30 分——
     * pass 30；fix 按问题数与未采纳建议数扣分（每项 -5，下限 0）；
     * 无评审记录（LLM 评审跳过/降级）15 中性不奖不罚；
     * confidence < 0.5 时评审分减半（评审本身不可信）。
     */
    private int reviewScoreOf(Map<String, Object> hints) {
        Object reviewObj = hints.get("aiReview");
        if (!(reviewObj instanceof Map)) {
            return 15;
        }
        Map<?, ?> review = (Map<?, ?>) reviewObj;
        String status = String.valueOf(review.get("status"));
        int score;
        if ("pass".equals(status)) {
            score = 30;
        } else if ("fix".equals(status)) {
            int issueCount = review.get("issues") instanceof List<?> list ? list.size() : 0;
            int suggestionCount = unappliedSuggestionCount(review);
            score = Math.max(0, 30 - issueCount * 5 - suggestionCount * 5);
        } else {
            score = 15;
        }
        if (review.get("confidence") instanceof Number n && n.doubleValue() < 0.5) {
            score /= 2;
        }
        return score;
    }

    /** v7.8(G6): 未自动采纳的建议字段数——R1 自动采纳过的（autoApplied）不罚 */
    private int unappliedSuggestionCount(Map<?, ?> review) {
        Object suggestionsObj = review.get("suggestedChanges");
        if (!(suggestionsObj instanceof Map<?, ?> suggestions)) {
            return 0;
        }
        Set<String> autoApplied = new HashSet<>();
        if (review.get("autoApplied") instanceof List<?> list) {
            for (Object o : list) {
                autoApplied.add(String.valueOf(o));
            }
        }
        int count = 0;
        for (Map.Entry<?, ?> entry : suggestions.entrySet()) {
            if (entry.getValue() != null && !autoApplied.contains(String.valueOf(entry.getKey()))) {
                count++;
            }
        }
        return count;
    }

    /** v7.8(G6): v7.3(G20层2) 的 uiLanguageViolations 参与评分——每项 -3，上限 -9 */
    private int uiLanguagePenaltyOf(Map<String, Object> hints) {
        Object violations = hints.get("uiLanguageViolations");
        int size = violations instanceof List<?> list ? list.size() : 0;
        return Math.min(9, size * 3);
    }

    // ==================== 辅助方法 ====================

    // v7.4(A19): 已删除 buildStateMachineRef / buildForbiddenTransitions / matchEndpoints——
    // v7.1(G5) 删除代码驱动生成链后无调用方的遗留死代码。

    /**
     * v7.4(A20): 从现有 sources JSON 派生状态机来源（不加数据库列，避免 Flyway 迁移）。
     * 规则兜底状态机（sources 含 rule_based 且不含 llm）transitions 恒为空、confidence 0.5，
     * 生成侧需要知道其不可信——prompt 按 source 调整信任度，rule 来源不注入 transitions 相关指令。
     */
    static String stateMachineSource(StateMachine sm) {
        if (sm == null || sm.getSources() == null) {
            return "llm";
        }
        List<String> sources = JsonHelper.parseListString(sm.getSources());
        return (sources.contains("rule_based") && !sources.contains("llm")) ? "rule" : "llm";
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
