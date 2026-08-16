package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.entity.Project;
import com.testagent.entity.TestCase;
import com.testagent.common.BusinessException;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.runtime.RuntimeFlag;
import com.testagent.runtime.RuntimeStore;
import com.testagent.agent.ExecutionAgent;
import com.testagent.skill.PlaywrightRecordSkill;
import com.testagent.skill.EvidenceSkill;
import com.testagent.security.SecurityUtils;
import com.testagent.service.SemanticService;
import com.testagent.service.TaskQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
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
    private ProjectRepository projectRepository;
    @Autowired
    private PlaywrightRecordSkill playwrightSkill;
    @Autowired
    private EvidenceSkill evidenceSkill;
    @Autowired
    private ExecutionAgent executionAgent;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private ProjectExecutionLimiter projectExecutionLimiter;

    // v5.2: 执行取消标志（executionId → RuntimeFlag），底层 Redis/内存
    private final java.util.concurrent.ConcurrentHashMap<String, RuntimeFlag> executionCancellations =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private RuntimeStore runtimeStore;

    @Autowired
    private TaskQueueService taskQueueService;

    @Autowired
    @Qualifier("executionExecutor")
    private java.util.concurrent.Executor executionExecutor;

    @Autowired
    private SemanticService semanticService;
    private static final long HEARTBEAT_STALE_MS = 30_000L;

    /**
     * 异步执行测试用例。
     */
    public String execute(String projectId, String testCaseId, String targetUrl) {
        return execute(projectId, testCaseId, targetUrl, "programmatic", null, true);
    }

    /**
     * v2.1: 异步执行测试用例（支持 Agent 模式）。
     * @param mode "programmatic" 或 "agent"
     */
    public String execute(String projectId, String testCaseId, String targetUrl, String mode, String batchId) {
        return execute(projectId, testCaseId, targetUrl, mode, batchId, true);
    }

    /**
     * v4.3: writeBack=false 表示复制执行——不回写原用例状态，完全隔离。
     */
    public String execute(String projectId, String testCaseId, String targetUrl,
                          String mode, String batchId, boolean writeBack) {
        projectAccessService.assertOperateAccess(projectId);
        TestCase testCase = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("用例不存在: " + testCaseId));

        // v4.2: 幂等——单条执行时若该用例已有 running 记录则拒绝
        if (writeBack && batchId == null
                && !executionRecordRepository.findByTestCaseIdAndStatus(testCaseId, "running").isEmpty()) {
            throw new BusinessException(50012, "该用例正在执行中，请勿重复触发", HttpStatus.BAD_REQUEST);
        }

        String executionId = UUID.randomUUID().toString().substring(0, 8);
        ExecutionRecord record = ExecutionRecord.builder()
                .id(executionId)
                .projectId(projectId)
                .testCaseId(testCaseId)
                .testCaseTitle(testCase.getTitle())
                // v4.2: 批量任务先进入排队（pending），真正启动后置 running
                .status(batchId != null ? "pending" : "running")
                .startTime(LocalDateTime.now())
                .mode(mode)
                .batchId(batchId)
                .operator(SecurityUtils.currentUsername())
                .writeBack(writeBack)
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

        // v5.3: 执行任务进入队列统计
        taskQueueService.enqueue(TaskQueueService.EXECUTION_QUEUE, executionId);

        // v3.11: 执行启动时回写用例执行状态（复制执行不回写）
        if (writeBack) {
            try {
                testCase.setExecutionStatus("running");
                testCaseRepository.save(testCase);
            } catch (Exception e) {
                log.warn("Failed to mark test case {} as running: {}", testCaseId, e.getMessage());
            }
        }

        // 异步执行
        if ("agent".equals(mode)) {
            executionExecutor.execute(() -> runAgentAsync(executionId, testCase, targetUrl, writeBack));
        } else {
            executionExecutor.execute(() -> runAsync(executionId, testCase, targetUrl, writeBack));
        }
        return executionId;
    }

    /**
     * v2.1: 批量执行多条测试用例。
     */
    public String executeBatch(String projectId, List<String> caseIds, String targetUrl) {
        projectAccessService.assertOperateAccess(projectId);
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
     * v4.3: 复制执行——仅需 VIEW 权限；对选中用例做快照执行，不回写原用例状态。
     */
    public Map<String, Object> copyExecute(String projectId, List<String> caseIds,
                                           String targetUrl, String mode) {
        projectAccessService.assertViewAccess(projectId);
        String batchId = "copy-" + UUID.randomUUID().toString().substring(0, 8);
        int started = 0;
        for (String caseId : caseIds) {
            try {
                execute(projectId, caseId, targetUrl, mode == null ? "agent" : mode, batchId, false);
                started++;
            } catch (Exception e) {
                log.warn("Failed to start copy execution for case {}: {}", caseId, e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("caseCount", started);
        return result;
    }

    /**
     * v2.1: 查询批次状态。
     */
    public Map<String, Object> getBatchStatus(String batchId) {
        List<ExecutionRecord> records = executionRecordRepository.findByBatchIdOrderByStartTimeAsc(batchId);
        if (!records.isEmpty()) {
            projectAccessService.assertViewAccess(records.get(0).getProjectId());
        }
        int total = records.size();
        int running = 0, passed = 0, failed = 0, queued = 0, cancelled = 0;
        for (ExecutionRecord r : records) {
            switch (r.getStatus()) {
                case "running" -> running++;
                case "passed" -> passed++;
                case "failed" -> failed++;
                case "pending" -> queued++;
                case "cancelled" -> cancelled++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("total", total);
        result.put("running", running);
        result.put("queued", queued);
        result.put("cancelled", cancelled);
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("completed", total - running - queued);
        result.put("records", records);
        // v3.12: 前端读取 executions 别名（原仅有 records，导致批次列表为空）
        result.put("executions", records);
        return result;
    }

    /**
     * v4.2: 取消批次——排队任务直接取消，运行中任务置取消标志（步骤检查点停止）。
     */
    public Map<String, Object> cancelBatch(String batchId) {
        List<ExecutionRecord> records = executionRecordRepository.findByBatchIdOrderByStartTimeAsc(batchId);
        if (!records.isEmpty()) {
            projectAccessService.assertOperateAccess(records.get(0).getProjectId());
        }
        int cancelledPending = 0, cancelledRunning = 0;
        for (ExecutionRecord r : records) {
            if ("pending".equals(r.getStatus())) {
                r.setStatus("cancelled");
                r.setEndTime(LocalDateTime.now());
                r.setSummary("已取消（未开始）");
                executionRecordRepository.save(r);
                cancelledPending++;
                // 恢复用例执行状态（复制执行不回写）
                if (Boolean.TRUE.equals(r.getWriteBack())) {
                    updateTestCaseExecutionStatus(r.getTestCaseId(), "not_executed");
                }
            } else if ("running".equals(r.getStatus())) {
                markRunningCancelled(r.getId());
                if (!isWorkerAlive(r.getId())) {
                    finalizeCancelled(r);
                }
                cancelledRunning++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("cancelledPending", cancelledPending);
        result.put("cancelledRunning", cancelledRunning);
        return result;
    }

    /**
     * 单条执行取消：pending 直接取消；running 置取消标志并强制关闭浏览器会话。
     */
    public Map<String, Object> cancelExecution(String executionId) {
        ExecutionRecord record = executionRecordRepository.findById(executionId)
                .orElseThrow(() -> BusinessException.notFound("执行记录不存在: " + executionId));
        projectAccessService.assertOperateAccess(record.getProjectId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        String status = record.getStatus();
        if ("pending".equals(status)) {
            record.setStatus("cancelled");
            record.setEndTime(LocalDateTime.now());
            record.setSummary("已取消（未开始）");
            executionRecordRepository.save(record);
            if (Boolean.TRUE.equals(record.getWriteBack())) {
                updateTestCaseExecutionStatus(record.getTestCaseId(), "not_executed");
            }
            result.put("cancelled", true);
            result.put("stage", "pending");
        } else if ("running".equals(status)) {
            markRunningCancelled(executionId);
            if (!isWorkerAlive(executionId)) {
                // worker 已死（卡死/重启遗留），直接完结
                finalizeCancelled(record);
                result.put("cancelled", true);
                result.put("stage", "running-stale");
            } else {
                result.put("cancelled", true);
                result.put("stage", "running");
            }
        } else {
            result.put("cancelled", false);
            result.put("stage", "finished");
        }
        return result;
    }

    /**
     * v2.8: Agent 模式异步执行（PlaywrightRecordSkill）。
     */
    void runAgentAsync(String executionId, TestCase testCase, String targetUrl, boolean writeBack) {
        List<ExecutionStep> steps = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        String sessionId = null;
        String errorMessage = null;
        String videoPath = null;
        boolean cancelled = false;

        // v4.2: 项目级并发配额（排队等待）→ 启动时置 running
        projectExecutionLimiter.acquire(testCase.getProjectId());
        try {
            ExecutionRecord started = executionRecordRepository.findById(executionId).orElse(null);
            // 仅 pending 置 running，避免覆盖已取消/失败的状态
            if (started != null && "pending".equals(started.getStatus())) {
                started.setStatus("running");
                executionRecordRepository.save(started);
            }
            touchHeartbeat(executionId);
            taskQueueService.markRunning(TaskQueueService.EXECUTION_QUEUE, executionId);
        } catch (Exception e) {
            log.warn("Failed to mark execution {} running", executionId, e);
        }

        // 启动前已被取消：直接收尾，不开浏览器
        if (isExecutionCancelled(executionId)) {
            cancelled = true;
            projectExecutionLimiter.release(testCase.getProjectId());
            ExecutionRecord pending = executionRecordRepository.findById(executionId).orElse(null);
            if (pending != null) {
                pending.setStatus("cancelled");
                pending.setEndTime(LocalDateTime.now());
                pending.setSummary("已取消");
                executionRecordRepository.save(pending);
            }
            if (writeBack) {
                updateTestCaseExecutionStatus(testCase.getId(), "not_executed");
            }
            executionCancellations.remove(executionId);
            runtimeStore.clearFlag("exec:cancel:" + executionId);
            runtimeStore.removeHeartbeat(executionId);
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
            return;
        }

        try {
            // 1. 启动浏览器（Playwright 自动开始录屏）
            sessionId = playwrightSkill.browserLaunch(true, 1280, 800);
            runtimeStore.putSession(executionId, sessionId);
            // 注入项目级登录 Cookie，跳过登录界面
            injectCookies(testCase.getProjectId(), sessionId);

            // 2. 导航到目标页面
            if (targetUrl != null && !targetUrl.isBlank()) {
                playwrightSkill.browserNavigate(sessionId, targetUrl);
            }

            // 3. 解析 structuredSteps（自动合并项目执行环境配置的前置步骤）
            JsonNode stepNodes = buildStepNodes(testCase);

            if (!stepNodes.isArray() || stepNodes.isEmpty()) {
                skipped++;
                errorMessage = "无结构化步骤";
            } else {
                String testCaseContext = "用例: " + testCase.getTitle() + ", 模块: " + testCase.getModule();
                for (int i = 0; i < stepNodes.size(); i++) {
                    // v4.2: 取消检查点
                    if (isExecutionCancelled(executionId)) {
                        cancelled = true;
                        break;
                    }
                    touchHeartbeat(executionId);
                    JsonNode stepNode = stepNodes.get(i);
                    try {
                        ExecutionStep step = executionAgent.executeStep(sessionId, stepNode, testCaseContext, i + 1, executionId);
                        steps.add(step);
                        executionStepRepository.save(step);
                        pauseForRecording();
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
                runtimeStore.removeSession(executionId);
            }
            projectExecutionLimiter.release(testCase.getProjectId());
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
        }

        // 更新执行记录
        String status;
        String summary;
        if (cancelled) {
            status = "cancelled";
            summary = "已取消";
        } else {
            status = failed > 0 ? "failed" : "passed";
            summary = String.format("通过 %d, 失败 %d, 跳过 %d", passed, failed, skipped);
        }
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

        // v5.4: 失败步骤写入语义失败经验库
        if ("failed".equals(status)) {
            for (ExecutionStep step : steps) {
                if ("failed".equals(step.getResult())) {
                    semanticService.recordFailure(testCase.getProjectId(), executionId, step.getAction(), step.getError());
                }
            }
        }

        // v3.11/v4.2: 执行结束回写用例执行状态（复制执行不回写；取消则恢复未执行）
        if (writeBack) {
            updateTestCaseExecutionStatus(testCase.getId(), "cancelled".equals(status) ? "not_executed" : status);
        }
        executionCancellations.remove(executionId);
        runtimeStore.clearFlag("exec:cancel:" + executionId);
        runtimeStore.removeHeartbeat(executionId);

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
    void runAsync(String executionId, TestCase testCase, String targetUrl, boolean writeBack) {
        List<ExecutionStep> steps = new ArrayList<>();
        int passed = 0, failed = 0, skipped = 0;
        String sessionId = null;
        String errorMessage = null;
        String videoPath = null;
        boolean cancelled = false;

        // v4.2: 项目级并发配额 → 启动时置 running
        projectExecutionLimiter.acquire(testCase.getProjectId());
        try {
            ExecutionRecord started = executionRecordRepository.findById(executionId).orElse(null);
            // 仅 pending 置 running，避免覆盖已取消/失败的状态
            if (started != null && "pending".equals(started.getStatus())) {
                started.setStatus("running");
                executionRecordRepository.save(started);
            }
            touchHeartbeat(executionId);
            taskQueueService.markRunning(TaskQueueService.EXECUTION_QUEUE, executionId);
        } catch (Exception e) {
            log.warn("Failed to mark execution {} running", executionId, e);
        }

        // 启动前已被取消：直接收尾，不开浏览器
        if (isExecutionCancelled(executionId)) {
            cancelled = true;
            projectExecutionLimiter.release(testCase.getProjectId());
            ExecutionRecord pending = executionRecordRepository.findById(executionId).orElse(null);
            if (pending != null) {
                pending.setStatus("cancelled");
                pending.setEndTime(LocalDateTime.now());
                pending.setSummary("已取消");
                executionRecordRepository.save(pending);
            }
            if (writeBack) {
                updateTestCaseExecutionStatus(testCase.getId(), "not_executed");
            }
            executionCancellations.remove(executionId);
            runtimeStore.clearFlag("exec:cancel:" + executionId);
            runtimeStore.removeHeartbeat(executionId);
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
            return;
        }

        try {
            // 1. 启动浏览器（Playwright 自动开始录屏）
            sessionId = playwrightSkill.browserLaunch(true, 1280, 800);
            runtimeStore.putSession(executionId, sessionId);
            // 注入项目级登录 Cookie，跳过登录界面
            injectCookies(testCase.getProjectId(), sessionId);

            // 2. 导航到目标页面
            if (targetUrl != null && !targetUrl.isBlank()) {
                playwrightSkill.browserNavigate(sessionId, targetUrl);
            }

            // 3. 解析 structuredSteps
            JsonNode stepNodes = buildStepNodes(testCase);

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
                    // v4.2: 取消检查点
                    if (isExecutionCancelled(executionId)) {
                        cancelled = true;
                        break;
                    }
                    touchHeartbeat(executionId);
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
                        int stepClickX = 0, stepClickY = 0;

                        switch (type) {
                            case "ui_action":
                                JsonNode selectorNode = node.path("uiSelector");
                                if (selectorNode.has("type") && selectorNode.has("value")) {
                                    String selType = selectorNode.path("type").asText();
                                    String selValue = selectorNode.path("value").asText();
                                    int[] clickPos = playwrightSkill.domClick(sessionId, selType, selValue);
                                    if (clickPos != null) {
                                        stepClickX = clickPos[0];
                                        stepClickY = clickPos[1];
                                    }
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

                            case "input":
                                JsonNode inputSelector = node.path("uiSelector");
                                String inputValue = node.path("inputValue").asText(node.path("value").asText(""));
                                if (inputSelector.has("type") && inputSelector.has("value") && !inputValue.isBlank()) {
                                    int[] inputPos = playwrightSkill.fillInput(
                                            sessionId,
                                            inputSelector.path("type").asText(),
                                            inputSelector.path("value").asText(),
                                            inputValue);
                                    if (inputPos != null) {
                                        stepClickX = inputPos[0];
                                        stepClickY = inputPos[1];
                                    }
                                    if (node.path("enter").asBoolean(false) || node.path("submit").asBoolean(false)) {
                                        playwrightSkill.pressKey(sessionId, "Enter");
                                    }
                                    stepBuilder.strategy("dom");
                                    stepBuilder.result("passed");
                                    passed++;
                                } else {
                                    stepBuilder.strategy("skipped")
                                            .result("skipped")
                                            .error("输入步骤缺少 uiSelector/value 或 inputValue");
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
                                stepClickX,
                                stepClickY);
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
                    pauseForRecording();
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
                runtimeStore.removeSession(executionId);
            }
            projectExecutionLimiter.release(testCase.getProjectId());
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
        }

        // 更新执行记录
        String status;
        String summary;
        if (cancelled) {
            status = "cancelled";
            summary = "已取消";
        } else {
            status = failed > 0 ? "failed" : "passed";
            summary = String.format("通过 %d, 失败 %d, 跳过 %d", passed, failed, skipped);
        }

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

        // v5.4: 失败步骤写入语义失败经验库
        if ("failed".equals(status)) {
            for (ExecutionStep step : steps) {
                if ("failed".equals(step.getResult())) {
                    semanticService.recordFailure(testCase.getProjectId(), executionId, step.getAction(), step.getError());
                }
            }
        }

        // v3.11/v4.2: 执行结束回写用例执行状态（复制执行不回写；取消则恢复未执行）
        if (writeBack) {
            updateTestCaseExecutionStatus(testCase.getId(), "cancelled".equals(status) ? "not_executed" : status);
        }
        executionCancellations.remove(executionId);
        runtimeStore.clearFlag("exec:cancel:" + executionId);
        runtimeStore.removeHeartbeat(executionId);

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
            projectAccessService.assertViewAccess(record.getProjectId());
        }
        return record;
    }

    // v5.7: 执行历史分页 + 全量统计/趋势
    public Map<String, Object> getExecutionsByProject(String projectId, int page, int pageSize) {
        projectAccessService.assertViewAccess(projectId);
        List<ExecutionRecord> all = executionRecordRepository.findByProjectIdOrderByStartTimeDesc(projectId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        int from = (safePage - 1) * safeSize;
        int to = Math.min(all.size(), from + safeSize);
        List<ExecutionRecord> items = from >= all.size() ? List.of() : all.subList(from, to);

        long passed = all.stream().filter(r -> "passed".equals(r.getStatus())).count();
        long failed = all.stream().filter(r -> "failed".equals(r.getStatus())).count();
        long running = all.stream().filter(r -> "running".equals(r.getStatus())).count();
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", (long) all.size());
        stats.put("passed", passed);
        stats.put("failed", failed);
        stats.put("running", running);

        // 最近 20 次已结束执行的滚动通过率（旧→新）
        List<ExecutionRecord> completed = all.stream()
                .filter(r -> "passed".equals(r.getStatus()) || "failed".equals(r.getStatus()))
                .limit(20)
                .collect(java.util.stream.Collectors.toList());
        Collections.reverse(completed);
        List<Integer> trend = new ArrayList<>();
        int passedCount = 0;
        for (int i = 0; i < completed.size(); i++) {
            if ("passed".equals(completed.get(i).getStatus())) {
                passedCount++;
            }
            trend.add(Math.round(passedCount * 100.0f / (i + 1)));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", all.size());
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        result.put("stats", stats);
        result.put("trend", trend);
        return result;
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

    private void injectCookies(String projectId, String sessionId) {
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null || project.getSettings() == null || project.getSettings().isBlank()) {
                return;
            }
            JsonNode settings = objectMapper.readTree(project.getSettings());
            JsonNode cookies = settings.path("executionCookies");
            if (cookies.isArray() && !cookies.isEmpty()) {
                List<Map<String, Object>> cookieList = objectMapper.convertValue(
                        cookies,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                playwrightSkill.addCookies(sessionId, cookieList);
            }
        } catch (Exception e) {
            log.warn("Failed to inject cookies for project {}", projectId, e);
        }
    }

    // 录屏节奏：步骤之间留出停顿，避免回放看起来一闪而过
    private void pauseForRecording() {
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode buildStepNodes(TestCase testCase) {
        ArrayNode all = objectMapper.createArrayNode();
        boolean hasCookies = false;
        try {
            Project project = projectRepository.findById(testCase.getProjectId()).orElse(null);
            if (project != null && project.getSettings() != null && !project.getSettings().isBlank()) {
                JsonNode settings = objectMapper.readTree(project.getSettings());
                JsonNode cookies = settings.path("executionCookies");
                hasCookies = cookies.isArray() && !cookies.isEmpty();
                if (!hasCookies) {
                    JsonNode envNode = settings.path("executionEnvironments");
                    String active = envNode.path("active").asText("");
                    JsonNode envs = envNode.path("environments");
                    if (envs.isArray()) {
                        for (JsonNode env : envs) {
                            if (active.equals(env.path("name").asText(""))) {
                                JsonNode pre = env.path("preSteps");
                                if (pre.isArray()) {
                                    for (JsonNode p : pre) {
                                        all.add(p);
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load execution preSteps for project {}", testCase.getProjectId(), e);
        }
        try {
            JsonNode steps = objectMapper.readTree(
                    testCase.getStructuredSteps() != null ? testCase.getStructuredSteps() : "[]");
            if (steps.isArray()) {
                for (JsonNode step : steps) {
                    all.add(step);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse structuredSteps for case {}", testCase.getId(), e);
        }
        return all;
    }

    // v4.2: 执行取消标志检查
    private boolean isExecutionCancelled(String executionId) {
        RuntimeFlag flag = executionCancellations.get(executionId);
        return flag != null && flag.isCancelled();
    }

    // 标记运行中任务取消，并强制关闭其浏览器会话（让当前步骤尽快失败）
    private void markRunningCancelled(String executionId) {
        RuntimeFlag flag = executionCancellations.computeIfAbsent(
                executionId, k -> runtimeStore.newFlag("exec:cancel:" + k));
        flag.cancel();
        // v6.0: 取消时先保存录像，避免浏览器提前关闭导致 WebM 丢失
        try {
            playwrightSkill.stopRecording("outputs/recordings/" + executionId + "/video.webm");
        } catch (Exception e) {
            log.warn("Failed to save recording for cancelled execution {}", executionId, e);
        }
        String sessionId = runtimeStore.getSession(executionId);
        if (sessionId != null) {
            try {
                playwrightSkill.closeSession(sessionId);
            } catch (Exception e) {
                log.warn("Failed to close session for cancelled execution {}", executionId, e);
            }
            runtimeStore.removeSession(executionId);
        }
    }

    // 心跳：worker 存活时定期更新
    private void touchHeartbeat(String executionId) {
        runtimeStore.putHeartbeat(executionId, System.currentTimeMillis());
    }

    private boolean isWorkerAlive(String executionId) {
        long last = runtimeStore.getHeartbeat(executionId);
        return last >= 0 && (System.currentTimeMillis() - last) < HEARTBEAT_STALE_MS;
    }

    // worker 已死：直接完结为已取消
    private void finalizeCancelled(ExecutionRecord record) {
        record.setStatus("cancelled");
        record.setEndTime(LocalDateTime.now());
        record.setSummary("已取消");
        executionRecordRepository.save(record);
        if (Boolean.TRUE.equals(record.getWriteBack())) {
            updateTestCaseExecutionStatus(record.getTestCaseId(), "not_executed");
        }
        executionCancellations.remove(record.getId());
        runtimeStore.clearFlag("exec:cancel:" + record.getId());
        runtimeStore.removeHeartbeat(record.getId());
        runtimeStore.removeSession(record.getId());
        taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, record.getId());
    }
}
