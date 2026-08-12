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
import com.testagent.skill.PlaywrightRecordSkill;
import com.testagent.skill.EvidenceSkill;
import com.testagent.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * v2.0: 测试用例执行服务。
 * v2.1: 新增 Agent 模式 + 批量执行。
 * v2.8: 切换到 PlaywrightRecordSkill，录屏升级为 WebM 视频。
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
    private PlaywrightRecordSkill playwrightSkill;
    @Autowired
    private EvidenceSkill evidenceSkill;
    @Autowired
    private ExecutionAgent executionAgent;

    @Autowired
    private ProjectAccessService projectAccessService;

    /**
     * 异步执行测试用例。
     */
    public String execute(String projectId, String testCaseId, String targetUrl) {
        return execute(projectId, testCaseId, targetUrl, "programmatic", null);
    }

    /**
     * v2.1: 异步执行测试用例（支持 Agent 模式）。
     * @param mode "programmatic" 或 "agent"
     */
    public String execute(String projectId, String testCaseId, String targetUrl, String mode, String batchId) {
        projectAccessService.assertProjectAccess(projectId);
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
                .operator(SecurityUtils.currentUsername())
                .build();
        // v3.16: 记录执行时用例快照（回溯执行内容）
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("title", testCase.getTitle());
            snapshot.put("module", testCase.getModule());
            snapshot.put("type", testCase.getType());
            snapshot.put("priority", testCase.getPriority());
            snapshot.put("preconditions", testCase.getPreconditions());
            snapshot.put("steps", testCase.getSteps());
            snapshot.put("expectedResults", testCase.getExpectedResults());
            snapshot.put("structuredSteps", testCase.getStructuredSteps());
            record.setTestCaseSnapshot(objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            log.warn("Failed to build test case snapshot for {}", testCaseId, e);
        }
        executionRecordRepository.save(record);

        // v3.11: 执行启动时回写用例执行状态
        try {
            testCase.setExecutionStatus("running");
            testCaseRepository.save(testCase);
        } catch (Exception e) {
            log.warn("Failed to mark test case {} as running: {}", testCaseId, e.getMessage());
        }

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
     */
    public String executeBatch(String projectId, List<String> caseIds, String targetUrl) {
        projectAccessService.assertProjectAccess(projectId);
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
        if (!records.isEmpty()) {
            projectAccessService.assertProjectAccess(records.get(0).getProjectId());
        }
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
        // v3.12: 前端读取 executions 别名（原仅有 records，导致批次列表为空）
        result.put("executions", records);
        return result;
    }

    /**
     * v2.8: Agent 模式异步执行（PlaywrightRecordSkill）。
     */
    @Async("analysisExecutor")
    void runAgentAsync(String executionId, TestCase testCase, String targetUrl) {
        List<ExecutionStep> steps = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        String sessionId = null;
        String errorMessage = null;
        String videoPath = null;

        try {
            // 1. 启动浏览器（Playwright 自动开始录屏）
            sessionId = playwrightSkill.browserLaunch(true, 1280, 800);

            // 2. 导航到目标页面
            if (targetUrl != null && !targetUrl.isBlank()) {
                playwrightSkill.browserNavigate(sessionId, targetUrl);
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
            // v2.8: 停止录屏，保存 WebM 视频
            try {
                videoPath = "outputs/recordings/" + executionId + "/video.webm";
                playwrightSkill.stopRecording(videoPath);
            } catch (Exception e) { log.warn("Failed to save recording video", e); }
            // 关闭浏览器
            if (sessionId != null) {
                try { playwrightSkill.closeSession(sessionId); } catch (Exception e) { log.warn("Failed to close session", e); }
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
            // v2.8: 保存录屏视频路径
            finalRecord.setRecordingVideoPath(videoPath);
            executionRecordRepository.save(finalRecord);
        }

        // v3.11: 执行结束回写用例执行状态
        updateTestCaseExecutionStatus(testCase.getId(), status);

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

    /**
     * v2.8: 程序化模式异步执行（PlaywrightRecordSkill）。
     */
    @Async("analysisExecutor")
    void runAsync(String executionId, TestCase testCase, String targetUrl) {
        List<ExecutionStep> steps = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        String sessionId = null;
        String errorMessage = null;
        String videoPath = null;

        try {
            // 1. 启动浏览器（Playwright 自动开始录屏）
            sessionId = playwrightSkill.browserLaunch(true, 1280, 800);

            // 2. 导航到目标页面
            if (targetUrl != null && !targetUrl.isBlank()) {
                playwrightSkill.browserNavigate(sessionId, targetUrl);
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
                            .error("暂不支持自然语言步骤自动执行，请使用 Agent 模式")
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
                        String screenshotBefore = playwrightSkill.takeScreenshot(sessionId);
                        stepBuilder.screenshotBefore(screenshotBefore);

                        switch (type) {
                            case "ui_action":
                                JsonNode selectorNode = node.path("uiSelector");
                                if (selectorNode.has("type") && selectorNode.has("value")) {
                                    String selType = selectorNode.path("type").asText();
                                    String selValue = selectorNode.path("value").asText();
                                    playwrightSkill.domClick(sessionId, selType, selValue);
                                    stepBuilder.strategy("dom");
                                    stepBuilder.result("passed");
                                    passed++;
                                } else {
                                    stepBuilder.strategy("skipped")
                                            .result("skipped")
                                            .error("无 DOM 选择器，Agent 模式支持多模态定位");
                                    skipped++;
                                }
                                break;

                            case "state_assert":
                                Map<String, String> status = playwrightSkill.getPageStatus(sessionId);
                                stepBuilder.strategy("manual")
                                        .result("passed")
                                        .coordinates("url=" + status.get("url"));
                                passed++;
                                break;

                            case "api_call":
                                stepBuilder.strategy("skipped")
                                        .result("skipped")
                                        .error("暂不支持 API 调用步骤");
                                skipped++;
                                break;

                            default:
                                stepBuilder.strategy("skipped")
                                        .result("skipped")
                                        .error("未知步骤类型: " + type);
                                skipped++;
                        }

                        // 截图（操作后）— v2.5: 带标注版本
                        String screenshotAfter = playwrightSkill.takeScreenshotWithMarker(
                                sessionId,
                                node.path("clickX").asInt(0),
                                node.path("clickY").asInt(0));
                        stepBuilder.screenshotAfter(screenshotAfter);

                    } catch (Exception e) {
                        log.warn("Step {} failed: {}", i + 1, e.getMessage());
                        stepBuilder.strategy("dom")
                                .result("failed")
                                .error(e.getMessage());
                        failed++;
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
            // v2.8: 停止录屏，保存 WebM 视频
            try {
                videoPath = "outputs/recordings/" + executionId + "/video.webm";
                playwrightSkill.stopRecording(videoPath);
            } catch (Exception e) { log.warn("Failed to save recording video", e); }
            // 关闭浏览器
            if (sessionId != null) {
                try {
                    playwrightSkill.closeSession(sessionId);
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
            // v2.8: 保存录屏视频路径
            finalRecord.setRecordingVideoPath(videoPath);
            executionRecordRepository.save(finalRecord);
        }

        // v3.11: 执行结束回写用例执行状态
        updateTestCaseExecutionStatus(testCase.getId(), status);

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
        ExecutionRecord record = executionRecordRepository.findById(executionId).orElse(null);
        if (record != null) {
            projectAccessService.assertProjectAccess(record.getProjectId());
        }
        return record;
    }

    public List<ExecutionRecord> getExecutionsByProject(String projectId) {
        projectAccessService.assertProjectAccess(projectId);
        return executionRecordRepository.findByProjectIdOrderByStartTimeDesc(projectId);
    }

    public List<ExecutionStep> getExecutionSteps(String executionId) {
        return executionStepRepository.findByExecutionIdOrderByStepIndexAsc(executionId);
    }

    /**
     * v3.11: 回写用例执行状态（running/passed/failed）。
     * 失败仅告警，不影响执行记录与证据落库。
     */
    private void updateTestCaseExecutionStatus(String testCaseId, String status) {
        try {
            testCaseRepository.findById(testCaseId).ifPresent(tc -> {
                tc.setExecutionStatus(status);
                testCaseRepository.save(tc);
            });
        } catch (Exception e) {
            log.warn("Failed to update test case {} execution status: {}", testCaseId, e.getMessage());
        }
    }
}
