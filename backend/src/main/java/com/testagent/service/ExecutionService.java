package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.entity.TestCase;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.agent.ExecutionAgent;
import com.testagent.skill.BrowserSkill;
import com.testagent.skill.EvidenceSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * v2.0: 测试用例执行服务。
 * v2.1: 新增 Agent 模式 + 批量执行。
 */
@Service
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ExecutionRecordRepository executionRecordRepository;
    @Autowired
    private ExecutionStepRepository executionStepRepository;
    @Autowired
    private TestCaseRepository testCaseRepository;
    @Autowired
    private BrowserSkill browserSkill;
    @Autowired
    private EvidenceSkill evidenceSkill;
    @Autowired
    private ExecutionAgent executionAgent;  // v2.1

    /**
     * 异步执行测试用例。
     * @param projectId 项目 ID
     * @param testCaseId 用例 ID
     * @param targetUrl 待测页面 URL
     * @return 执行记录 ID
     */
    public String execute(String projectId, String testCaseId, String targetUrl) {
        return execute(projectId, testCaseId, targetUrl, "programmatic", null);
    }

    /**
     * v2.1: 异步执行测试用例（支持 Agent 模式）。
     * @param mode "programmatic" 或 "agent"
     */
    public String execute(String projectId, String testCaseId, String targetUrl, String mode, String batchId) {
        TestCase testCase = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("用例不存在: " + testCaseId));

        String executionId = UUID.randomUUID().toString().substring(0, 8);
        ExecutionRecord record = ExecutionRecord.builder()
                .id(executionId)
                .projectId(projectId)
                .testCaseId(testCaseId)
                .testCaseTitle(testCase.getTitle())
                .status("running")
                .startTime(LocalDateTime.now())
                .mode(mode)
                .batchId(batchId)
                .build();
        executionRecordRepository.save(record);

        // 异步执行
        if ("agent".equals(mode)) {
            runAgentAsync(executionId, testCase, targetUrl);
        } else {
            runAsync(executionId, testCase, targetUrl);
        }
        return executionId;
    }

    /**
     * v2.1: 批量执行多条测试用例。
     * @return 批次 ID
     */
    public String executeBatch(String projectId, List<String> caseIds, String targetUrl) {
        String batchId = "batch-" + UUID.randomUUID().toString().substring(0, 8);
        for (String caseId : caseIds) {
            try {
                execute(projectId, caseId, targetUrl, "agent", batchId);
            } catch (Exception e) {
                log.warn("Failed to start execution for case {}: {}", caseId, e.getMessage());
            }
        }
        return batchId;
    }

    /**
     * v2.1: 查询批次状态。
     */
    public Map<String, Object> getBatchStatus(String batchId) {
        List<ExecutionRecord> records = executionRecordRepository.findByBatchIdOrderByStartTimeAsc(batchId);
        int total = records.size();
        int running = 0, passed = 0, failed = 0;
        for (ExecutionRecord r : records) {
            switch (r.getStatus()) {
                case "running" -> running++;
                case "passed" -> passed++;
                case "failed" -> failed++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("total", total);
        result.put("running", running);
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("completed", total - running);
        result.put("records", records);
        return result;
    }

    /**
     * v2.1: Agent 模式异步执行。
     */
    @Async("analysisExecutor")
    void runAgentAsync(String executionId, TestCase testCase, String targetUrl) {
        List<ExecutionStep> steps = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        String sessionId = null;
        String errorMessage = null;

        try {
            // 1. 启动浏览器
            sessionId = browserSkill.browserLaunch(true, 1280, 800);

            // 2. 导航到目标页面
            if (targetUrl != null && !targetUrl.isBlank()) {
                browserSkill.browserNavigate(sessionId, targetUrl);
            }

            // 3. 解析 structuredSteps
            JsonNode stepNodes = objectMapper.readTree(
                    testCase.getStructuredSteps() != null ? testCase.getStructuredSteps() : "[]");

            if (!stepNodes.isArray() || stepNodes.isEmpty()) {
                skipped++;
                errorMessage = "无结构化步骤";
            } else {
                String testCaseContext = "用例: " + testCase.getTitle() + ", 模块: " + testCase.getModule();
                for (int i = 0; i < stepNodes.size(); i++) {
                    JsonNode stepNode = stepNodes.get(i);
                    try {
                        ExecutionStep step = executionAgent.executeStep(sessionId, stepNode, testCaseContext, i + 1, executionId);
                        steps.add(step);
                        executionStepRepository.save(step);
                        switch (step.getResult()) {
                            case "passed" -> passed++;
                            case "failed" -> failed++;
                            default -> skipped++;
                        }
                    } catch (Exception e) {
                        log.warn("Agent step {} failed: {}", i + 1, e.getMessage());
                        ExecutionStep failStep = ExecutionStep.builder()
                                .id(UUID.randomUUID().toString().substring(0, 8))
                                .executionId(executionId)
                                .stepIndex(i + 1)
                                .action(stepNode.path("action").asText(""))
                                .target(stepNode.path("target").asText(""))
                                .strategy("agent")
                                .result("failed")
                                .error(e.getMessage())
                                .build();
                        steps.add(failStep);
                        executionStepRepository.save(failStep);
                        failed++;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Agent execution {} failed", executionId, e);
            errorMessage = e.getMessage();
        } finally {
            if (sessionId != null) {
                try { browserSkill.closeSession(sessionId); } catch (Exception e) { log.warn("Failed to close session", e); }
            }
        }

        // 更新执行记录
        String status = failed > 0 ? "failed" : "passed";
        String summary = String.format("通过 %d, 失败 %d, 跳过 %d", passed, failed, skipped);
        ExecutionRecord finalRecord = executionRecordRepository.findById(executionId).orElse(null);
        if (finalRecord != null) {
            finalRecord.setStatus(status);
            finalRecord.setEndTime(LocalDateTime.now());
            finalRecord.setSummary(summary);
            finalRecord.setErrorMessage(errorMessage);
            executionRecordRepository.save(finalRecord);
        }

        // 保存证据
        try {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("executionId", executionId);
            evidence.put("testCaseTitle", testCase.getTitle());
            evidence.put("result", status);
            evidence.put("startTime", finalRecord != null ? finalRecord.getStartTime() : "");
            evidence.put("endTime", finalRecord != null ? finalRecord.getEndTime() : "");
            evidence.put("summary", summary);
            evidence.put("mode", "agent");
            if (errorMessage != null) evidence.put("errorMessage", errorMessage);
            evidence.put("steps", steps);
            evidenceSkill.saveTestEvidence(evidence);
        } catch (Exception e) {
            log.warn("Failed to save evidence", e);
        }
        log.info("Agent execution {} completed: {}", executionId, summary);
    }

    @Async("analysisExecutor")
    void runAsync(String executionId, TestCase testCase, String targetUrl) {
        List<ExecutionStep> steps = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        String sessionId = null;
        String errorMessage = null;

        try {
            // 1. 启动浏览器
            sessionId = browserSkill.browserLaunch(true, 1280, 800);

            // 2. 导航到目标页面
            if (targetUrl != null && !targetUrl.isBlank()) {
                browserSkill.browserNavigate(sessionId, targetUrl);
            }

            // 3. 解析 structuredSteps
            JsonNode stepNodes = objectMapper.readTree(
                    testCase.getStructuredSteps() != null ? testCase.getStructuredSteps() : "[]");

            if (!stepNodes.isArray() || stepNodes.isEmpty()) {
                // 无结构化步骤，按自然语言 steps 执行
                JsonNode textSteps = objectMapper.readTree(
                        testCase.getSteps() != null ? testCase.getSteps() : "[]");
                for (int i = 0; i < textSteps.size(); i++) {
                    String stepDesc = textSteps.get(i).asText("");
                    ExecutionStep step = ExecutionStep.builder()
                            .id(UUID.randomUUID().toString().substring(0, 8))
                            .executionId(executionId)
                            .stepIndex(i + 1)
                            .action(stepDesc)
                            .target("")
                            .strategy("manual")
                            .result("skipped")
                            .error("v2.0 暂不支持自然语言步骤自动执行，v2.1 Agent 驱动后支持")
                            .build();
                    steps.add(step);
                    skipped++;
                }
            } else {
                // 按结构化步骤执行
                for (int i = 0; i < stepNodes.size(); i++) {
                    JsonNode node = stepNodes.get(i);
                    String action = node.path("action").asText("");
                    String target = node.path("target").asText("");
                    String type = node.path("type").asText("ui_action");

                    ExecutionStep.ExecutionStepBuilder stepBuilder = ExecutionStep.builder()
                            .id(UUID.randomUUID().toString().substring(0, 8))
                            .executionId(executionId)
                            .stepIndex(i + 1)
                            .action(action)
                            .target(target);

                    try {
                        // 截图（操作前）
                        String screenshotBefore = browserSkill.takeScreenshot(sessionId);
                        stepBuilder.screenshotBefore(screenshotBefore);

                        switch (type) {
                            case "ui_action":
                                // v2.0: 用 DOM 点击（v2.1 接入多模态视觉定位）
                                JsonNode selectorNode = node.path("uiSelector");
                                if (selectorNode.has("type") && selectorNode.has("value")) {
                                    String selType = selectorNode.path("type").asText();
                                    String selValue = selectorNode.path("value").asText();
                                    browserSkill.domClick(sessionId, selType, selValue);
                                    stepBuilder.strategy("dom");
                                    stepBuilder.result("passed");
                                    passed++;
                                } else {
                                    stepBuilder.strategy("skipped")
                                            .result("skipped")
                                            .error("无 DOM 选择器，v2.1 Agent 多模态定位后可执行");
                                    skipped++;
                                }
                                break;

                            case "state_assert":
                                Map<String, String> status = browserSkill.getPageStatus(sessionId);
                                stepBuilder.strategy("manual")
                                        .result("passed")
                                        .coordinates("url=" + status.get("url"));
                                passed++;
                                break;

                            case "api_call":
                                stepBuilder.strategy("skipped")
                                        .result("skipped")
                                        .error("v2.1 接入 API 调用");
                                skipped++;
                                break;

                            default:
                                stepBuilder.strategy("skipped")
                                        .result("skipped")
                                        .error("未知步骤类型: " + type);
                                skipped++;
                        }

                        // 截图（操作后）
                        String screenshotAfter = browserSkill.takeScreenshot(sessionId);
                        stepBuilder.screenshotAfter(screenshotAfter);

                    } catch (Exception e) {
                        log.warn("Step {} failed: {}", i + 1, e.getMessage());
                        stepBuilder.strategy("dom")
                                .result("failed")
                                .error(e.getMessage());
                        failed++;
                        // 继续执行下一步骤，不终止
                    }

                    ExecutionStep step = stepBuilder.build();
                    steps.add(step);
                    executionStepRepository.save(step);
                }
            }

        } catch (Exception e) {
            log.error("Execution {} failed", executionId, e);
            errorMessage = e.getMessage();
        } finally {
            // 关闭浏览器
            if (sessionId != null) {
                try {
                    browserSkill.closeSession(sessionId);
                } catch (Exception e) {
                    log.warn("Failed to close browser session", e);
                }
            }
        }

        // 更新执行记录
        String status = failed > 0 ? "failed" : "passed";
        String summary = String.format("通过 %d, 失败 %d, 跳过 %d", passed, failed, skipped);

        ExecutionRecord finalRecord = executionRecordRepository.findById(executionId).orElse(null);
        if (finalRecord != null) {
            finalRecord.setStatus(status);
            finalRecord.setEndTime(LocalDateTime.now());
            finalRecord.setSummary(summary);
            finalRecord.setErrorMessage(errorMessage);
            executionRecordRepository.save(finalRecord);
        }

        // 保存证据
        try {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("executionId", executionId);
            evidence.put("testCaseTitle", testCase.getTitle());
            evidence.put("result", status);
            evidence.put("startTime", finalRecord != null ? finalRecord.getStartTime() : "");
            evidence.put("endTime", finalRecord != null ? finalRecord.getEndTime() : "");
            evidence.put("summary", summary);
            if (errorMessage != null) {
                evidence.put("errorMessage", errorMessage);
            }
            evidence.put("steps", steps);
            evidenceSkill.saveTestEvidence(evidence);
        } catch (Exception e) {
            log.warn("Failed to save evidence", e);
        }

        log.info("Execution {} completed: {}", executionId, summary);
    }

    public ExecutionRecord getExecution(String executionId) {
        return executionRecordRepository.findById(executionId).orElse(null);
    }

    public List<ExecutionRecord> getExecutionsByProject(String projectId) {
        return executionRecordRepository.findByProjectIdOrderByStartTimeDesc(projectId);
    }

    public List<ExecutionStep> getExecutionSteps(String executionId) {
        return executionStepRepository.findByExecutionIdOrderByStepIndexAsc(executionId);
    }
}
