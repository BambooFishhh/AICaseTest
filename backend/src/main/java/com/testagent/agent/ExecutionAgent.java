package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.testagent.dto.LocateResult;
import com.testagent.entity.ExecutionStep;
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

    @Autowired
    private PlaywrightRecordSkill playwrightSkill;

    @Autowired
    private McpBridgeService mcpBridgeService;

    @Autowired
    private LlmService llmService;

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
            // 步骤 1: LLM 生成元素查找描述
            String elementDesc = askLlmForElementDescription(action, testCaseContext);

            // 步骤 2: 截图（操作前）
            screenshotBefore = playwrightSkill.takeScreenshot(sessionId);

            // 步骤 3: 调 MCP 多模态识别（异常时退化为未找到，由 LLM 决策走 DOM 兜底）
            LocateResult locateResult;
            try {
                locateResult = mcpBridgeService.multimodalElementLocate(screenshotBefore, elementDesc);
            } catch (Exception e) {
                log.warn("MCP multimodal locate exception, treat as not found: {}", e.getMessage());
                locateResult = LocateResult.fail("MCP 调用异常: " + e.getMessage());
            }

            // 步骤 4: LLM 决策执行策略
            Map<String, Object> decision = askLlmForStrategy(action, target, step, locateResult);
            strategy = String.valueOf(decision.getOrDefault("strategy", "skip"));

            // 操作前页面状态（用于步骤 6 验证点击是否生效）
            Map<String, String> statusBefore = playwrightSkill.getPageStatus(sessionId);

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
                        playwrightSkill.domClick(sessionId, selType, selValue);
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
                    error = "LLM 决策跳过该步骤";
                    break;
            }

            // 步骤 6: 验证点击是否生效（仅 visual_click 和 dom_click）
            if (!"skip".equals(strategy)) {
                Map<String, String> statusAfter = playwrightSkill.getPageStatus(sessionId);
                boolean effective = askLlmIfEffective(statusBefore, statusAfter, action);

                if (!effective) {
                    // 兜底：LLM 决策是否用 DOM 重试
                    Map<String, Object> fallback = askLlmForFallback(action, statusAfter);
                    String fallbackStrategy = String.valueOf(fallback.get("strategy"));
                    if ("dom_click".equals(fallbackStrategy)) {
                        JsonNode selector = step.path("uiSelector");
                        String selType = selector.path("type").asText("css");
                        String selValue = selector.path("value").asText("");
                        if (!selValue.isEmpty()) {
                            playwrightSkill.domClick(sessionId, selType, selValue);
                            strategy = strategy + "+dom_fallback";
                            // 兜底重试后保留 passed（最佳努力重试）
                        } else {
                            result = "failed";
                            error = "操作未生效且无 DOM 选择器可兜底";
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
                .id(UUID.randomUUID().toString().substring(0, 8))
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
            String systemPrompt = "你是测试执行Agent。根据视觉识别结果，决定下一步执行策略。返回 JSON：{\"strategy\": \"visual_click|dom_click|skip\", \"reason\": \"...\", \"x\": 0, \"y\": 0, \"selectorType\": \"\", \"selectorValue\": \"\"}";
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
     * 未配置 LLM 时比较 URL 是否变化，变化则视为生效。
     */
    private boolean askLlmIfEffective(Map<String, String> statusBefore, Map<String, String> statusAfter, String action) {
        if (!llmService.isConfigured()) {
            String urlBefore = statusBefore == null ? "" : statusBefore.getOrDefault("url", "");
            String urlAfter = statusAfter == null ? "" : statusAfter.getOrDefault("url", "");
            return !urlBefore.equals(urlAfter);
        }
        try {
            String systemPrompt = "你是测试执行Agent。请判断操作是否生效。返回 JSON：{\"effective\": true/false, \"reason\": \"...\"}";
            String userPrompt = "步骤: " + action
                    + "\n操作前URL: " + (statusBefore == null ? "" : statusBefore.get("url"))
                    + "\n操作后URL: " + (statusAfter == null ? "" : statusAfter.get("url"))
                    + "\n操作后标题: " + (statusAfter == null ? "" : statusAfter.get("title"))
                    + "\n请判断操作是否生效：";
            Map<String, Object> result = llmService.chatJson(systemPrompt, userPrompt, 0.2);
            Object effective = result.get("effective");
            if (effective instanceof Boolean) {
                return (Boolean) effective;
            }
            return Boolean.parseBoolean(String.valueOf(effective));
        } catch (Exception e) {
            log.warn("askLlmIfEffective failed, fallback to URL compare: {}", e.getMessage());
            String urlBefore = statusBefore == null ? "" : statusBefore.getOrDefault("url", "");
            String urlAfter = statusAfter == null ? "" : statusAfter.getOrDefault("url", "");
            return !urlBefore.equals(urlAfter);
        }
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
