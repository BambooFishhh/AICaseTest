package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.testagent.dto.LocateResult;
import com.testagent.entity.ExecutionStep;
import com.testagent.runtime.RuntimeStore;
import com.testagent.service.ExecutionAssert;
import com.testagent.service.LlmService;
import com.testagent.service.McpBridgeService;
import com.testagent.skill.PlaywrightRecordSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v2.1: LLM 驱动的 Agent 执行引擎。
 * Agent 接收测试用例步骤，自主决策调用 Skill 工具和 MCP 工具完成测试执行。
 * 每个 step 走 agentic loop：元素描述生成 → 截图 → 多模态识别 → LLM 决策 → 执行 → 验证 → 兜底。
 * 异常隔离到单步：某步失败标记 failed，不终止后续步骤。
 * v2.8: 浏览器操作从 BrowserSkill(Selenium) 切换到 PlaywrightRecordSkill(Playwright MCP)。
 */
@Component
public class ExecutionAgent {

    private static final Logger log = LoggerFactory.getLogger(ExecutionAgent.class);

    // v7.3(L5): 点击后等待 SPA 异步渲染的窗口（毫秒）
    private static final long EFFECT_CHECK_DELAY_MS = 800;

    /**
     * v7.9(E9): 步骤 ID 从 UUID 前 8 位（32bit，约 7.7 万条 50% 碰撞）加长到 16 位（64bit），
     * 消除 JPA save 静默覆盖隐患。旧 8 位记录为 String 主键，与新 ID 共存无需迁移。
     */
    static String newStepId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Autowired
    private PlaywrightRecordSkill playwrightSkill;

    @Autowired
    private McpBridgeService mcpBridgeService;

    @Autowired
    private LlmService llmService;

    @Autowired
    private RuntimeStore runtimeStore;

    /**
     * Agent 的 agentic loop，对单个测试步骤执行完整流程。
     *
     * @param sessionId        浏览器会话 ID
     * @param step             测试步骤 JSON 节点（含 action/target/uiSelector 等）
     * @param testCaseContext  测试用例上下文（标题/前置条件等）
     * @param stepIndex        步骤序号
     * @param executionId      执行记录 ID
     * @return ExecutionStep 执行步骤记录（含截图、坐标、策略、结果）
     */
    public ExecutionStep executeStep(String sessionId, JsonNode step, String testCaseContext,
                                     int stepIndex, String executionId) {
        return executeStep(sessionId, step, testCaseContext, stepIndex, executionId, null);
    }

    // v8.9.8(12.14-A): baseUrl 透传，支持导航步骤（打开目标页/路由）
    public ExecutionStep executeStep(String sessionId, JsonNode step, String testCaseContext,
                                      int stepIndex, String executionId, String baseUrl) {
        String action = step.path("action").asText("");
        String target = step.path("target").asText("");

        // 默认值：跳过
        String strategy = "skip";
        String result = "skipped";
        String coordinates = null;
        String error = null;
        String screenshotBefore = null;
        String screenshotAfter = null;
        int clickX = 0, clickY = 0;  // v2.5: 记录视觉点击坐标用于截图标注

        try {
            // v7.6(E5): 按步骤类型分流——state_assert/api_call 此前掉进"找元素→截图→定位→点击"
            // 流水线，验证步骤可能随机点中页面元素（描述撞上删除按钮即生产事故）。
            String stepType = step.path("type").asText("");
            if ("state_assert".equals(stepType)) {
                return executeStateAssert(sessionId, step, stepIndex, executionId, action, target);
            }
            if ("api_call".equals(stepType)) {
                return ExecutionStep.builder()
                        .id(newStepId())
                        .executionId(executionId)
                        .stepIndex(stepIndex)
                        .action(action)
                        .target(target)
                        .strategy("skip")
                        .result("skipped")
                        .error("Agent 模式暂不支持 API 调用步骤")
                        .build();
            }
            // v7.15(C): 脏数据防御——ui_action 的 target 是 "METHOD /path" 形态，
            // 说明接口引用被误标为页面操作（生成数据缺陷），点击必然失败；
            // 自动降级 skip 并如实记录原因，不再进入"找元素→截图→定位→点击"流水线
            if ("ui_action".equals(stepType)
                    && target != null && target.trim().matches(
                            "(?i)^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+/\\S+$")) {
                return ExecutionStep.builder()
                        .id(newStepId())
                        .executionId(executionId)
                        .stepIndex(stepIndex)
                        .action(action)
                        .target(target)
                        .strategy("skip")
                        .result("skipped")
                        .error("target 为接口引用而非页面元素（生成数据缺陷），已自动跳过点击")
                        .build();
            }
            // v8.9.8(12.14-A): 导航步骤分支——"打开/进入/跳转到 XX页(路由)"的 ui_action，不走点击流水线
            if (isNavigationStep(step, action, target)) {
                return executeNavigation(sessionId, step, stepIndex, executionId, action, target, baseUrl);
            }
            if ("input".equals(step.path("type").asText())) {
                JsonNode selector = step.path("uiSelector");
                String inputValue = step.path("inputValue").asText(step.path("value").asText(""));
                String selType = selector.path("type").asText("css");
                String selValue = selector.path("value").asText("");
                if (selValue.isBlank() || inputValue.isBlank()) {
                    throw new RuntimeException("输入步骤缺少 uiSelector.value 或 inputValue");
                }
                int[] inputPos = playwrightSkill.fillInput(sessionId, selType, selValue, inputValue);
                if (inputPos != null) {
                    clickX = inputPos[0];
                    clickY = inputPos[1];
                }
                if (step.path("enter").asBoolean(false) || step.path("submit").asBoolean(false)) {
                    playwrightSkill.pressKey(sessionId, "Enter");
                }
                strategy = "dom";
                result = "passed";
                screenshotAfter = playwrightSkill.takeScreenshotWithMarker(sessionId, clickX, clickY);
                return ExecutionStep.builder()
                        .id(newStepId())
                        .executionId(executionId)
                        .stepIndex(stepIndex)
                        .action(action)
                        .target(target)
                        .strategy(strategy)
                        .result(result)
                        .screenshotAfter(screenshotAfter)
                        .error(error)
                        .build();
            }
            // 步骤 1: LLM 生成元素查找描述
            String elementDesc = askLlmForElementDescription(action, testCaseContext);
            touchHeartbeat(executionId);  // v7.0(E8): 单步内补心跳，防慢步骤被误判 worker 已死

            // 步骤 2: 截图（操作前）
            screenshotBefore = playwrightSkill.takeScreenshot(sessionId);

            // 步骤 3: 多模态识别；找不到时上下滚动页面重试，避免元素在首屏之外
            LocateResult locateResult = null;
            String[] scrollSequence = {"", "down", "down", "up", "up"};
            for (int attempt = 0; attempt < scrollSequence.length; attempt++) {
                if (!scrollSequence[attempt].isEmpty()) {
                    playwrightSkill.scroll(sessionId, scrollSequence[attempt], 600);
                    screenshotBefore = playwrightSkill.takeScreenshot(sessionId);
                    log.info("Element not found, scrolled {} and retry locate (attempt {})",
                            scrollSequence[attempt], attempt);
                }
                try {
                    locateResult = mcpBridgeService.multimodalElementLocate(screenshotBefore, elementDesc);
                } catch (Exception e) {
                    log.warn("MCP multimodal locate exception, treat as not found: {}", e.getMessage());
                    locateResult = LocateResult.fail("MCP 调用异常: " + e.getMessage());
                }
                touchHeartbeat(executionId);  // v7.0(E8): 每轮定位后补心跳
                if (locateResult != null && locateResult.isFound()) {
                    break;
                }
            }

            // 步骤 4: LLM 决策执行策略
            Map<String, Object> decision = askLlmForStrategy(action, target, step, locateResult);
            touchHeartbeat(executionId);  // v7.0(E8): 决策调用后补心跳
            strategy = String.valueOf(decision.getOrDefault("strategy", "skip"));

            // 操作前页面状态（用于步骤 6 验证点击是否生效）
            Map<String, String> statusBefore = playwrightSkill.getPageStatus(sessionId);
            String domSelectorUsed = null;

            // 步骤 5: 执行决策
            switch (strategy) {
                case "visual_click": {
                    int x = toInt(decision.get("x"), locateResult.getClickX());
                    int y = toInt(decision.get("y"), locateResult.getClickY());
                    playwrightSkill.visualClick(sessionId, x, y);
                    coordinates = "x=" + x + ",y=" + y;
                    clickX = x;  // v2.5: 供操作后截图标注
                    clickY = y;
                    result = "passed";
                    break;
                }
                case "dom_click": {
                    JsonNode selector = step.path("uiSelector");
                    String selType = selector.path("type").asText("css");
                    String selValue = selector.path("value").asText("");
                    if (!selValue.isEmpty()) {
                        domSelectorUsed = selValue;
                        int[] clickPos = playwrightSkill.domClick(sessionId, selType, selValue);
                        if (clickPos != null) {
                            clickX = clickPos[0];
                            clickY = clickPos[1];
                        }
                        result = "passed";
                    } else {
                        // 无可用选择器，降级为跳过
                        strategy = "skip";
                        result = "skipped";
                        error = "无可用 DOM 选择器";
                    }
                    break;
                }
                case "skip":
                default:
                    result = "skipped";
                    // v7.0(E12): 错误信息按来源区分——LLM 决策带真实理由；defaultStrategy 兜底
                    // 的 reason（如"MCP 未找到且无 DOM 选择器"）不再被统一栽赃为"LLM 决策跳过"
                    Object skipReason = decision.get("reason");
                    error = (skipReason == null || String.valueOf(skipReason).isBlank())
                            ? "跳过（决策未提供理由）"
                            : "跳过: " + skipReason;
                    break;
            }

            // 步骤 6: 验证点击是否生效（仅 visual_click 和 dom_click）
            if (!"skip".equals(strategy)) {
                // v7.3(L5): 等待 SPA 异步渲染完成再取状态，避免"渲染未完成→指纹相同→误判未生效→DOM 兜底重复点击"
                try {
                    Thread.sleep(EFFECT_CHECK_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                Map<String, String> statusAfter = playwrightSkill.getPageStatus(sessionId);
                boolean effective = askLlmIfEffective(statusBefore, statusAfter, action);
                touchHeartbeat(executionId);  // v7.0(E8): 生效判断调用后补心跳

                if (!effective) {
                    // 兜底：LLM 决策是否用 DOM 重试
                    Map<String, Object> fallback = askLlmForFallback(action, statusAfter);
                    String fallbackStrategy = String.valueOf(fallback.get("strategy"));
                    if ("dom_click".equals(fallbackStrategy)) {
                        JsonNode selector = step.path("uiSelector");
                        String selType = selector.path("type").asText("css");
                        String selValue = selector.path("value").asText("");
                        // 已用 DOM 点击过同一选择器时不再重试，避免二次点击改变业务结果
                        boolean alreadyDomClicked = "dom_click".equals(strategy)
                                || (domSelectorUsed != null && domSelectorUsed.equals(selValue));
                        if (!selValue.isEmpty() && !alreadyDomClicked) {
                            int[] clickPos = playwrightSkill.domClick(sessionId, selType, selValue);
                            if (clickPos != null) {
                                clickX = clickPos[0];
                                clickY = clickPos[1];
                            }
                            strategy = strategy + "+dom_fallback";
                            // 兜底重试后保留 passed（最佳努力重试）
                        } else {
                            result = "failed";
                            error = alreadyDomClicked
                                    ? "操作未生效且已尝试 DOM 点击，为避免重复点击不再重试"
                                    : "操作未生效且无 DOM 选择器可兜底";
                        }
                    } else {
                        result = "failed";
                        error = "操作未生效，兜底策略: " + fallbackStrategy;
                    }
                }
            }

            // 步骤 7: 截图（操作后）— v2.5: 带点击坐标标注
            screenshotAfter = playwrightSkill.takeScreenshotWithMarker(sessionId, clickX, clickY);

        } catch (Exception e) {
            // 异常隔离到单步：标记 failed，不终止后续步骤
            log.warn("Step {} execution failed: {}", stepIndex, e.getMessage(), e);
            result = "failed";
            error = e.getMessage();
            // 失败场景也尽量补一张操作后截图作为证据
                try {
                    screenshotAfter = playwrightSkill.takeScreenshotWithMarker(sessionId, clickX, clickY);
                } catch (Exception ignored) {
                // 截图失败不影响错误信息
            }
        }

        // 步骤 8: 组装 ExecutionStep
        return ExecutionStep.builder()
                .id(newStepId())
                .executionId(executionId)
                .stepIndex(stepIndex)
                .action(action)
                .target(target)
                .strategy(strategy)
                .result(result)
                .screenshotBefore(screenshotBefore)
                .screenshotAfter(screenshotAfter)
                .coordinates(coordinates)
                .error(error)
                .build();
    }

    // ==================== 辅助方法 ====================

    // v8.9.8(12.14-A): 导航步骤识别——route 选择器，或 ui_action 的 target 为路由路径（/xxx 或 /goods/:id 等）
    private boolean isNavigationStep(JsonNode step, String action, String target) {
        String selType = step.path("uiSelector").path("type").asText("");
        if ("route".equals(selType)) {
            return true;
        }
        // 目标为路由路径形态 → 视为导航（frontendRoutes 注入后 LLM 常以 "/user" 等为 target，
        // 动词可能是"点击底部导航/进入/跳转"等，无法枚举，用路径形态判定最稳）
        if (target != null && target.trim().matches("^/[\\w:{}$-].*")) {
            return true;
        }
        return false;
    }

    // v8.9.8(12.14-A): 执行导航步骤——调浏览器导航，校验 URL，不进点击流水线
    private ExecutionStep executeNavigation(String sessionId, JsonNode step, int stepIndex,
                                            String executionId, String action, String target, String baseUrl) {
        String url = joinBase(baseUrl, target);
        String error = null;
        try {
            playwrightSkill.browserNavigate(sessionId, url);
            Map<String, String> status = playwrightSkill.getPageStatus(sessionId);
            String cur = status.get("url");
            if (urlHitsRoute(cur, routeKey(target))) {
                return okNav(executionId, stepIndex, action, target, cur);
            }
            // v8.9.8: hash 路由兜底——history 路由拼不到时尝试 baseUrl/#/target（SPA hash 路由）
            String hashUrl = joinBase(baseUrl, "#" + target);
            if (!hashUrl.equals(url)) {
                playwrightSkill.browserNavigate(sessionId, hashUrl);
                status = playwrightSkill.getPageStatus(sessionId);
                cur = status.get("url");
                if (urlHitsRoute(cur, routeKey(target))) {
                    return okNav(executionId, stepIndex, action, target, cur);
                }
            }
            error = "导航后 URL 未命中目标路由，当前=" + cur;
        } catch (Exception e) {
            error = "导航失败: " + e.getMessage();
        }
        return ExecutionStep.builder()
                .id(newStepId())
                .executionId(executionId)
                .stepIndex(stepIndex)
                .action(action)
                .target(target)
                .strategy("navigate")
                .result("failed")
                .error(error)
                .build();
    }

    private ExecutionStep okNav(String executionId, int stepIndex, String action, String target, String cur) {
        return ExecutionStep.builder()
                .id(newStepId())
                .executionId(executionId)
                .stepIndex(stepIndex)
                .action(action)
                .target(target)
                .strategy("navigate")
                .result("passed")
                .coordinates("url=" + cur)
                .build();
    }

    // v8.9.8(12.14-A): baseUrl + 路由拼接；处理 hash 路由（#/）与相对路径
    private String joinBase(String baseUrl, String target) {
        String t = target == null ? "" : target.trim();
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return t;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return t;
        }
        String base = baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (t.startsWith("/")) {
            return base + t;
        }
        return base + "/" + t;
    }

    // 提取路由键用于 URL 命中校验（兼容 hash 路由 /#/collect）
    private String routeKey(String target) {
        String t = target == null ? "" : target.trim();
        if (t.startsWith("/#")) {
            return t.substring(2);
        }
        if (t.startsWith("#/")) {
            return t.substring(1);
        }
        return t;
    }

    /**
     * v9.2: URL 是否真的命中目标路由——hash 路由应用必须看 # 后面的路由段。
     * 修复假通过：导航到 history 形式（base/collect）时，hash 路由 SPA 会回落到首页，
     * URL 变成 base/collect#/——path 段残留 "/collect" 但 hash 为空，页面实际停在首页，
     * 旧 contains 全串判定误判命中，导致 hash 兜底永不触发。
     */
    boolean urlHitsRoute(String url, String key) {
        if (url == null || url.isBlank() || key == null || key.isBlank()) {
            return false;
        }
        String routePart;
        int hash = url.indexOf('#');
        if (hash >= 0) {
            routePart = url.substring(hash + 1);
        } else {
            String noScheme = url.contains("://") ? url.substring(url.indexOf("://") + 3) : url;
            int slash = noScheme.indexOf('/');
            routePart = slash >= 0 ? noScheme.substring(slash) : "";
        }
        return routePart.contains(key);
    }

    /**
     * v7.6(E5/L6): state_assert 步骤——读页面状态 + 共享断言（与程序化模式一致），
     * 不进"找元素→截图→定位→点击"流水线，避免验证步骤随机点中页面元素。
     * 保留一张操作后截图作为证据（无点击坐标标注）。
     */
    private ExecutionStep executeStateAssert(String sessionId, JsonNode step, int stepIndex,
                                             String executionId, String action, String target) {
        String expected = step.path("expected").asText("");
        Map<String, String> pageState;
        try {
            pageState = playwrightSkill.getPageStatus(sessionId);
        } catch (Exception e) {
            log.warn("state_assert getPageStatus failed: {}", e.getMessage());
            return ExecutionStep.builder()
                    .id(newStepId())
                    .executionId(executionId)
                    .stepIndex(stepIndex)
                    .action(action)
                    .target(target)
                    .strategy("assert")
                    .result("skipped")
                    .error("页面状态读取失败: " + e.getMessage())
                    .build();
        }
        touchHeartbeat(executionId);
        String verdict = ExecutionAssert.assertExpected(expected, pageState);
        String error = switch (verdict) {
            case "failed" -> ExecutionAssert.describe(expected, pageState);
            case "skipped" -> "UI 层暂无法验证: " + expected;
            default -> null;
        };
        String screenshotAfter = null;
        try {
            screenshotAfter = playwrightSkill.takeScreenshot(sessionId);
        } catch (Exception e) {
            log.debug("state_assert screenshot failed: {}", e.getMessage());
        }
        return ExecutionStep.builder()
                .id(newStepId())
                .executionId(executionId)
                .stepIndex(stepIndex)
                .action(action)
                .target(target)
                .strategy("assert")
                .result(verdict)
                .screenshotAfter(screenshotAfter)
                .coordinates("url=" + pageState.getOrDefault("url", "")
                        + ", 页面文本=" + ExecutionAssert.snippetSummary(pageState))
                .error(error)
                .build();
    }

    /**
     * LLM 生成元素查找描述。
     * 未配置 LLM 时直接用 action 作为元素描述。
     */
    private String askLlmForElementDescription(String action, String testCaseContext) {
        if (!llmService.isConfigured()) {
            return action;
        }
        try {
            String systemPrompt = "你是测试执行助手。根据测试步骤描述，用简洁的自然语言描述需要在页面上找到的控件。只返回控件描述，不要其他内容。";
            String userPrompt = "测试用例上下文: " + testCaseContext + "\n当前步骤: " + action + "\n请描述要找的页面元素：";
            String desc = llmService.chat(systemPrompt, userPrompt, 0.2);
            return desc == null || desc.isBlank() ? action : desc.trim();
        } catch (Exception e) {
            log.warn("askLlmForElementDescription failed, fallback to action: {}", e.getMessage());
            return action;
        }
    }

    /**
     * LLM 决策执行策略。
     * 未配置 LLM 时按 MCP 结果直接决策：
     *   found → visual_click；未 found 但有 uiSelector → dom_click；否则 skip。
     */
    private Map<String, Object> askLlmForStrategy(String action, String target, JsonNode step, LocateResult locateResult) {
        Map<String, Object> decision = new LinkedHashMap<>();

        // 默认决策逻辑（LLM 未配置或调用异常时兜底）
        if (!llmService.isConfigured()) {
            return defaultStrategy(locateResult, step);
        }

        try {
            // v7.0(E12): 增加决策规则引导——无决策规则时 LLM 见"未找到"倾向保守 skip，
            // 即使备用 DOM 选择器可用，导致大量本可执行的步骤被放弃
            String systemPrompt = "你是测试执行Agent。根据视觉识别结果，决定下一步执行策略。返回 JSON："
                    + "{\"strategy\": \"visual_click|dom_click|skip\", \"reason\": \"...\", \"x\": 0, \"y\": 0, "
                    + "\"selectorType\": \"\", \"selectorValue\": \"\"}\n"
                    + "决策规则：\n"
                    + "1. found=true 且置信度>=0.5 → visual_click（用 MCP 返回的坐标）\n"
                    + "2. found=false 但备用DOM选择器非空 → 优先 dom_click，不要轻易放弃\n"
                    + "3. 仅当 found=false 且备用DOM选择器为空，或页面明确不存在该元素时才 skip\n"
                    + "4. reason 必须说明具体原因，skip 时尤其要说清为什么";
            String userPrompt = "步骤: " + action
                    + "\nMCP结果: found=" + locateResult.isFound()
                    + ", clickX=" + locateResult.getClickX()
                    + ", clickY=" + locateResult.getClickY()
                    + ", confidence=" + locateResult.getConfidence()
                    + "\n备用DOM选择器: " + extractSelectorInfo(step);
            Map<String, Object> llmDecision = llmService.chatJson(systemPrompt, userPrompt, 0.2);
            if (llmDecision == null || llmDecision.isEmpty() || !llmDecision.containsKey("strategy")) {
                log.warn("LLM strategy decision empty, fallback to default");
                return defaultStrategy(locateResult, step);
            }
            return llmDecision;
        } catch (Exception e) {
            log.warn("askLlmForStrategy failed, fallback to default: {}", e.getMessage());
            return defaultStrategy(locateResult, step);
        }
    }

    /**
     * v7.0(E8): 单步内补心跳。Agent 模式单步 = 最多 5 轮定位 + 4 次 LLM 调用，
     * 常超 HEARTBEAT_STALE_MS(30s)，期间取消会被误判 worker 已死触发复活竞态。
     * 失败不影响执行。
     */
    private void touchHeartbeat(String executionId) {
        try {
            runtimeStore.putHeartbeat(executionId, System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("touchHeartbeat failed for {}: {}", executionId, e.getMessage());
        }
    }

    /**
     * LLM 未配置/异常时的默认策略。
     */
    private Map<String, Object> defaultStrategy(LocateResult locateResult, JsonNode step) {
        Map<String, Object> decision = new LinkedHashMap<>();
        if (locateResult.isFound()) {
            decision.put("strategy", "visual_click");
            decision.put("x", locateResult.getClickX());
            decision.put("y", locateResult.getClickY());
            decision.put("reason", "LLM 未配置，MCP 找到元素，直接视觉点击");
        } else {
            JsonNode selector = step.path("uiSelector");
            String selValue = selector.path("value").asText("");
            if (!selValue.isEmpty()) {
                decision.put("strategy", "dom_click");
                decision.put("selectorType", selector.path("type").asText("css"));
                decision.put("selectorValue", selValue);
                decision.put("reason", "MCP 未找到元素，回退 DOM 点击");
            } else {
                decision.put("strategy", "skip");
                decision.put("reason", "MCP 未找到且无 DOM 选择器");
            }
        }
        return decision;
    }

    /**
     * LLM 判断操作是否生效。
     * v7.3(L5): 未配置 LLM 时退化为 URL+title+textSnippet 三指纹比较——
     * 旧版仅比 URL 在 SPA（URL 不变）场景下几乎必判"未生效"，触发 DOM 兜底重复点击（重复下单/提交）。
     * textSnippet 是页面 body 文本前 500 字符快照（MCP browser_get_page_status），零额外调用。
     * v7.9(E6): 两级判断——①本地三指纹任一变化直接判生效（省一次 LLM 调用：旧实现无论指纹
     * 是否变化都调 LLM，而 LLM 的输入与本地比较完全相同，页面已变化时该调用纯冗余）；
     * ②指纹完全相同（SPA 局部更新/确实无变化存疑）才调 LLM 终审，并在 prompt 中明示
     * "快照无变化"事实，避免 LLM 无证据幻觉式判生效。
     */
    private boolean askLlmIfEffective(Map<String, String> statusBefore, Map<String, String> statusAfter, String action) {
        boolean changed = pageChanged(statusBefore, statusAfter);
        if (!llmService.isConfigured()) {
            return changed;
        }
        if (changed) {
            // v7.9(E6): 指纹已变化（URL/title/文本快照任一），本地证据充分，直接判生效
            return true;
        }
        try {
            // v7.3(L5/E6最小版): 注入操作前后文本快照，让 LLM 有证据判断而非无据猜 URL
            String systemPrompt = "你是测试执行Agent。请根据操作前后页面文本快照判断操作是否生效。"
                    + "前后快照出现差异（新元素/提示/列表变化）即视为生效；SPA 页面 URL 不变不代表未生效。"
                    + "返回 JSON：{\"effective\": true/false, \"reason\": \"...\"}";
            String userPrompt = "步骤: " + action
                    + "\n操作前URL: " + (statusBefore == null ? "" : statusBefore.get("url"))
                    + "\n操作后URL: " + (statusAfter == null ? "" : statusAfter.get("url"))
                    + "\n操作后标题: " + (statusAfter == null ? "" : statusAfter.get("title"))
                    + "\n操作前页面文本快照: " + snippet(statusBefore)
                    + "\n操作后页面文本快照: " + snippet(statusAfter)
                    + "\n注意：操作前后页面文本快照无变化（本地指纹比较未发现差异）。"
                    + "\n请判断操作是否生效：";
            Map<String, Object> result = llmService.chatJson(systemPrompt, userPrompt, 0.2);
            Object effective = result.get("effective");
            if (effective instanceof Boolean) {
                return (Boolean) effective;
            }
            return Boolean.parseBoolean(String.valueOf(effective));
        } catch (Exception e) {
            log.warn("askLlmIfEffective failed, fallback to page fingerprint compare: {}", e.getMessage());
            return pageChanged(statusBefore, statusAfter);
        }
    }

    /** v7.3(L5): 无 LLM 时的生效判断——URL/title/textSnippet 任一变化即生效 */
    static boolean pageChanged(Map<String, String> before, Map<String, String> after) {
        if (before == null || after == null) {
            // 状态获取失败时保守判"未生效"（与旧行为一致，交由兜底逻辑处理）
            return false;
        }
        return !before.getOrDefault("url", "").equals(after.getOrDefault("url", ""))
                || !before.getOrDefault("title", "").equals(after.getOrDefault("title", ""))
                || !before.getOrDefault("textSnippet", "").equals(after.getOrDefault("textSnippet", ""));
    }

    private String snippet(Map<String, String> status) {
        if (status == null) {
            return "";
        }
        String text = status.getOrDefault("textSnippet", "");
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    /**
     * LLM 决策兜底策略（操作未生效时是否用 DOM 重试）。
     * 未配置 LLM 时默认走 DOM 兜底。
     */
    private Map<String, Object> askLlmForFallback(String action, Map<String, String> statusAfter) {
        Map<String, Object> decision = new LinkedHashMap<>();
        if (!llmService.isConfigured()) {
            decision.put("strategy", "dom_click");
            decision.put("reason", "LLM 未配置，默认 DOM 兜底");
            return decision;
        }
        try {
            String systemPrompt = "你是测试执行Agent。当前操作未生效，请决定是否使用 DOM 选择器兜底重试。返回 JSON：{\"strategy\": \"dom_click|skip\", \"reason\": \"...\"}";
            String userPrompt = "步骤: " + action
                    + "\n当前页面状态: url=" + (statusAfter == null ? "" : statusAfter.get("url"))
                    + ", title=" + (statusAfter == null ? "" : statusAfter.get("title"))
                    + "\n请决定是否用 DOM 选择器兜底重试：";
            Map<String, Object> llmDecision = llmService.chatJson(systemPrompt, userPrompt, 0.2);
            if (llmDecision == null || llmDecision.isEmpty() || !llmDecision.containsKey("strategy")) {
                decision.put("strategy", "dom_click");
                decision.put("reason", "LLM 返回空，默认 DOM 兜底");
                return decision;
            }
            return llmDecision;
        } catch (Exception e) {
            log.warn("askLlmForFallback failed, default to dom_click: {}", e.getMessage());
            decision.put("strategy", "dom_click");
            decision.put("reason", "LLM 异常，默认 DOM 兜底: " + e.getMessage());
            return decision;
        }
    }

    /**
     * 从 step 提取 uiSelector 信息，供 LLM 决策参考。
     */
    private String extractSelectorInfo(JsonNode step) {
        JsonNode selector = step.path("uiSelector");
        if (selector.isMissingNode() || selector.isNull()) {
            return "无";
        }
        String type = selector.path("type").asText("");
        String value = selector.path("value").asText("");
        if (value.isEmpty()) {
            return "无";
        }
        return type + "=" + value;
    }

    /**
     * 将 LLM 返回的数字字段安全转为 int。
     * Jackson 可能返回 Integer/Long/Double 等 Number 子类。
     */
    private int toInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
