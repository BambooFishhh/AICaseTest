package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import com.testagent.agent.OrchestratorAgent;
import com.testagent.agent.TestCaseReviewAgent;
import com.testagent.agent.TestGeneratorAgent;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.BusinessRule;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.common.BusinessException;
import com.testagent.common.UploadGuard;
import com.testagent.dto.CreateTestCaseRequest;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.TestCaseDTO;
import com.testagent.dto.TestCaseListResponse;
import com.testagent.dto.TestCaseVersionDTO;
import com.testagent.dto.UpdateTestCaseRequest;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.entity.TestCaseVersion;
import com.testagent.mcp.McpClientManager;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseAiReviewRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.TestCaseVersionRepository;
import com.testagent.runtime.RuntimeFlag;
import com.testagent.runtime.RuntimeStore;
import com.testagent.service.TaskQueueService;
import com.testagent.service.SemanticService;
import com.testagent.service.ProjectAccessService;
import com.testagent.service.XmindService;
import com.testagent.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.testagent.common.GenerationCancelledException;

@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // v3.3: 取消标志注册表（projectId → cancelled flag），供 cancel 端点触发
    private final ConcurrentHashMap<String, RuntimeFlag> cancellationFlags = new ConcurrentHashMap<>();

    @Autowired
    private RuntimeStore runtimeStore;

    @Autowired
    private TaskQueueService taskQueueService;

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private SemanticService semanticService;

    @Autowired
    private SemanticIndexingAsyncService semanticIndexingAsyncService;

    @Autowired
    private TestCasePersistenceService testCasePersistenceService;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestCaseVersionRepository testCaseVersionRepository;

    @Autowired
    private TestCaseAiReviewRepository aiReviewRepository;

    @Autowired
    private AiReviewHistoryRecorder aiReviewHistoryRecorder;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;

    @Autowired
    private TestGeneratorAgent testGeneratorAgent;

    @Autowired
    private TestCaseReviewAgent testCaseReviewAgent;

    @Autowired
    private OrchestratorAgent orchestratorAgent;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private XmindService xmindService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private UploadGuard uploadGuard;

    @Autowired
    private McpClientManager mcpClientManager;

    @Autowired
    private LlmService llmService;

    private boolean hasPrd(Project project) {
        if (project.getPrdContent() != null && !project.getPrdContent().isBlank()) {
            return true;
        }
        try {
            JsonNode settings = objectMapper.readTree(
                    project.getSettings() == null ? "{}" : project.getSettings());
            JsonNode reqDocs = settings.path("reqDocs");
            if (reqDocs.isArray()) {
                for (JsonNode doc : reqDocs) {
                    if ("prd".equals(doc.path("docType").asText(""))
                            && !doc.path("content").asText("").isBlank()) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check project PRD docs: {}", e.getMessage());
        }
        return false;
    }

    @Async("generationExecutor")
    public void runGenerate(String projectId, GenerateRequest req) {
        String taskId = null;
        try {
            // v5.13: 前置校验——生成必须基于 PRD
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
            boolean hasPrd = hasPrd(project);
            if (!hasPrd) {
                throw new IllegalStateException("请先添加 PRD 文档");
            }

            updateProjectStatus(projectId, "generating");
            taskQueueService.markRunning(TaskQueueService.GENERATION_QUEUE, projectId);
            // v6.5: 任务状态持久化（request_id 复用项目 ID，避免同项目重复活跃任务）
            taskId = agentTaskService.createTask(AgentTaskService.TYPE_GENERATION,
                    projectId, projectId, toJson(req));
            agentTaskService.start(taskId);
            agentTaskService.checkpoint(taskId, "generate", null);
            // v1.10: 改由 OrchestratorAgent 编排（PrdAgent + 代码侧 → TestGeneratorAgent）
            telemetryService.setTaskContext(taskId, agentTaskService.getAttempt(taskId));
            List<TestCase> testCases;
            try {
                testCases = orchestratorAgent.generate(projectId,
                        progress -> projectRepository.updateProgress(projectId, progress));
            } finally {
                telemetryService.clearTaskContext();
            }
            if (testCases.stream().anyMatch(tc -> "rule_based".equals(tc.getSource()))) {
                agentTaskService.markDegraded(taskId);
            }

            agentTaskService.checkpoint(taskId, "persist", null);
            projectRepository.updateProgress(projectId, "正在保存用例...");
            // v5.6: 事务化落库（先删旧用例+版本，再统一写入）
            testCasePersistenceService.replaceAll(projectId, testCases);

            // v5.4/v5.6: 重新生成后重建语义索引
            semanticService.clearCases(projectId);
            semanticService.indexCases(projectId, testCases);
            agentTaskService.checkpoint(taskId, "index", null);

            // v1.6: 完成时清除进度
            projectRepository.updateProgress(projectId, null);
            updateProjectStatus(projectId, "completed");
            agentTaskService.succeed(taskId);
            log.info("Test case generation completed for project {}: {} cases",
                    projectId, testCases.size());

        } catch (Exception e) {
            log.error("Test case generation failed for project {}", projectId, e);
            // v1.6: 失败时存储错误详情，前端可展示具体失败原因
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            projectRepository.updateStatusWithError(projectId, "failed", errorMsg);
            if (taskId != null) {
                agentTaskService.fail(taskId, "GENERATION_FAILED", errorMsg);
            }
        }
    }

    /**
     * v3.2: SSE 流式生成。emitter 由 Controller 传入，本方法在 generationExecutor 线程执行生成，
     * 通过 emitter 推送 progress/case/complete/error 事件，结束时落库。
     * v3.3: 新增 cancelled 标志——客户端断开/超时/error 或 cancel 端点触发取消，
     *       生成线程在检查点抛 GenerationCancelledException，catch 后跳过落库（保留旧用例）。
     */
    @Async("generationExecutor")
    public void runGenerateStream(String projectId, SseEmitter emitter) {
        String taskId = null;
        AtomicBoolean clientGone = new AtomicBoolean(false);
        // v5.8fix: 清除上次可能残留的取消标志，避免新任务被误取消
        runtimeStore.clearFlag("gen:cancel:" + projectId);
        // v5.2: 取消标志写入 RuntimeStore（Redis/内存），支持跨实例取消
        RuntimeFlag cancelled = runtimeStore.newFlag("gen:cancel:" + projectId);
        // v3.3: 客户端断开同时置 cancelled（不只跳过 send，还要停止生成 + 跳过落库）
        emitter.onCompletion(() -> {
            clientGone.set(true);
            cancelled.cancel();
            log.info("SSE client disconnected: {}", projectId);
        });
        emitter.onTimeout(() -> {
            clientGone.set(true);
            cancelled.cancel();
            log.warn("SSE timeout: {}", projectId);
        });
        emitter.onError(t -> {
            clientGone.set(true);
            cancelled.cancel();
            log.warn("SSE error: {}", projectId, t);
        });
        // v3.3: 注册取消标志（供 cancel 端点触发）
        cancellationFlags.put(projectId, cancelled);

        try {
            // v5.13: 前置校验——生成必须基于 PRD
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
            boolean hasPrd = hasPrd(project);
            if (!hasPrd) {
                throw new IllegalStateException("请先添加 PRD 文档");
            }

            updateProjectStatus(projectId, "generating");
            taskId = agentTaskService.createTask(AgentTaskService.TYPE_GENERATION,
                    projectId, projectId, "{}");
            agentTaskService.start(taskId);
            agentTaskService.checkpoint(taskId, "generate", null);

            // 进度回调 → 推送 progress 事件 + 同步写 project.progress（兼容轮询）
            TestGeneratorAgent.ProgressCallback progressCb = msg -> {
                sendSseEvent(emitter, clientGone, "progress", Map.of("message", msg));
                projectRepository.updateProgress(projectId, msg);
            };
            // 用例回调 → 推送 case 事件（每条用例解析完成即推送，不等去重/落库）
            TestGeneratorAgent.CaseCallback caseCb = tc ->
                    sendSseEvent(emitter, clientGone, "case", Map.of("testCase", TestCaseDTO.from(tc)));

            telemetryService.setTaskContext(taskId, agentTaskService.getAttempt(taskId));
            List<TestCase> testCases;
            try {
                testCases = orchestratorAgent.generateStreaming(projectId, progressCb, caseCb, cancelled);
            } finally {
                telemetryService.clearTaskContext();
            }
            if (testCases.stream().anyMatch(tc -> "rule_based".equals(tc.getSource()))) {
                agentTaskService.markDegraded(taskId);
            }

            agentTaskService.checkpoint(taskId, "persist", null);

            // v3.3: 落库前最终检查（LLM 返回后可能已取消）
            if (cancelled.isCancelled()) {
                throw new GenerationCancelledException("用户取消生成");
            }

            progressCb.update("正在保存用例...");
            // v5.6: 事务化落库（先删旧用例+版本，再统一写入）
            testCasePersistenceService.replaceAll(projectId, testCases);

            // v5.4/v5.6: 重新生成后重建语义索引
            semanticService.clearCases(projectId);
            semanticService.indexCases(projectId, testCases);
            agentTaskService.checkpoint(taskId, "index", null);
            agentTaskService.succeed(taskId);

            projectRepository.updateProgress(projectId, null);
            updateProjectStatus(projectId, "completed");

            sendSseEvent(emitter, clientGone, "complete", Map.of("total", testCases.size()));
            safeSseComplete(emitter, clientGone);
            log.info("Streaming generation completed for project {}: {} cases", projectId, testCases.size());
        } catch (GenerationCancelledException e) {
            // v3.3: 落库保护——跳过 deleteAll + save，保留旧用例
            log.info("Streaming generation cancelled for project {}", projectId);
            if (taskId != null) {
                agentTaskService.cancel(taskId);
            }
            projectRepository.updateProgress(projectId, null);
            restoreProjectStatus(projectId);
            sendSseEvent(emitter, clientGone, "cancelled",
                    Map.of("message", "生成已取消，旧用例已保留"));
            safeSseComplete(emitter, clientGone);
        } catch (Exception e) {
            log.error("Streaming generation failed for project {}", projectId, e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            projectRepository.updateStatusWithError(projectId, "failed", errorMsg);
            if (taskId != null) {
                agentTaskService.fail(taskId, "GENERATION_FAILED", errorMsg);
            }
            sendSseEvent(emitter, clientGone, "error", Map.of("message", errorMsg));
            safeSseCompleteWithError(emitter, clientGone, e);
        } finally {
            // v3.3: 清理取消标志，避免内存泄漏
            cancellationFlags.remove(projectId);
            cancelled.clear();
            taskQueueService.markDone(TaskQueueService.GENERATION_QUEUE, projectId);
        }
    }

    /**
     * v3.5: 追加生成（SSE）。与 runGenerateStream 结构对称，但落库阶段：
     * - 不删除现有用例
     * - type 非空时仅保留该类型用例
     * - 新用例与现有用例跨去重
     * - ID 从现有最大 +1 续号
     * complete 事件携带 total/appended/dropped/existingBefore 字段。
     */
    @Async("generationExecutor")
    public void runGenerateStreamAppend(String projectId, String type, SseEmitter emitter) {
        String taskId = null;
        AtomicBoolean clientGone = new AtomicBoolean(false);
        runtimeStore.clearFlag("gen:cancel:" + projectId);
        RuntimeFlag cancelled = runtimeStore.newFlag("gen:cancel:" + projectId);
        emitter.onCompletion(() -> {
            clientGone.set(true);
            cancelled.cancel();
            log.info("SSE client disconnected (append): {}", projectId);
        });
        emitter.onTimeout(() -> {
            clientGone.set(true);
            cancelled.cancel();
            log.warn("SSE timeout (append): {}", projectId);
        });
        emitter.onError(t -> {
            clientGone.set(true);
            cancelled.cancel();
            log.warn("SSE error (append): {}", projectId, t);
        });
        cancellationFlags.put(projectId, cancelled);

        try {
            // v5.13: 前置校验——生成必须基于 PRD
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
            boolean hasPrd = hasPrd(project);
            if (!hasPrd) {
                throw new IllegalStateException("请先添加 PRD 文档");
            }

            updateProjectStatus(projectId, "generating");
            taskQueueService.markRunning(TaskQueueService.GENERATION_QUEUE, projectId);
            taskId = agentTaskService.createTask(AgentTaskService.TYPE_APPEND_GENERATION,
                    projectId, projectId, toJson(Map.of("type", type == null ? "" : type)));
            agentTaskService.start(taskId);
            agentTaskService.checkpoint(taskId, "generate", null);

            TestGeneratorAgent.ProgressCallback progressCb = msg -> {
                sendSseEvent(emitter, clientGone, "progress", Map.of("message", msg));
                projectRepository.updateProgress(projectId, msg);
            };
            TestGeneratorAgent.CaseCallback caseCb = tc ->
                    sendSseEvent(emitter, clientGone, "case", Map.of("testCase", TestCaseDTO.from(tc)));

            telemetryService.setTaskContext(taskId, agentTaskService.getAttempt(taskId));
            List<TestCase> generated;
            try {
                generated = orchestratorAgent.generateStreaming(projectId, progressCb, caseCb, cancelled);
            } finally {
                telemetryService.clearTaskContext();
            }
            if (generated.stream().anyMatch(tc -> "rule_based".equals(tc.getSource()))) {
                agentTaskService.markDegraded(taskId);
            }

            agentTaskService.checkpoint(taskId, "persist", null);

            if (cancelled.isCancelled()) {
                throw new GenerationCancelledException("用户取消追加生成");
            }

            progressCb.update("正在追加保存用例...");

            // v3.5: 追加模式核心逻辑
            List<TestCase> existing = testCaseRepository.findByProjectId(projectId);

            // 1. 类型过滤（type 非空时仅保留该类型）
            List<TestCase> filtered = (type == null || type.isBlank())
                    ? generated
                    : generated.stream().filter(tc -> type.equals(tc.getType())).collect(Collectors.toList());

            // 2. 跨去重：新用例 vs 现有用例 + 新用例之间去重
            List<TestCase> toAppend = new ArrayList<>();
            for (TestCase newTc : filtered) {
                boolean isDup = false;
                for (TestCase exTc : existing) {
                    if (isDuplicate(newTc, exTc)) {
                        isDup = true;
                        break;
                    }
                }
                if (!isDup) {
                    for (TestCase alreadyAppend : toAppend) {
                        if (isDuplicate(newTc, alreadyAppend)) {
                            isDup = true;
                            break;
                        }
                    }
                }
                // v5.4: 语义级去重（Milvus 相似度阈值）
                if (!isDup && semanticService.isDuplicate(projectId, newTc)) {
                    isDup = true;
                }
                if (!isDup) toAppend.add(newTc);
            }

            // 3. 续号保存
            int startNo = nextTestCaseNumber(projectId);
            for (TestCase tc : toAppend) {
                tc.setId(String.format("TC-%03d", startNo++));
                tc.setProjectId(projectId);
                tc.setCreatedAt(LocalDateTime.now());
                testCaseRepository.save(tc);
            }
            // v5.12: 追加生成落库后补记 AI 评审历史
            testCaseReviewAgent.recordHistoryForCases(toAppend, "generation");

            // v5.4: 追加用例写入语义索引
            semanticService.indexCases(projectId, toAppend);
            agentTaskService.checkpoint(taskId, "index", null);
            agentTaskService.succeed(taskId);

            projectRepository.updateProgress(projectId, null);
            updateProjectStatus(projectId, "completed");

            int total = generated.size();
            int appended = toAppend.size();
            int dropped = total - appended;
            Map<String, Object> completeData = new LinkedHashMap<>();
            completeData.put("total", total);
            completeData.put("appended", appended);
            completeData.put("dropped", dropped);
            completeData.put("existingBefore", existing.size());
            sendSseEvent(emitter, clientGone, "complete", completeData);
            safeSseComplete(emitter, clientGone);
            log.info("Append generation completed for project {}: generated={}, appended={}, dropped={}",
                    projectId, total, appended, dropped);
        } catch (GenerationCancelledException e) {
            log.info("Append generation cancelled for project {}", projectId);
            if (taskId != null) {
                agentTaskService.cancel(taskId);
            }
            projectRepository.updateProgress(projectId, null);
            restoreProjectStatus(projectId);
            sendSseEvent(emitter, clientGone, "cancelled",
                    Map.of("message", "追加生成已取消，现有用例已保留"));
            safeSseComplete(emitter, clientGone);
        } catch (Exception e) {
            log.error("Append generation failed for project {}", projectId, e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            projectRepository.updateStatusWithError(projectId, "failed", errorMsg);
            if (taskId != null) {
                agentTaskService.fail(taskId, "APPEND_GENERATION_FAILED", errorMsg);
            }
            sendSseEvent(emitter, clientGone, "error", Map.of("message", errorMsg));
            safeSseCompleteWithError(emitter, clientGone, e);
        } finally {
            cancellationFlags.remove(projectId);
            cancelled.clear();
            taskQueueService.markDone(TaskQueueService.GENERATION_QUEUE, projectId);
        }
    }

    // v3.5: 跨去重判重逻辑（与 TestGeneratorAgent.isDuplicate 一致）
    // 决策：复制而非提升可见性，保持 TestGeneratorAgent 封装；职责分离（生成阶段 vs 落库阶段）。
    private boolean isDuplicate(TestCase a, TestCase b) {
        String titleA = a.getTitle() == null ? "" : a.getTitle().trim();
        String titleB = b.getTitle() == null ? "" : b.getTitle().trim();
        if (titleA.isEmpty() || titleB.isEmpty()) return false;
        if (titleA.equals(titleB)) return true;
        String modA = a.getModule() == null ? "" : a.getModule();
        String modB = b.getModule() == null ? "" : b.getModule();
        if (modA.equals(modB)) {
            if (titleA.contains(titleB) || titleB.contains(titleA)) return true;
            if (titleA.length() <= 20 && titleB.length() <= 20) {
                Set<Character> setA = new HashSet<>();
                for (char c : titleA.toCharArray()) setA.add(c);
                Set<Character> setB = new HashSet<>();
                for (char c : titleB.toCharArray()) setB.add(c);
                Set<Character> intersection = new HashSet<>(setA);
                intersection.retainAll(setB);
                int maxLen = Math.max(setA.size(), setB.size());
                if (maxLen > 0 && (double) intersection.size() / maxLen > 0.8) return true;
            }
        }
        // v5.14: 类型一致且步骤/接口指纹一致视为重复
        String typeA = a.getType() == null ? "" : a.getType();
        String typeB = b.getType() == null ? "" : b.getType();
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

    // v3.3: 取消生成（供 Controller 调用）。返回是否成功取消（有进行中的生成任务）。
    public boolean cancelGeneration(String projectId) {
        RuntimeFlag flag = cancellationFlags.get(projectId);
        if (flag != null) {
            flag.cancel();
            // v6.0: 立即中断 Spring AI 流式订阅，避免等待当前 LLM 调用跑完
            llmService.cancelStreaming();
            // 保留对旧 MCP 流式连接的中断（vision/其他流兼容）
            mcpClientManager.cancelStreaming("llm-stream");
            return true;
        }
        return false;
    }

    // v3.3: 取消后恢复项目状态（有旧用例→completed，无→created）
    private void restoreProjectStatus(String projectId) {
        List<TestCase> existing = testCaseRepository.findByProjectId(projectId);
        String status = (existing != null && !existing.isEmpty()) ? "completed" : "created";
        updateProjectStatus(projectId, status);
    }

    // v3.2: 安全推送——客户端已断开则跳过，IOException/IllegalStateException 静默吞掉并置位
    private void sendSseEvent(SseEmitter emitter, AtomicBoolean clientGone, String name, Object data) {
        if (clientGone.get()) return;
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IllegalStateException | IOException ex) {
            clientGone.set(true);
            log.debug("SSE send failed (client likely gone): {}", ex.getMessage());
        }
    }

    private void safeSseComplete(SseEmitter emitter, AtomicBoolean clientGone) {
        if (clientGone.get()) return;
        try { emitter.complete(); } catch (Exception ignored) {}
    }

    private void safeSseCompleteWithError(SseEmitter emitter, AtomicBoolean clientGone, Exception e) {
        if (clientGone.get()) return;
        try { emitter.completeWithError(e); } catch (Exception ignored) {}
    }

    public TestCaseListResponse listTestCases(String projectId, int page, int pageSize,
                                               String type, String module, String keyword,
                                               String reviewStatus, String executionStatus) {
        projectAccessService.assertViewAccess(projectId);
        // vP5: 分页下推数据库，避免大项目全量载入内存；筛选使用 Specification 落到 SQL
        Specification<TestCase> spec = buildTestCaseSpec(
                projectId, type, module, keyword, reviewStatus, executionStatus);
        Page<TestCase> pageResult = testCaseRepository.findAll(spec,
                PageRequest.of(Math.max(0, page - 1), Math.max(1, pageSize)));
        List<TestCaseDTO> items = pageResult.getContent().stream()
                .map(TestCaseDTO::from)
                .collect(Collectors.toList());

        // 覆盖率是聚合视图，仍基于完整匹配集计算
        List<TestCase> all = testCaseRepository.findAll(spec);
        Map<String, Object> coverage = calculateCoverage(projectId, all);

        return TestCaseListResponse.builder()
                .total((int) pageResult.getTotalElements())
                .page(page)
                .pageSize(pageSize)
                .testCases(items)
                .coverage(coverage)
                .build();
    }

    private Specification<TestCase> buildTestCaseSpec(String projectId, String type, String module,
                                                      String keyword, String reviewStatus,
                                                      String executionStatus) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("projectId"), projectId));
            if (type != null && !type.isBlank()) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (module != null && !module.isBlank()) {
                predicates.add(cb.equal(root.get("module"), module));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("module")), like)));
            }
            if (executionStatus != null && !executionStatus.isBlank()) {
                predicates.add(cb.equal(
                        cb.coalesce(root.get("executionStatus"), "not_executed"), executionStatus));
            }
            if (reviewStatus != null && !reviewStatus.isBlank()) {
                predicates.add(cb.equal(
                        cb.coalesce(root.get("reviewStatus"), "draft"), reviewStatus));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public TestCaseDTO getTestCase(String projectId, String testcaseId) {
        projectAccessService.assertViewAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
        return TestCaseDTO.from(tc);
    }

    // v5.12: 单条 AI 评审异步化——先标记 reviewing，任务完成后由 TestCaseReviewRunner 执行
    @Transactional
    public Map<String, Object> markReviewing(String projectId, String testcaseId) {
        projectAccessService.assertOperateAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        Map<String, Object> review = readAiReview(hints);
        if (review != null && "reviewing".equals(String.valueOf(review.get("status")))) {
            throw BusinessException.invalidState("该用例正在评审中，请勿重复提交");
        }
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("status", "reviewing");
        next.put("issues", List.of());
        next.put("confidence", 0.0);
        next.put("suggestedChanges", emptyAiReviewSuggestions());
        hints.put("aiReview", next);
        tc.setExecutionHints(toJson(hints));
        testCaseRepository.save(tc);
        return Map.of("status", "reviewing");
    }

    @Transactional
    public TestCaseDTO reviewTestCaseInternal(String projectId, String testcaseId) {
        TelemetryService.TelemetryContext telemetry = telemetryService.start("ai_review", projectId);
        boolean ok = false;
        try {
            TestCase tc = findTestCase(projectId, testcaseId);
            List<TestCase> reviewed = testCaseReviewAgent.review(
                    List.of(tc), buildCoverageForReview(projectId), "rerun");
            if (reviewed.isEmpty()) {
                throw new BusinessException(40001, "评审后没有可用用例", HttpStatus.BAD_REQUEST);
            }
            TestCase saved = testCaseRepository.save(reviewed.get(0));
            ensureReviewCompleted(saved);
            testCaseReviewAgent.recordHistoryForCases(List.of(saved), "rerun");
            ok = true;
            return TestCaseDTO.from(saved);
        } finally {
            telemetryService.finish(ok);
        }
    }

    @Transactional
    public void markReviewFailed(String projectId, String testcaseId, String error) {
        TestCase tc = findTestCase(projectId, testcaseId);
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", "failed");
        review.put("issues", List.of(error == null || error.isBlank() ? "AI 评审失败" : error));
        review.put("confidence", 0.0);
        review.put("suggestedChanges", emptyAiReviewSuggestions());
        hints.put("aiReview", review);
        tc.setExecutionHints(toJson(hints));
        testCaseRepository.save(tc);
        aiReviewHistoryRecorder.record(tc, review, readCoverageRefs(hints), "rerun");
    }

    private void ensureReviewCompleted(TestCase tc) {
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        Map<String, Object> review = readAiReview(hints);
        if (review == null || "reviewing".equals(String.valueOf(review.get("status")))) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("status", "failed");
            fallback.put("issues", List.of("LLM 评审失败，已保留规则兜底结果"));
            fallback.put("confidence", 0.0);
            fallback.put("suggestedChanges", emptyAiReviewSuggestions());
            hints.put("aiReview", fallback);
            tc.setExecutionHints(toJson(hints));
            testCaseRepository.save(tc);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readAiReview(Map<String, Object> hints) {
        Object review = hints.get("aiReview");
        if (review instanceof Map) {
            return (Map<String, Object>) review;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCoverageRefs(Map<String, Object> hints) {
        Object refs = hints.get("coverageRefs");
        if (refs instanceof Map) {
            return (Map<String, Object>) refs;
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> emptyAiReviewSuggestions() {
        Map<String, Object> suggestions = new LinkedHashMap<>();
        suggestions.put("title", null);
        suggestions.put("module", null);
        suggestions.put("type", null);
        suggestions.put("priority", null);
        suggestions.put("coverageRefs", null);
        return suggestions;
    }

    private Map<String, Object> buildCoverageForReview(String projectId) {
        List<Map<String, Object>> transitions = new ArrayList<>();
        for (StateMachine sm : stateMachineRepository.findByProjectId(projectId)) {
            for (Map<String, Object> t : JsonHelper.parseListMap(sm.getTransitions())) {
                Map<String, Object> item = new LinkedHashMap<>();
                String from = String.valueOf(t.getOrDefault("from", ""));
                String to = String.valueOf(t.getOrDefault("to", ""));
                item.put("id", from + "->" + to);
                item.put("from", from);
                item.put("to", to);
                item.put("trigger", t.get("trigger"));
                item.put("condition", t.get("condition"));
                item.put("stateMachine", sm.getName());
                transitions.add(item);
            }
        }

        List<Map<String, Object>> endpoints = new ArrayList<>();
        List<Map<String, Object>> rules = new ArrayList<>();
        Optional<CodeAnalysis> analysisOpt = codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId);
        if (analysisOpt.isPresent()) {
            String json = analysisOpt.get().getBackendResult();
            if (json != null && !json.isBlank() && !json.equals("{}")) {
                try {
                    BackendResult backendResult = objectMapper.readValue(json, BackendResult.class);
                    if (backendResult.getEndpoints() != null) {
                        for (EndpointInfo ep : backendResult.getEndpoints()) {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", (ep.getMethod() == null ? "" : ep.getMethod().toUpperCase()) + " " + ep.getPath());
                            item.putAll(ep.toContextMap());
                            endpoints.add(item);
                        }
                    }
                    if (backendResult.getBusinessRules() != null) {
                        int i = 1;
                        for (BusinessRule br : backendResult.getBusinessRules()) {
                            Map<String, Object> item = new LinkedHashMap<>(br.toContextMap());
                            item.put("id", "rule-" + i++);
                            rules.add(item);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse backend result for review: {}", e.getMessage());
                }
            }
        }

        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("requirements", List.of());
        checklist.put("transitions", transitions);
        checklist.put("endpoints", endpoints);
        checklist.put("businessRules", rules);
        Map<String, Object> gaps = new LinkedHashMap<>();
        gaps.put("requirementIds", List.of());
        gaps.put("transitionIds", transitions.stream().map(t -> t.get("id")).toList());
        gaps.put("endpointIds", endpoints.stream().map(e -> e.get("id")).toList());
        gaps.put("ruleIds", rules.stream().map(r -> r.get("id")).toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checklist", checklist);
        result.put("gaps", gaps);
        return result;
    }

    @Transactional
    public void deleteTestCase(String projectId, String testcaseId) {
        projectAccessService.assertOperateAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
        testCaseRepository.delete(tc);
        // v5.6/v5.12: 同步删除版本快照、AI 评审历史与语义向量
        testCaseVersionRepository.deleteByTestCaseId(testcaseId);
        aiReviewRepository.deleteByTestCaseId(testcaseId);
        semanticService.removeCases(projectId, List.of(testcaseId));
    }

    @Transactional
    public int batchDeleteTestCases(String projectId, java.util.List<String> ids) {
        projectAccessService.assertOperateAccess(projectId);
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);
        int count = 0;
        List<String> deletedIds = new ArrayList<>();
        for (TestCase tc : all) {
            if (ids.contains(tc.getId())) {
                testCaseRepository.delete(tc);
                testCaseVersionRepository.deleteByTestCaseId(tc.getId());
                aiReviewRepository.deleteByTestCaseId(tc.getId());
                deletedIds.add(tc.getId());
                count++;
            }
        }
        // v5.6: 批量删除同步清理语义向量
        semanticService.removeCases(projectId, deletedIds);
        return count;
    }

    // ==================== v1.7: 导入导出与跨项目复制 ====================

    public ResponseEntity<Resource> exportTestCases(String projectId, String format, List<String> ids) {
        projectAccessService.assertViewAccess(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);
        List<TestCase> target = (ids == null || ids.isEmpty())
                ? all
                : all.stream().filter(tc -> ids.contains(tc.getId())).collect(Collectors.toList());

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String baseName = project.getName() + "_testcases_" + timestamp;

        if ("csv".equalsIgnoreCase(format)) {
            byte[] csv = CsvExporter.toCsv(target);
            InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(csv));
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + ".csv\"")
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .contentLength(csv.length)
                    .body(resource);
        }

        // 默认 JSON 导出
        List<TestCaseDTO> dtos = target.stream().map(TestCaseDTO::from).collect(Collectors.toList());
        byte[] json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(dtos);
        } catch (Exception e) {
            log.error("Failed to export JSON for project {}", projectId, e);
            throw new BusinessException(50004, "导出 JSON 失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        InputStreamResource resource = new InputStreamResource(new ByteArrayInputStream(json));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + baseName + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(json.length)
                .body(resource);
    }

    @Transactional
    public Map<String, Object> importTestCases(String projectId, MultipartFile file) {
        projectAccessService.assertOperateAccess(projectId);
        projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        uploadGuard.assertSize(file, "JSON 导入文件");

        List<TestCase> parsed;
        try {
            JsonNode root = objectMapper.readTree(file.getBytes());
            if (!root.isArray()) {
                throw BusinessException.invalidParam("JSON 根节点必须是数组");
            }
            parsed = new ArrayList<>();
            for (JsonNode node : root) {
                parsed.add(parseTestCaseFromJson(node));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse import JSON for project {}", projectId, e);
            throw BusinessException.invalidParam("JSON 解析失败: " + e.getMessage());
        }

        int startNo = nextTestCaseNumber(projectId);
        int imported = 0;
        List<TestCase> importedCases = new ArrayList<>();
        for (TestCase tc : parsed) {
            // 跳过无标题的无效用例
            if (tc.getTitle() == null || tc.getTitle().isBlank()) {
                continue;
            }
            tc.setId(String.format("TC-%03d", startNo++));
            tc.setProjectId(projectId);
            tc.setSource("imported");
            tc.setCreatedAt(LocalDateTime.now());
            testCaseRepository.save(tc);
            importedCases.add(tc);
            imported++;
        }
        // v5.6: JSON 导入同步语义索引
        semanticService.indexCases(projectId, importedCases);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", parsed.size() - imported);
        return result;
    }

    /**
     * v3.9: 从 XMind 文件导入用例（追加模式，重新编号）。
     */
    @Transactional
    public Map<String, Object> importFromXmind(String projectId, MultipartFile file) {
        projectAccessService.assertOperateAccess(projectId);
        projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        uploadGuard.assertSize(file, "XMind 导入文件");

        List<TestCase> parsed;
        try {
            parsed = xmindService.parseXmind(file.getInputStream());
        } catch (Exception e) {
            log.warn("XMind 解析失败 project={}", projectId, e);
            throw BusinessException.invalidParam("XMind 解析失败: " + e.getMessage());
        }

        int startNo = nextTestCaseNumber(projectId);
        int imported = 0;
        List<TestCase> importedCases = new ArrayList<>();
        // v3.16: 跳过明细（标题为空等原因）
        List<Map<String, String>> skippedDetails = new ArrayList<>();
        for (TestCase tc : parsed) {
            if (tc.getTitle() == null || tc.getTitle().isBlank()) {
                skippedDetails.add(Map.of(
                        "title", tc.getTitle() == null ? "(无标题)" : tc.getTitle(),
                        "reason", "标题为空"));
                continue;
            }
            tc.setId(String.format("TC-%03d", startNo++));
            tc.setProjectId(projectId);
            tc.setSource("xmind_import");
            tc.setCreatedAt(LocalDateTime.now());
            testCaseRepository.save(tc);
            importedCases.add(tc);
            imported++;
        }
        // v5.6: XMind 导入同步语义索引
        semanticService.indexCases(projectId, importedCases);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", parsed.size() - imported);
        result.put("skippedDetails", skippedDetails);
        return result;
    }

    @Transactional
    public Map<String, Object> copyToProject(String sourceProjectId, List<String> ids, String targetProjectId) {
        projectAccessService.assertOperateAccess(sourceProjectId);
        projectAccessService.assertOperateAccess(targetProjectId);
        if (targetProjectId == null || targetProjectId.isBlank()) {
            throw BusinessException.invalidParam("目标项目 ID 不能为空");
        }
        if (targetProjectId.equals(sourceProjectId)) {
            throw BusinessException.invalidParam("目标项目不能与源项目相同");
        }
        projectRepository.findById(targetProjectId)
                .orElseThrow(() -> BusinessException.notFound("目标项目不存在: " + targetProjectId));

        List<TestCase> all = testCaseRepository.findByProjectId(sourceProjectId);
        List<TestCase> selected = all.stream()
                .filter(tc -> ids != null && ids.contains(tc.getId()))
                .collect(Collectors.toList());

        int startNo = nextTestCaseNumber(targetProjectId);
        int copied = 0;
        List<TestCase> copiedCases = new ArrayList<>();
        for (TestCase tc : selected) {
            TestCase copy = cloneTestCase(tc);
            copy.setId(String.format("TC-%03d", startNo++));
            copy.setProjectId(targetProjectId);
            copy.setSource("copied");
            copy.setCreatedAt(LocalDateTime.now());
            testCaseRepository.save(copy);
            copiedCases.add(copy);
            copied++;
        }
        // v5.6: 复制到目标项目后同步语义索引
        semanticService.indexCases(targetProjectId, copiedCases);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("copied", copied);
        return result;
    }

    private int nextTestCaseNumber(String projectId) {
        List<TestCase> existing = testCaseRepository.findByProjectId(projectId);
        return existing.stream()
                .map(TestCase::getId)
                .filter(id -> id != null && id.startsWith("TC-"))
                .mapToInt(id -> {
                    try {
                        return Integer.parseInt(id.substring(3));
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .max().orElse(0) + 1;
    }

    private TestCase parseTestCaseFromJson(JsonNode node) {
        TestCase tc = new TestCase();
        tc.setTitle(node.path("title").asText(""));
        tc.setModule(node.path("module").asText("未分类"));
        tc.setType(node.path("type").asText("positive"));
        tc.setPriority(node.path("priority").asText("P1"));
        tc.setPreconditions(jsonField(node, "preconditions", "[]"));
        tc.setSteps(jsonField(node, "steps", "[]"));
        tc.setExpectedResults(jsonField(node, "expectedResults", "[]"));
        tc.setStructuredSteps(jsonField(node, "structuredSteps", "[]"));
        tc.setApiEndpoints(jsonField(node, "apiEndpoints", "[]"));
        tc.setTestData(jsonField(node, "testData", "{}"));
        tc.setExecutionHints(jsonField(node, "executionHints", "{}"));
        tc.setStateMachineRef(jsonField(node, "stateMachineRef", "{}"));
        tc.setConfidence(0.8);
        return tc;
    }

    private TestCase cloneTestCase(TestCase src) {
        TestCase tc = new TestCase();
        tc.setTitle(src.getTitle());
        tc.setModule(src.getModule());
        tc.setType(src.getType());
        tc.setPriority(src.getPriority());
        tc.setPreconditions(src.getPreconditions());
        tc.setSteps(src.getSteps());
        tc.setExpectedResults(src.getExpectedResults());
        tc.setStructuredSteps(src.getStructuredSteps());
        tc.setApiEndpoints(src.getApiEndpoints());
        tc.setTestData(src.getTestData());
        tc.setExecutionHints(src.getExecutionHints());
        tc.setStateMachineRef(src.getStateMachineRef());
        tc.setConfidence(src.getConfidence());
        return tc;
    }

    // 将 JSON 节点转为字符串存储；缺失/null 时返回默认值
    private String jsonField(JsonNode node, String field, String defaultValue) {
        JsonNode child = node.path(field);
        if (child == null || child.isMissingNode() || child.isNull()) {
            return defaultValue;
        }
        String s = child.toString();
        return (s == null || s.isEmpty() || "null".equals(s)) ? defaultValue : s;
    }

    // ==================== v1.8: 评审状态流转 ====================

    private static final Set<String> VALID_REVIEW_STATUSES =
            Set.of("draft", "reviewed", "approved", "rejected");

    @Transactional
    public Map<String, Object> batchUpdateReviewStatus(String projectId, List<String> ids,
                                                         String status, String reviewer) {
        projectAccessService.assertOperateAccess(projectId);
        if (status == null || !VALID_REVIEW_STATUSES.contains(status)) {
            throw BusinessException.invalidParam("非法的评审状态: " + status);
        }
        if (ids == null || ids.isEmpty()) {
            throw BusinessException.invalidParam("ids 不能为空");
        }
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);
        int updated = 0;
        for (TestCase tc : all) {
            if (ids.contains(tc.getId())) {
                tc.setReviewStatus(status);
                testCaseRepository.save(tc);
                updated++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", updated);
        result.put("status", status);
        // v4.1: 评审人取登录态，不信任前端传参
        String operator = SecurityUtils.currentUsername();
        result.put("reviewer", operator == null || operator.isBlank() ? "system" : operator);
        return result;
    }

    // v3.6: 手动创建测试用例
    @Transactional
    public TestCaseDTO createTestCase(String projectId, CreateTestCaseRequest req) {
        projectAccessService.assertOperateAccess(projectId);
        TestCase tc = new TestCase();
        tc.setProjectId(projectId);
        tc.setTitle(req.getTitle() != null ? req.getTitle() : "未命名测试用例");
        tc.setModule(req.getModule() != null ? req.getModule() : "未分类");
        tc.setType(req.getType() != null ? req.getType() : "positive");
        tc.setPriority(req.getPriority() != null ? req.getPriority() : "P2");
        tc.setPreconditions(toJson(req.getPreconditions() != null ? req.getPreconditions() : new ArrayList<>()));
        tc.setSteps(toJson(req.getSteps() != null ? req.getSteps() : new ArrayList<>()));
        tc.setExpectedResults(toJson(req.getExpectedResults() != null ? req.getExpectedResults() : new ArrayList<>()));
        tc.setStructuredSteps(toJson(req.getStructuredSteps() != null ? req.getStructuredSteps() : new ArrayList<>()));
        tc.setApiEndpoints(toJson(req.getApiEndpoints() != null ? req.getApiEndpoints() : new ArrayList<>()));
        tc.setTestData(toJson(req.getTestData() != null ? req.getTestData() : new LinkedHashMap<>()));
        tc.setExecutionHints(toJson(req.getExecutionHints() != null ? req.getExecutionHints() : new LinkedHashMap<>()));
        tc.setSource("manual");
        tc.setConfidence(1.0);
        tc.setExecutionStatus("pending");
        tc.setReviewStatus("draft");
        tc.setCreatedAt(LocalDateTime.now());

        // 分配编号: TC-{当前最大编号+1}
        List<TestCase> existing = testCaseRepository.findByProjectId(projectId);
        int nextNum = existing.size() + 1;
        tc.setId(String.format("TC-%03d", nextNum));

        testCaseRepository.save(tc);
        // v5.6: 手工创建用例同步语义索引
        semanticService.indexCase(projectId, tc);
        log.info("手动创建用例: projectId={}, id={}", projectId, tc.getId());
        return TestCaseDTO.from(tc);
    }

    // v4.3: 手动标记执行状态（未执行/通过/阻塞/失败）
    private static final java.util.Set<String> MANUAL_EXECUTION_STATUSES =
            java.util.Set.of("not_executed", "passed", "failed", "blocked");

    @Transactional
    public TestCaseDTO updateExecutionStatus(String projectId, String testcaseId, String status) {
        projectAccessService.assertOperateAccess(projectId);
        if (status == null || !MANUAL_EXECUTION_STATUSES.contains(status)) {
            throw BusinessException.invalidParam("非法的执行状态: " + status);
        }
        TestCase tc = findTestCase(projectId, testcaseId);
        tc.setExecutionStatus(status);
        testCaseRepository.save(tc);
        return TestCaseDTO.from(tc);
    }

    @Transactional
    public TestCaseDTO updateTestCase(String projectId, String testcaseId, UpdateTestCaseRequest req) {
        projectAccessService.assertOperateAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
        Map<String, Object> oldHints = JsonHelper.parseMap(tc.getExecutionHints());
        // v1.9: 保存编辑前快照
        createVersion(projectId, testcaseId, tc, "edit");

        if (req.getTitle() != null) {
            tc.setTitle(req.getTitle());
        }
        if (req.getModule() != null) {
            tc.setModule(req.getModule());
        }
        if (req.getType() != null) {
            tc.setType(req.getType());
        }
        if (req.getPriority() != null) {
            tc.setPriority(req.getPriority());
        }
        if (req.getPreconditions() != null) {
            tc.setPreconditions(toJson(req.getPreconditions()));
        }
        if (req.getSteps() != null) {
            tc.setSteps(toJson(req.getSteps()));
        }
        if (req.getExpectedResults() != null) {
            tc.setExpectedResults(toJson(req.getExpectedResults()));
        }
        if (req.getStructuredSteps() != null) {
            tc.setStructuredSteps(toJson(req.getStructuredSteps()));
        }
        if (req.getApiEndpoints() != null) {
            tc.setApiEndpoints(toJson(req.getApiEndpoints()));
        }
        if (req.getTestData() != null) {
            tc.setTestData(toJson(req.getTestData()));
        }
        if (req.getExecutionHints() != null) {
            syncAiReviewStatusHistory(testcaseId, oldHints, req.getExecutionHints());
            tc.setExecutionHints(toJson(req.getExecutionHints()));
        }
        if (req.getReviewStatus() != null) {
            tc.setReviewStatus(req.getReviewStatus());
        }
        if (req.getExecutionStatus() != null) {
            tc.setExecutionStatus(req.getExecutionStatus());
        }

        testCaseRepository.save(tc);
        // v5.6: 编辑用例后重建向量（先删旧向量再写入）；异步执行避免排在 MCP 连接后阻塞保存请求
        semanticIndexingAsyncService.reindexCase(projectId, tc);
        return TestCaseDTO.from(tc);
    }

    private void syncAiReviewStatusHistory(String testcaseId,
                                           Map<String, Object> oldHints,
                                           Map<String, Object> newHints) {
        Map<String, Object> oldReview = readAiReview(oldHints);
        Map<String, Object> newReview = readAiReview(newHints);
        if (newReview == null) {
            return;
        }
        String oldStatus = oldReview == null ? null : String.valueOf(oldReview.get("status"));
        String newStatus = String.valueOf(newReview.get("status"));
        if (!Objects.equals(oldStatus, newStatus)
                && ("applied".equals(newStatus) || "ignored".equals(newStatus))) {
            aiReviewRepository.findFirstByTestCaseIdOrderByCreatedAtDesc(testcaseId)
                    .ifPresent(row -> {
                        row.setStatus(newStatus);
                        aiReviewRepository.save(row);
                    });
        }
    }

    // ==================== v1.9: 用例版本管理 ====================

    public List<TestCaseVersionDTO> listVersions(String projectId, String testcaseId) {
        projectAccessService.assertViewAccess(projectId);
        findTestCase(projectId, testcaseId);
        return testCaseVersionRepository
                .findByTestCaseIdOrderByVersionNoDesc(testcaseId)
                .stream()
                .map(TestCaseVersionDTO::listFrom)
                .collect(Collectors.toList());
    }

    public TestCaseVersionDTO getVersion(String projectId, String testcaseId, String versionId) {
        projectAccessService.assertViewAccess(projectId);
        findTestCase(projectId, testcaseId);
        TestCaseVersion v = testCaseVersionRepository
                .findByIdAndTestCaseId(versionId, testcaseId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + versionId));
        return TestCaseVersionDTO.detailFrom(v);
    }

    @Transactional
    public TestCaseDTO rollbackToVersion(String projectId, String testcaseId, String versionId) {
        projectAccessService.assertOperateAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
        TestCaseVersion v = testCaseVersionRepository
                .findByIdAndTestCaseId(versionId, testcaseId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + versionId));

        // v1.9: 回滚前先存当前内容为版本，使回滚可撤销
        createVersion(projectId, testcaseId, tc, "rollback");

        applySnapshotToTestCase(tc, JsonHelper.parseMap(v.getSnapshot()));
        testCaseRepository.save(tc);
        return TestCaseDTO.from(tc);
    }

    private void createVersion(String projectId, String testcaseId, TestCase tc, String action) {
        TestCaseVersion v = new TestCaseVersion();
        v.setId(UUID.randomUUID().toString().substring(0, 12));
        v.setTestCaseId(testcaseId);
        v.setProjectId(projectId);
        v.setVersionNo((int) testCaseVersionRepository.countByTestCaseId(testcaseId) + 1);
        v.setSnapshot(toSnapshotJson(tc));
        v.setAction(action);
        v.setCreatedAt(LocalDateTime.now());
        testCaseVersionRepository.save(v);
    }

    private String toSnapshotJson(TestCase tc) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("title", tc.getTitle());
        snap.put("module", tc.getModule());
        snap.put("type", tc.getType());
        snap.put("priority", tc.getPriority());
        snap.put("preconditions", JsonHelper.parseListString(tc.getPreconditions()));
        snap.put("steps", JsonHelper.parseListString(tc.getSteps()));
        snap.put("expectedResults", JsonHelper.parseListString(tc.getExpectedResults()));
        snap.put("structuredSteps", JsonHelper.parseListMap(tc.getStructuredSteps()));
        snap.put("apiEndpoints", JsonHelper.parseListMap(tc.getApiEndpoints()));
        snap.put("testData", JsonHelper.parseMap(tc.getTestData()));
        snap.put("executionHints", JsonHelper.parseMap(tc.getExecutionHints()));
        snap.put("stateMachineRef", JsonHelper.parseMap(tc.getStateMachineRef()));
        snap.put("executionStatus", tc.getExecutionStatus());
        snap.put("reviewStatus", tc.getReviewStatus());
        snap.put("qualityScore", tc.getQualityScore());
        return toJson(snap);
    }

    private void applySnapshotToTestCase(TestCase tc, Map<String, Object> snap) {
        if (snap.containsKey("title")) tc.setTitle(asString(snap.get("title")));
        if (snap.containsKey("module")) tc.setModule(asString(snap.get("module")));
        if (snap.containsKey("type")) tc.setType(asString(snap.get("type")));
        if (snap.containsKey("priority")) tc.setPriority(asString(snap.get("priority")));
        if (snap.containsKey("preconditions")) tc.setPreconditions(toJson(snap.get("preconditions")));
        if (snap.containsKey("steps")) tc.setSteps(toJson(snap.get("steps")));
        if (snap.containsKey("expectedResults")) tc.setExpectedResults(toJson(snap.get("expectedResults")));
        if (snap.containsKey("structuredSteps")) tc.setStructuredSteps(toJson(snap.get("structuredSteps")));
        if (snap.containsKey("apiEndpoints")) tc.setApiEndpoints(toJson(snap.get("apiEndpoints")));
        if (snap.containsKey("testData")) tc.setTestData(toJson(snap.get("testData")));
        if (snap.containsKey("executionHints")) tc.setExecutionHints(toJson(snap.get("executionHints")));
        if (snap.containsKey("stateMachineRef")) tc.setStateMachineRef(toJson(snap.get("stateMachineRef")));
        if (snap.containsKey("executionStatus")) tc.setExecutionStatus(asString(snap.get("executionStatus")));
        if (snap.containsKey("reviewStatus")) tc.setReviewStatus(asString(snap.get("reviewStatus")));
        if (snap.containsKey("qualityScore") && snap.get("qualityScore") != null) {
            tc.setQualityScore(((Number) snap.get("qualityScore")).intValue());
        }
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private TestCase findTestCase(String projectId, String testcaseId) {
        List<TestCase> testCases = testCaseRepository.findByProjectId(projectId);
        return testCases.stream()
                .filter(t -> testcaseId.equals(t.getId()))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("测试用例不存在: " + testcaseId));
    }

    private void updateProjectStatus(String projectId, String status) {
        projectRepository.findById(projectId).ifPresent(project -> {
            project.setStatus(status);
            // v1.6: 进入 generating/completed 时清除上次失败的错误详情，避免残留误导用户
            if ("generating".equals(status) || "completed".equals(status)) {
                project.setErrorMessage(null);
            }
            projectRepository.save(project);
        });
    }

    private Map<String, Object> calculateCoverage(String projectId, List<TestCase> allTestCases) {
        Map<String, Object> coverage = new LinkedHashMap<>();

        // 状态转换覆盖率
        List<StateMachine> stateMachines = stateMachineRepository.findByProjectId(projectId);
        Set<String> totalTransitions = new HashSet<>();
        for (StateMachine sm : stateMachines) {
            List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
            for (Map<String, Object> t : transitions) {
                String from = String.valueOf(t.getOrDefault("from", ""));
                String to = String.valueOf(t.getOrDefault("to", ""));
                totalTransitions.add(from + "->" + to);
            }
        }

        Set<String> coveredTransitions = new HashSet<>();
        for (TestCase tc : allTestCases) {
            // v5.12: 计划覆盖（coverageRefs）先计入，未执行也视为已规划覆盖
            coveredTransitions.addAll(parseCoverageRefTransitions(tc));
            if (!isExecuted(tc)) {
                continue;
            }
            Map<String, Object> smRef = JsonHelper.parseMap(tc.getStateMachineRef());
            Object transitionsObj = smRef.get("transitions");
            if (transitionsObj instanceof List) {
                for (Object item : (List<?>) transitionsObj) {
                    if (item instanceof Map) {
                        Map<?, ?> t = (Map<?, ?>) item;
                        String from = String.valueOf(t.get("from"));
                        String to = String.valueOf(t.get("to"));
                        coveredTransitions.add(from + "->" + to);
                    }
                }
            }
        }

        int stateTotal = totalTransitions.size();
        int stateCovered = 0;
        for (String ct : coveredTransitions) {
            if (totalTransitions.contains(ct)) {
                stateCovered++;
            }
        }
        Map<String, Object> stateCov = new LinkedHashMap<>();
        stateCov.put("covered", stateCovered);
        stateCov.put("total", stateTotal);
        stateCov.put("rate", stateTotal == 0 ? 0.0 : (double) stateCovered / stateTotal);
        coverage.put("stateTransition", stateCov);

        // 接口覆盖率
        Set<String> totalEndpoints = new HashSet<>();
        Optional<CodeAnalysis> analysisOpt = codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId);
        if (analysisOpt.isPresent()) {
            String backendResultJson = analysisOpt.get().getBackendResult();
            if (backendResultJson != null && !backendResultJson.isBlank()
                    && !backendResultJson.equals("{}")) {
                try {
                    BackendResult backendResult = objectMapper.readValue(backendResultJson, BackendResult.class);
                    if (backendResult.getEndpoints() != null) {
                        for (EndpointInfo ep : backendResult.getEndpoints()) {
                            totalEndpoints.add(normalizeEndpointIdForCoverage(ep.getMethod() + " " + ep.getPath()));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse backend result for coverage", e);
                }
            }
        }

        Set<String> coveredEndpoints = new HashSet<>();
        for (TestCase tc : allTestCases) {
            // v5.12: 计划接口覆盖先计入，与状态机矩阵口径一致
            coveredEndpoints.addAll(parseCoverageRefEndpoints(tc));
            if (!isExecuted(tc)) {
                continue;
            }
            List<Map<String, Object>> eps = JsonHelper.parseListMap(tc.getApiEndpoints());
            for (Map<String, Object> ep : eps) {
                String method = String.valueOf(ep.getOrDefault("method", ""));
                String path = String.valueOf(ep.getOrDefault("path", ""));
                coveredEndpoints.add(normalizeEndpointIdForCoverage(method + " " + path));
            }
        }

        int apiTotal = totalEndpoints.size();
        int apiCovered = 0;
        for (String ce : coveredEndpoints) {
            if (totalEndpoints.contains(ce)) {
                apiCovered++;
            }
        }
        Map<String, Object> apiCov = new LinkedHashMap<>();
        apiCov.put("covered", apiCovered);
        apiCov.put("total", apiTotal);
        apiCov.put("rate", apiTotal == 0 ? 0.0 : (double) apiCovered / apiTotal);
        coverage.put("apiEndpoint", apiCov);

        // 类型分布
        Map<String, Integer> typeDist = new LinkedHashMap<>();
        typeDist.put("positive", 0);
        typeDist.put("negative", 0);
        typeDist.put("boundary", 0);
        typeDist.put("data", 0);
        for (TestCase tc : allTestCases) {
            String type = tc.getType();
            if (type != null && typeDist.containsKey(type)) {
                typeDist.put(type, typeDist.get(type) + 1);
            }
        }
        coverage.put("typeDistribution", typeDist);

        return coverage;
    }

    private Set<String> parseCoverageRefTransitions(TestCase tc) {
        Set<String> result = new HashSet<>();
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        Object refs = hints.get("coverageRefs");
        if (refs instanceof Map) {
            Object ids = ((Map<?, ?>) refs).get("transitionIds");
            if (ids instanceof List) {
                for (Object id : (List<?>) ids) {
                    if (id != null) {
                        result.add(String.valueOf(id));
                    }
                }
            }
        }
        return result;
    }

    private Set<String> parseCoverageRefEndpoints(TestCase tc) {
        Set<String> result = new HashSet<>();
        Map<String, Object> hints = JsonHelper.parseMap(tc.getExecutionHints());
        Object refs = hints.get("coverageRefs");
        if (refs instanceof Map) {
            Object ids = ((Map<?, ?>) refs).get("endpointIds");
            if (ids instanceof List) {
                for (Object id : (List<?>) ids) {
                    if (id != null) {
                        result.add(normalizeEndpointIdForCoverage(String.valueOf(id)));
                    }
                }
            }
        }
        return result;
    }

    private String normalizeEndpointIdForCoverage(String id) {
        if (id == null) {
            return "";
        }
        int space = id.indexOf(' ');
        if (space > 0) {
            return id.substring(0, space).toUpperCase() + id.substring(space);
        }
        return id;
    }

    private boolean isExecuted(TestCase tc) {
        String status = tc.getExecutionStatus();
        return "passed".equals(status) || "failed".equals(status);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
