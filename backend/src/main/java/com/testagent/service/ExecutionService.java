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
import org.springframework.beans.factory.annotation.Value;
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

    @Autowired
    private RuntimeStore runtimeStore;

    @Autowired
    private TaskQueueService taskQueueService;

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    @Qualifier("executionExecutor")
    private java.util.concurrent.Executor executionExecutor;

    @Autowired
    private SemanticService semanticService;
    private static final long HEARTBEAT_STALE_MS = 30_000L;

    /**
     * v7.9(E7): 单批执行用例数上限——超过后入口直接拒绝。
     * 旧实现无上限：批量超量时 execution 池 queue 满触发 CallerRunsPolicy，
     * 浏览器自动化（单条数分钟）跑在 HTTP 请求线程上导致接口挂死、batchId 不返回、用户重试重复提交。
     */
    private static final int MAX_BATCH_SIZE = 100;

    /**
     * v7.9(E10): 复制执行权限收敛开关。默认 false 保持 VIEW 即可复制执行（v4.3 现状）；
     * true 时要求 OPERATE 权限，防止只读成员对目标环境执行删除类用例。
     */
    @Value("${app.execution.copy-execute-require-operate:false}")
    private boolean copyExecuteRequireOperate;

    /**
     * v7.9(E7): 项目执行并发配额排队超时（分钟）。超时该条执行记 failed（"排队超时"），
     * 不再无限阻塞线程。<=0 禁用超时（保持旧行为，供排障）。
     */
    @Value("${app.executor.project-acquire-timeout-minutes:30}")
    private int projectAcquireTimeoutMinutes;

    /**
     * v7.9(E9): 执行记录/批次 ID 从 UUID 前 8 位加长到 16 位（64bit），消除碰撞静默覆盖。
     */
    static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

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

        String executionId = newId();
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

        // v6.6: 执行任务接入 agent_task（taskId = executionId，可被租约恢复/管理端查询）
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("testCaseId", testCaseId);
            input.put("mode", mode);
            input.put("batchId", batchId == null ? "" : batchId);
            input.put("targetUrl", targetUrl == null ? "" : targetUrl);
            input.put("writeBack", writeBack);
            agentTaskService.createTaskWithId(executionId, AgentTaskService.TYPE_EXECUTION,
                    projectId, executionId, objectMapper.writeValueAsString(input));
        } catch (Exception e) {
            log.warn("Failed to create agent task for execution {}: {}", executionId, e.getMessage());
        }

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
     * v7.9(E7): 入口限流——单批超过 MAX_BATCH_SIZE 直接拒绝，防止 execution 池 queue 满
     * 触发 CallerRunsPolicy 把浏览器自动化挤到 HTTP 请求线程（接口挂死/批次丢失/重复提交）。
     */
    public String executeBatch(String projectId, List<String> caseIds, String targetUrl) {
        projectAccessService.assertOperateAccess(projectId);
        assertBatchSize(caseIds);
        String batchId = "batch-" + newId();
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
     * v4.3: 复制执行——对选中用例做快照执行，不回写原用例状态。
     * v7.9(E10): 权限收敛开关 app.execution.copy-execute-require-operate——
     * 默认 false 维持 VIEW 口径；true 时要求 OPERATE（只读成员不能对目标环境真实执行）。
     * v7.9(E7): 同批量执行入口限流。
     */
    public Map<String, Object> copyExecute(String projectId, List<String> caseIds,
                                           String targetUrl, String mode) {
        if (copyExecuteRequireOperate) {
            projectAccessService.assertOperateAccess(projectId);
        } else {
            projectAccessService.assertViewAccess(projectId);
        }
        assertBatchSize(caseIds);
        String batchId = "copy-" + newId();
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
     * v7.9(E7): 批量入口限流——空列表拒绝；超过上限拒绝（提示分批）。
     */
    private void assertBatchSize(List<String> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            throw new BusinessException(50013, "执行用例列表为空", HttpStatus.BAD_REQUEST);
        }
        if (caseIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(50014,
                    "单批执行用例数超过上限 " + MAX_BATCH_SIZE + "（当前 " + caseIds.size() + "），请分批执行",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * v7.9(E7): 获取项目并发配额；排队超过 app.executor.project-acquire-timeout-minutes 时
     * 收尾记 failed 并返回 false（不产生僵尸 running）。<=0 禁用超时（旧行为：无限等待）。
     */
    private boolean acquireProjectPermitOrTimeout(String executionId, TestCase testCase, boolean writeBack) {
        String projectId = testCase.getProjectId();
        if (projectAcquireTimeoutMinutes <= 0) {
            projectExecutionLimiter.acquire(projectId, executionId);
            return true;
        }
        long timeoutMs = projectAcquireTimeoutMinutes * 60_000L;
        if (projectExecutionLimiter.tryAcquire(projectId, timeoutMs, executionId)) {
            return true;
        }
        log.warn("Execution {} queue timeout ({} minutes) for project {}", executionId, projectAcquireTimeoutMinutes, projectId);
        ExecutionRecord record = executionRecordRepository.findById(executionId).orElse(null);
        // v7.11(E13): 排队期间被用户取消的任务保持 cancelled 终态，不再翻转成 failed
        if (record != null && !"cancelled".equals(record.getStatus())) {
            record.setStatus("failed");
            record.setEndTime(LocalDateTime.now());
            record.setSummary("项目执行并发排队超时");
            record.setErrorMessage("项目执行并发排队超时（等待 " + projectAcquireTimeoutMinutes + " 分钟未获得执行配额）");
            executionRecordRepository.save(record);
        }
        if (writeBack) {
            updateTestCaseExecutionStatus(testCase.getId(), "not_executed");
        }
        taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
        try {
            agentTaskService.fail(executionId, "QUEUE_TIMEOUT", "项目执行并发排队超时");
        } catch (Exception e) {
            log.warn("Failed to fail agent task {}: {}", executionId, e.getMessage());
        }
        runtimeStore.removeHeartbeat(executionId);
        // v7.11(E13): 清除取消标志——取消分支（cancelExecution/cancelBatch pending 路径）
        // 已置标志且本方法即将返回 false，标志若不清理会在内存版 RuntimeStore 永久残留
        runtimeStore.clearFlag("exec:cancel:" + executionId);
        return false;
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
                // v7.0(E1): 仅改 DB 状态拦不住 worker（worker 只查运行时标志），
                // 必须同时置运行时取消标志，否则任务照跑并把 cancelled 覆盖成 passed/failed
                runtimeStore.newFlag("exec:cancel:" + r.getId()).cancel();
                agentTaskService.cancel(r.getId());
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
            // v7.0(E1): 同 cancelBatch，补运行时取消标志防止 worker 复活
            runtimeStore.newFlag("exec:cancel:" + executionId).cancel();
            agentTaskService.cancel(executionId);
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
        // v7.9(E7): 排队超时上限——超时收尾记 failed，不再无限阻塞线程
        if (!acquireProjectPermitOrTimeout(executionId, testCase, writeBack)) {
            return;
        }
        try {
            ExecutionRecord started = executionRecordRepository.findById(executionId).orElse(null);
            // 仅 pending 置 running，避免覆盖已取消/失败的状态
            if (started != null && "pending".equals(started.getStatus())) {
                started.setStatus("running");
                executionRecordRepository.save(started);
            }
            touchHeartbeat(executionId, testCase.getProjectId());
            taskQueueService.markRunning(TaskQueueService.EXECUTION_QUEUE, executionId);
        } catch (Exception e) {
            log.warn("Failed to mark execution {} running", executionId, e);
        }
        try {
            agentTaskService.start(executionId);
        } catch (Exception e) {
            log.warn("Failed to start agent task for execution {}: {}", executionId, e.getMessage());
        }

        // 启动前已被取消：直接收尾，不开浏览器
        if (isExecutionCancelled(executionId)) {
            cancelled = true;
            projectExecutionLimiter.release(testCase.getProjectId(), executionId);
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
            runtimeStore.clearFlag("exec:cancel:" + executionId);
            runtimeStore.removeHeartbeat(executionId);
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
            agentTaskService.cancel(executionId);
            return;
        }

        try {
            // 1. 启动浏览器（Playwright 自动开始录屏）
            // v7.11(E12): 以 executionId 派生会话 ID，多次执行/并发执行互不干扰
            sessionId = playwrightSkill.browserLaunch("exec-" + executionId, true, 1280, 800);
            runtimeStore.putSession(executionId, sessionId);
            agentTaskService.checkpoint(executionId, "browser_launch", null);
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
                // v8.2: blocked 语义——setup 阶段步骤失败时终止后续验证，整条记 blocked
                boolean setupFailed = false;
                String setupError = null;
                for (int i = 0; i < stepNodes.size(); i++) {
                    // v4.2: 取消检查点
                    if (isExecutionCancelled(executionId)) {
                        cancelled = true;
                        break;
                    }
                    touchHeartbeat(executionId, testCase.getProjectId());
                    JsonNode stepNode = stepNodes.get(i);
                    try {
                        ExecutionStep step = executionAgent.executeStep(sessionId, stepNode, testCaseContext, i + 1, executionId);
                        steps.add(step);
                        executionStepRepository.save(step);
                        agentTaskService.checkpoint(executionId, "step_" + (i + 1), null);
                        pauseForRecording();
                        switch (step.getResult()) {
                            case "passed" -> passed++;
                            case "failed" -> failed++;
                            default -> skipped++;
                        }
                        if ("setup".equalsIgnoreCase(stepNode.path("phase").asText(""))
                                && "failed".equals(step.getResult())) {
                            setupFailed = true;
                            setupError = step.getError();
                            break;   // 前置不满足，后续 verify 步骤无意义
                        }
                    } catch (Exception e) {
                        log.warn("Agent step {} failed: {}", i + 1, e.getMessage());
                        ExecutionStep failStep = ExecutionStep.builder()
                                .id(newId())
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
                        if ("setup".equalsIgnoreCase(stepNode.path("phase").asText(""))) {
                            setupFailed = true;
                            setupError = e.getMessage();
                            break;
                        }
                    }
                }
                if (setupFailed) {
                    errorMessage = "前置准备失败: " + (setupError == null ? "未知原因" : setupError);
                }
            }

        } catch (Exception e) {
            log.error("Agent execution {} failed", executionId, e);
            errorMessage = e.getMessage();
        } finally {
            // v2.8: 停止录屏，保存 WebM 视频
            try {
                videoPath = "outputs/recordings/" + executionId + "/video.webm";
                playwrightSkill.stopRecording(sessionId, videoPath);  // v7.11(E12): 指定会话
            } catch (Exception e) { log.warn("Failed to save recording video", e); }
            // 关闭浏览器
            if (sessionId != null) {
                try { playwrightSkill.closeSession(sessionId); } catch (Exception e) { log.warn("Failed to close session", e); }
                runtimeStore.removeSession(executionId);
            }
            projectExecutionLimiter.release(testCase.getProjectId(), executionId);
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
        }

        // 更新执行记录
        // v7.0(E1): 收尾前复查——用户可能在最后一步之后、收尾之前点取消（此时循环检查点已过）
        if (!cancelled) {
            ExecutionRecord probe = executionRecordRepository.findById(executionId).orElse(null);
            if (probe != null && "cancelled".equals(probe.getStatus())) {
                cancelled = true;
            }
        }
        String status;
        String summary;
        if (cancelled) {
            status = "cancelled";
            summary = "已取消";
        } else {
            // v7.0(E3): 基础设施故障（浏览器启动失败/导航异常/无步骤）不再记 passed
            if (errorMessage != null && failed == 0) failed++;
            // v8.2: setup 阶段失败 → blocked（前置不满足 ≠ 用例本身失败）
            if (errorMessage != null && errorMessage.startsWith("前置准备失败")) {
                status = "blocked";
                summary = String.format("通过 %d, 失败 %d, 跳过 %d（%s）",
                        passed, failed, skipped, errorMessage);
            } else {
                status = determineStatus(passed, failed, skipped);
                summary = String.format("通过 %d, 失败 %d, 跳过 %d", passed, failed, skipped)
                        + (errorMessage != null ? "（" + errorMessage + "）" : "");
            }
        }
        ExecutionRecord finalRecord = executionRecordRepository.findById(executionId).orElse(null);
        if (finalRecord != null && !"cancelled".equals(finalRecord.getStatus())) {
            // v7.0(E1): 已被取消的记录不被 worker 覆盖（取消与收尾的竞态）
            finalRecord.setStatus(status);
            finalRecord.setEndTime(LocalDateTime.now());
            finalRecord.setSummary(summary);
            finalRecord.setErrorMessage(errorMessage);
            // v2.8: 保存录屏视频路径
            finalRecord.setRecordingVideoPath(videoPath);
            executionRecordRepository.save(finalRecord);
        }
        try {
            if (cancelled) {
                agentTaskService.cancel(executionId);
            } else if ("failed".equals(status) || errorMessage != null) {
                agentTaskService.fail(executionId, "EXECUTION_FAILED",
                        errorMessage == null ? "执行失败" : errorMessage);
            } else {
                agentTaskService.succeed(executionId);
            }
        } catch (Exception e) {
            log.warn("Failed to finalize agent task for execution {}: {}", executionId, e.getMessage());
        }

        // v5.4: 失败步骤写入语义失败经验库
        // v7.10(R13): 语料补用例标题与页面 URL——检索侧向量相似度从"需求 vs 动作"
        // 改善为"需求 vs 标题+动作"；入库按内容 hash 稳定 ID 去重（SemanticService 内部处理）
        if ("failed".equals(status)) {
            for (ExecutionStep step : steps) {
                if ("failed".equals(step.getResult())) {
                    semanticService.recordFailure(testCase.getProjectId(), executionId,
                            step.getAction(), step.getError(), testCase.getTitle(), targetUrl);
                }
            }
        }

        // v3.11/v4.2: 执行结束回写用例执行状态（复制执行不回写；取消则恢复未执行）
        if (writeBack) {
            updateTestCaseExecutionStatus(testCase.getId(), "cancelled".equals(status) ? "not_executed" : status);
        }
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
        // v7.9(E7): 排队超时上限——超时收尾记 failed，不再无限阻塞线程
        if (!acquireProjectPermitOrTimeout(executionId, testCase, writeBack)) {
            return;
        }
        try {
            ExecutionRecord started = executionRecordRepository.findById(executionId).orElse(null);
            // 仅 pending 置 running，避免覆盖已取消/失败的状态
            if (started != null && "pending".equals(started.getStatus())) {
                started.setStatus("running");
                executionRecordRepository.save(started);
            }
            touchHeartbeat(executionId, testCase.getProjectId());
            taskQueueService.markRunning(TaskQueueService.EXECUTION_QUEUE, executionId);
        } catch (Exception e) {
            log.warn("Failed to mark execution {} running", executionId, e);
        }
        try {
            agentTaskService.start(executionId);
        } catch (Exception e) {
            log.warn("Failed to start agent task for execution {}: {}", executionId, e.getMessage());
        }

        // 启动前已被取消：直接收尾，不开浏览器
        if (isExecutionCancelled(executionId)) {
            cancelled = true;
            projectExecutionLimiter.release(testCase.getProjectId(), executionId);
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
            runtimeStore.clearFlag("exec:cancel:" + executionId);
            runtimeStore.removeHeartbeat(executionId);
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
            agentTaskService.cancel(executionId);
            return;
        }

        try {
            // 1. 启动浏览器（Playwright 自动开始录屏）
            // v7.11(E12): 以 executionId 派生会话 ID，多次执行/并发执行互不干扰
            sessionId = playwrightSkill.browserLaunch("exec-" + executionId, true, 1280, 800);
            runtimeStore.putSession(executionId, sessionId);
            agentTaskService.checkpoint(executionId, "browser_launch", null);
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
                            .id(newId())
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
                    touchHeartbeat(executionId, testCase.getProjectId());
                    JsonNode node = stepNodes.get(i);
                    String action = node.path("action").asText("");
                    String target = node.path("target").asText("");
                    String type = node.path("type").asText("ui_action");

                    ExecutionStep.ExecutionStepBuilder stepBuilder = ExecutionStep.builder()
                            .id(newId())
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

                            case "state_assert": {
                                Map<String, String> pageState = playwrightSkill.getPageStatus(sessionId);
                                String expected = node.path("expected").asText("");
                                // v7.0(E4): 状态断言诚实化——此前无条件 passed 是假通过
                                // v7.6(L6): 三层断言——URL/标题 → DOM 文本(textSnippet) → skipped
                                String verdict = assertExpected(expected, pageState);
                                stepBuilder.strategy("manual")
                                        .result(verdict)
                                        .coordinates("url=" + pageState.getOrDefault("url", "")
                                                + ", 页面文本=" + ExecutionAssert.snippetSummary(pageState));
                                switch (verdict) {
                                    case "passed" -> passed++;
                                    case "failed" -> {
                                        stepBuilder.error(ExecutionAssert.describe(expected, pageState));
                                        failed++;
                                    }
                                    default -> {
                                        stepBuilder.error("UI 层暂无法验证: " + expected);
                                        skipped++;
                                    }
                                }
                                break;
                            }

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
                    agentTaskService.checkpoint(executionId, "step_" + (i + 1), null);
                    pauseForRecording();
                    // v8.2: setup 阶段步骤失败 → 终止后续验证步骤
                    if ("setup".equalsIgnoreCase(node.path("phase").asText(""))
                            && "failed".equals(step.getResult())) {
                        errorMessage = "前置准备失败: " + (step.getError() == null ? "未知原因" : step.getError());
                        break;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Execution {} failed", executionId, e);
            errorMessage = e.getMessage();
        } finally {
            // v2.8: 停止录屏，保存 WebM 视频
            try {
                videoPath = "outputs/recordings/" + executionId + "/video.webm";
                playwrightSkill.stopRecording(sessionId, videoPath);  // v7.11(E12): 指定会话
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
            projectExecutionLimiter.release(testCase.getProjectId(), executionId);
            taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, executionId);
        }

        // 更新执行记录
        // v7.0(E1): 收尾前复查——用户可能在最后一步之后、收尾之前点取消（此时循环检查点已过）
        if (!cancelled) {
            ExecutionRecord probe = executionRecordRepository.findById(executionId).orElse(null);
            if (probe != null && "cancelled".equals(probe.getStatus())) {
                cancelled = true;
            }
        }
        String status;
        String summary;
        if (cancelled) {
            status = "cancelled";
            summary = "已取消";
        } else {
            // v7.0(E3): 基础设施故障（浏览器启动失败/导航异常/无步骤）不再记 passed
            if (errorMessage != null && failed == 0) failed++;
            // v8.2: setup 阶段失败 → blocked（前置不满足 ≠ 用例本身失败）
            if (errorMessage != null && errorMessage.startsWith("前置准备失败")) {
                status = "blocked";
                summary = String.format("通过 %d, 失败 %d, 跳过 %d（%s）",
                        passed, failed, skipped, errorMessage);
            } else {
                status = determineStatus(passed, failed, skipped);
                summary = String.format("通过 %d, 失败 %d, 跳过 %d", passed, failed, skipped)
                        + (errorMessage != null ? "（" + errorMessage + "）" : "");
            }
        }

        ExecutionRecord finalRecord = executionRecordRepository.findById(executionId).orElse(null);
        if (finalRecord != null && !"cancelled".equals(finalRecord.getStatus())) {
            // v7.0(E1): 已被取消的记录不被 worker 覆盖（取消与收尾的竞态）
            finalRecord.setStatus(status);
            finalRecord.setEndTime(LocalDateTime.now());
            finalRecord.setSummary(summary);
            finalRecord.setErrorMessage(errorMessage);
            // v2.8: 保存录屏视频路径
            finalRecord.setRecordingVideoPath(videoPath);
            executionRecordRepository.save(finalRecord);
        }
        try {
            if (cancelled) {
                agentTaskService.cancel(executionId);
            } else if ("failed".equals(status) || errorMessage != null) {
                agentTaskService.fail(executionId, "EXECUTION_FAILED",
                        errorMessage == null ? "执行失败" : errorMessage);
            } else {
                agentTaskService.succeed(executionId);
            }
        } catch (Exception e) {
            log.warn("Failed to finalize agent task for execution {}: {}", executionId, e.getMessage());
        }

        // v5.4: 失败步骤写入语义失败经验库
        // v7.10(R13): 语料补用例标题与页面 URL——检索侧向量相似度从"需求 vs 动作"
        // 改善为"需求 vs 标题+动作"；入库按内容 hash 稳定 ID 去重（SemanticService 内部处理）
        if ("failed".equals(status)) {
            for (ExecutionStep step : steps) {
                if ("failed".equals(step.getResult())) {
                    semanticService.recordFailure(testCase.getProjectId(), executionId,
                            step.getAction(), step.getError(), testCase.getTitle(), targetUrl);
                }
            }
        }

        // v3.11/v4.2: 执行结束回写用例执行状态（复制执行不回写；取消则恢复未执行）
        if (writeBack) {
            updateTestCaseExecutionStatus(testCase.getId(), "cancelled".equals(status) ? "not_executed" : status);
        }
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

    /**
     * v7.2(R10): 执行终态判定统一收敛——全 skipped 不再记 passed。
     * 背景：纯 api_call 用例 10 步全跳过，旧逻辑挂 passed 徽章，
     * 报告却是"通过 0/失败 0/跳过 10, 通过率 0%"，同屏自相矛盾。
     * 注意：errorMessage 已在调用方折算为 failed（v7.0 E3），此处只看三个计数。
     */
    static String determineStatus(int passed, int failed, int skipped) {
        if (failed > 0) return "failed";
        if (passed == 0 && skipped > 0) return "skipped";
        return "passed";
    }

    // v5.7: 执行历史分页 + 全量统计/趋势
    public Map<String, Object> getExecutionsByProject(String projectId, int page, int pageSize) {
        return getExecutionsByProject(projectId, page, pageSize, null);
    }

    // v5.10: 支持按用例过滤执行历史
    public Map<String, Object> getExecutionsByProject(String projectId, int page, int pageSize, String testCaseId) {
        projectAccessService.assertViewAccess(projectId);
        List<ExecutionRecord> all = new ArrayList<>(
                executionRecordRepository.findByProjectIdOrderByStartTimeDesc(projectId));
        if (testCaseId != null && !testCaseId.isBlank()) {
            all.removeIf(r -> !testCaseId.equals(r.getTestCaseId()));
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), 200);
        int from = (safePage - 1) * safeSize;
        int to = Math.min(all.size(), from + safeSize);
        List<ExecutionRecord> items = from >= all.size() ? List.of() : all.subList(from, to);

        long passed = all.stream().filter(r -> "passed".equals(r.getStatus())).count();
        long failed = all.stream().filter(r -> "failed".equals(r.getStatus())).count();
        long running = all.stream().filter(r -> "running".equals(r.getStatus())).count();
        long skipped = all.stream().filter(r -> "skipped".equals(r.getStatus())).count();
        // v8.2: blocked（前置准备失败）单独统计
        long blocked = all.stream().filter(r -> "blocked".equals(r.getStatus())).count();
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", (long) all.size());
        stats.put("passed", passed);
        stats.put("failed", failed);
        stats.put("running", running);
        stats.put("skipped", skipped);
        stats.put("blocked", blocked);

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
        return runtimeStore.isFlagSet("exec:cancel:" + executionId);
    }

    // ==================== v7.0(E4)/v7.6(L6): state_assert 断言 ====================
    // v7.6(L6): 断言逻辑移至共享工具 ExecutionAssert（程序化/Agent 两模式共用），
    // 并新增层 2 DOM 文本断言（expected 关键词与 textSnippet 包含比较）。
    // 委托方法保留包内可见性以兼容既有测试。
    static String assertExpected(String expected, Map<String, String> pageState) {
        return ExecutionAssert.assertExpected(expected, pageState);
    }

    // 标记运行中任务取消，并强制关闭其浏览器会话（让当前步骤尽快失败）
    private void markRunningCancelled(String executionId) {
        RuntimeFlag flag = runtimeStore.newFlag("exec:cancel:" + executionId);
        flag.cancel();
        // v7.11(E12): 会话 ID 与 executionId 一一对应（exec-<executionId>），兜底用 runtimeStore
        String sessionId = runtimeStore.getSession(executionId);
        if (sessionId == null) {
            sessionId = "exec-" + executionId;
        }
        // v6.0: 取消时先保存录像，避免浏览器提前关闭导致 WebM 丢失
        try {
            playwrightSkill.stopRecording(sessionId, "outputs/recordings/" + executionId + "/video.webm");
        } catch (Exception e) {
            log.warn("Failed to save recording for cancelled execution {}", executionId, e);
        }
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
    // v7.12(E15): 心跳同时续租项目并发配额（Redis 租约模型）——活跃执行租约不过期，
    // 防长执行（Agent 模式常见 >10min）租约被清理导致超发；内存实现为 no-op
    private void touchHeartbeat(String executionId, String projectId) {
        runtimeStore.putHeartbeat(executionId, System.currentTimeMillis());
        projectExecutionLimiter.renew(projectId, executionId);
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
        runtimeStore.clearFlag("exec:cancel:" + record.getId());
        runtimeStore.removeHeartbeat(record.getId());
        runtimeStore.removeSession(record.getId());
        taskQueueService.markDone(TaskQueueService.EXECUTION_QUEUE, record.getId());
        agentTaskService.cancel(record.getId());
    }
}
