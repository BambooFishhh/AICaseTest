package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.agent.OrchestratorAgent;
import com.testagent.agent.TestGeneratorAgent;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.common.BusinessException;
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
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.TestCaseVersionRepository;
import com.testagent.service.ProjectAccessService;
import com.testagent.service.XmindService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final ConcurrentHashMap<String, AtomicBoolean> cancellationFlags = new ConcurrentHashMap<>();

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestCaseVersionRepository testCaseVersionRepository;

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;

    @Autowired
    private TestGeneratorAgent testGeneratorAgent;

    @Autowired
    private OrchestratorAgent orchestratorAgent;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private XmindService xmindService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Async("generationExecutor")
    public void runGenerate(String projectId, GenerateRequest req) {
        projectAccessService.assertProjectAccess(projectId);
        try {
            // v3.0: 前置校验——PRD 和代码分析至少一项才能生成用例
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
            boolean hasPrd = project.getPrdContent() != null && !project.getPrdContent().isBlank();
            CodeAnalysis analysis = codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
            boolean hasAnalysis = analysis != null && "completed".equals(analysis.getStatus());
            if (!hasPrd && !hasAnalysis) {
                throw new IllegalStateException("请先输入 PRD 或完成代码分析，至少需要一项才能生成用例");
            }

            updateProjectStatus(projectId, "generating");
            // v1.10: 改由 OrchestratorAgent 编排（PrdAgent + 代码侧 → TestGeneratorAgent）
            List<TestCase> testCases = orchestratorAgent.generate(projectId,
                    progress -> projectRepository.updateProgress(projectId, progress));

            projectRepository.updateProgress(projectId, "正在保存用例...");
            testCaseRepository.deleteAll(testCaseRepository.findByProjectId(projectId));

            for (TestCase tc : testCases) {
                tc.setProjectId(projectId);
                testCaseRepository.save(tc);
            }

            // v1.6: 完成时清除进度
            projectRepository.updateProgress(projectId, null);
            updateProjectStatus(projectId, "completed");
            log.info("Test case generation completed for project {}: {} cases",
                    projectId, testCases.size());

        } catch (Exception e) {
            log.error("Test case generation failed for project {}", projectId, e);
            // v1.6: 失败时存储错误详情，前端可展示具体失败原因
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            projectRepository.updateStatusWithError(projectId, "failed", errorMsg);
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
        projectAccessService.assertProjectAccess(projectId);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        // v3.3: 客户端断开同时置 cancelled（不只跳过 send，还要停止生成 + 跳过落库）
        emitter.onCompletion(() -> {
            clientGone.set(true);
            cancelled.set(true);
            log.info("SSE client disconnected: {}", projectId);
        });
        emitter.onTimeout(() -> {
            clientGone.set(true);
            cancelled.set(true);
            log.warn("SSE timeout: {}", projectId);
        });
        emitter.onError(t -> {
            clientGone.set(true);
            cancelled.set(true);
            log.warn("SSE error: {}", projectId, t);
        });
        // v3.3: 注册取消标志（供 cancel 端点触发）
        cancellationFlags.put(projectId, cancelled);

        try {
            // 前置校验（与 runGenerate 一致）
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
            boolean hasPrd = project.getPrdContent() != null && !project.getPrdContent().isBlank();
            CodeAnalysis analysis = codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
            boolean hasAnalysis = analysis != null && "completed".equals(analysis.getStatus());
            if (!hasPrd && !hasAnalysis) {
                throw new IllegalStateException("请先输入 PRD 或完成代码分析，至少需要一项才能生成用例");
            }

            updateProjectStatus(projectId, "generating");

            // 进度回调 → 推送 progress 事件 + 同步写 project.progress（兼容轮询）
            TestGeneratorAgent.ProgressCallback progressCb = msg -> {
                sendSseEvent(emitter, clientGone, "progress", Map.of("message", msg));
                projectRepository.updateProgress(projectId, msg);
            };
            // 用例回调 → 推送 case 事件（每条用例解析完成即推送，不等去重/落库）
            TestGeneratorAgent.CaseCallback caseCb = tc ->
                    sendSseEvent(emitter, clientGone, "case", Map.of("testCase", TestCaseDTO.from(tc)));

            List<TestCase> testCases = orchestratorAgent.generateStreaming(projectId, progressCb, caseCb, cancelled);

            // v3.3: 落库前最终检查（LLM 返回后可能已取消）
            if (cancelled.get()) {
                throw new GenerationCancelledException("用户取消生成");
            }

            progressCb.update("正在保存用例...");
            testCaseRepository.deleteAll(testCaseRepository.findByProjectId(projectId));
            for (TestCase tc : testCases) {
                tc.setProjectId(projectId);
                testCaseRepository.save(tc);
            }

            projectRepository.updateProgress(projectId, null);
            updateProjectStatus(projectId, "completed");

            sendSseEvent(emitter, clientGone, "complete", Map.of("total", testCases.size()));
            safeSseComplete(emitter, clientGone);
            log.info("Streaming generation completed for project {}: {} cases", projectId, testCases.size());
        } catch (GenerationCancelledException e) {
            // v3.3: 落库保护——跳过 deleteAll + save，保留旧用例
            log.info("Streaming generation cancelled for project {}", projectId);
            projectRepository.updateProgress(projectId, null);
            restoreProjectStatus(projectId);
            sendSseEvent(emitter, clientGone, "cancelled",
                    Map.of("message", "生成已取消，旧用例已保留"));
            safeSseComplete(emitter, clientGone);
        } catch (Exception e) {
            log.error("Streaming generation failed for project {}", projectId, e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            projectRepository.updateStatusWithError(projectId, "failed", errorMsg);
            sendSseEvent(emitter, clientGone, "error", Map.of("message", errorMsg));
            safeSseCompleteWithError(emitter, clientGone, e);
        } finally {
            // v3.3: 清理取消标志，避免内存泄漏
            cancellationFlags.remove(projectId);
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
        projectAccessService.assertProjectAccess(projectId);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> {
            clientGone.set(true);
            cancelled.set(true);
            log.info("SSE client disconnected (append): {}", projectId);
        });
        emitter.onTimeout(() -> {
            clientGone.set(true);
            cancelled.set(true);
            log.warn("SSE timeout (append): {}", projectId);
        });
        emitter.onError(t -> {
            clientGone.set(true);
            cancelled.set(true);
            log.warn("SSE error (append): {}", projectId, t);
        });
        cancellationFlags.put(projectId, cancelled);

        try {
            // 前置校验（与 runGenerateStream 一致）
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
            boolean hasPrd = project.getPrdContent() != null && !project.getPrdContent().isBlank();
            CodeAnalysis analysis = codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
            boolean hasAnalysis = analysis != null && "completed".equals(analysis.getStatus());
            if (!hasPrd && !hasAnalysis) {
                throw new IllegalStateException("请先输入 PRD 或完成代码分析，至少需要一项才能生成用例");
            }

            updateProjectStatus(projectId, "generating");

            TestGeneratorAgent.ProgressCallback progressCb = msg -> {
                sendSseEvent(emitter, clientGone, "progress", Map.of("message", msg));
                projectRepository.updateProgress(projectId, msg);
            };
            TestGeneratorAgent.CaseCallback caseCb = tc ->
                    sendSseEvent(emitter, clientGone, "case", Map.of("testCase", TestCaseDTO.from(tc)));

            List<TestCase> generated = orchestratorAgent.generateStreaming(projectId, progressCb, caseCb, cancelled);

            if (cancelled.get()) {
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
            projectRepository.updateProgress(projectId, null);
            restoreProjectStatus(projectId);
            sendSseEvent(emitter, clientGone, "cancelled",
                    Map.of("message", "追加生成已取消，现有用例已保留"));
            safeSseComplete(emitter, clientGone);
        } catch (Exception e) {
            log.error("Append generation failed for project {}", projectId, e);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            projectRepository.updateStatusWithError(projectId, "failed", errorMsg);
            sendSseEvent(emitter, clientGone, "error", Map.of("message", errorMsg));
            safeSseCompleteWithError(emitter, clientGone, e);
        } finally {
            cancellationFlags.remove(projectId);
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
        return false;
    }

    // v3.3: 取消生成（供 Controller 调用）。返回是否成功取消（有进行中的生成任务）。
    public boolean cancelGeneration(String projectId) {
        AtomicBoolean flag = cancellationFlags.get(projectId);
        if (flag != null) {
            flag.set(true);
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
        projectAccessService.assertProjectAccess(projectId);
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);

        // v3.12: 执行状态筛选（not_executed/running/passed/failed，历史数据 null 视为 not_executed）
        if (executionStatus != null && !executionStatus.isBlank()) {
            all = all.stream()
                    .filter(tc -> executionStatus.equals(
                            tc.getExecutionStatus() == null ? "not_executed" : tc.getExecutionStatus()))
                    .collect(Collectors.toList());
        }

        // v1.8: 评审状态筛选（历史数据 null 视为 draft）
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            all = all.stream()
                    .filter(tc -> reviewStatus.equals(
                            tc.getReviewStatus() == null ? "draft" : tc.getReviewStatus()))
                    .collect(Collectors.toList());
        }

        if (type != null && !type.isBlank()) {
            all = all.stream()
                    .filter(tc -> type.equals(tc.getType()))
                    .collect(Collectors.toList());
        }
        if (module != null && !module.isBlank()) {
            all = all.stream()
                    .filter(tc -> module.equals(tc.getModule()))
                    .collect(Collectors.toList());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.toLowerCase();
            all = all.stream()
                    .filter(tc -> (tc.getTitle() != null && tc.getTitle().toLowerCase().contains(kw))
                            || (tc.getModule() != null && tc.getModule().toLowerCase().contains(kw)))
                    .collect(Collectors.toList());
        }

        int total = all.size();
        int fromIndex = Math.max(0, (page - 1) * pageSize);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<TestCase> paged = fromIndex < total
                ? all.subList(fromIndex, toIndex)
                : new ArrayList<>();

        List<TestCaseDTO> items = paged.stream()
                .map(TestCaseDTO::from)
                .collect(Collectors.toList());

        Map<String, Object> coverage = calculateCoverage(projectId, all);

        return TestCaseListResponse.builder()
                .total(total)
                .page(page)
                .pageSize(pageSize)
                .testCases(items)
                .coverage(coverage)
                .build();
    }

    public TestCaseDTO getTestCase(String projectId, String testcaseId) {
        projectAccessService.assertProjectAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
        return TestCaseDTO.from(tc);
    }

    @Transactional
    public void deleteTestCase(String projectId, String testcaseId) {
        projectAccessService.assertProjectAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
        testCaseRepository.delete(tc);
    }

    @Transactional
    public int batchDeleteTestCases(String projectId, java.util.List<String> ids) {
        projectAccessService.assertProjectAccess(projectId);
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);
        int count = 0;
        for (TestCase tc : all) {
            if (ids.contains(tc.getId())) {
                testCaseRepository.delete(tc);
                count++;
            }
        }
        return count;
    }

    // ==================== v1.7: 导入导出与跨项目复制 ====================

    public ResponseEntity<Resource> exportTestCases(String projectId, String format, List<String> ids) {
        projectAccessService.assertProjectAccess(projectId);
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
        projectAccessService.assertProjectAccess(projectId);
        projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        if (file == null || file.isEmpty()) {
            throw BusinessException.invalidParam("导入文件为空");
        }

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
            imported++;
        }

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
        projectAccessService.assertProjectAccess(projectId);
        projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        if (file == null || file.isEmpty()) {
            throw BusinessException.invalidParam("导入文件为空");
        }

        List<TestCase> parsed;
        try {
            parsed = xmindService.parseXmind(file.getInputStream());
        } catch (Exception e) {
            log.warn("XMind 解析失败 project={}", projectId, e);
            throw BusinessException.invalidParam("XMind 解析失败: " + e.getMessage());
        }

        int startNo = nextTestCaseNumber(projectId);
        int imported = 0;
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
            imported++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", parsed.size() - imported);
        result.put("skippedDetails", skippedDetails);
        return result;
    }

    @Transactional
    public Map<String, Object> copyToProject(String sourceProjectId, List<String> ids, String targetProjectId) {
        projectAccessService.assertProjectAccess(sourceProjectId);
        projectAccessService.assertProjectAccess(targetProjectId);
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
        for (TestCase tc : selected) {
            TestCase copy = cloneTestCase(tc);
            copy.setId(String.format("TC-%03d", startNo++));
            copy.setProjectId(targetProjectId);
            copy.setSource("copied");
            copy.setCreatedAt(LocalDateTime.now());
            testCaseRepository.save(copy);
            copied++;
        }

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
        projectAccessService.assertProjectAccess(projectId);
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
        if (reviewer != null && !reviewer.isBlank()) {
            result.put("reviewer", reviewer);
        }
        return result;
    }

    // v3.6: 手动创建测试用例
    @Transactional
    public TestCaseDTO createTestCase(String projectId, CreateTestCaseRequest req) {
        projectAccessService.assertProjectAccess(projectId);
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
        log.info("手动创建用例: projectId={}, id={}", projectId, tc.getId());
        return TestCaseDTO.from(tc);
    }

    @Transactional
    public TestCaseDTO updateTestCase(String projectId, String testcaseId, UpdateTestCaseRequest req) {
        projectAccessService.assertProjectAccess(projectId);
        TestCase tc = findTestCase(projectId, testcaseId);
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
            tc.setExecutionHints(toJson(req.getExecutionHints()));
        }
        if (req.getExecutionStatus() != null) {
            tc.setExecutionStatus(req.getExecutionStatus());
        }

        testCaseRepository.save(tc);
        return TestCaseDTO.from(tc);
    }

    // ==================== v1.9: 用例版本管理 ====================

    public List<TestCaseVersionDTO> listVersions(String projectId, String testcaseId) {
        projectAccessService.assertProjectAccess(projectId);
        findTestCase(projectId, testcaseId);
        return testCaseVersionRepository
                .findByTestCaseIdOrderByVersionNoDesc(testcaseId)
                .stream()
                .map(TestCaseVersionDTO::listFrom)
                .collect(Collectors.toList());
    }

    public TestCaseVersionDTO getVersion(String projectId, String testcaseId, String versionId) {
        projectAccessService.assertProjectAccess(projectId);
        findTestCase(projectId, testcaseId);
        TestCaseVersion v = testCaseVersionRepository
                .findByIdAndTestCaseId(versionId, testcaseId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + versionId));
        return TestCaseVersionDTO.detailFrom(v);
    }

    @Transactional
    public TestCaseDTO rollbackToVersion(String projectId, String testcaseId, String versionId) {
        projectAccessService.assertProjectAccess(projectId);
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
                            totalEndpoints.add(ep.getMethod() + " " + ep.getPath());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse backend result for coverage", e);
                }
            }
        }

        Set<String> coveredEndpoints = new HashSet<>();
        for (TestCase tc : allTestCases) {
            List<Map<String, Object>> eps = JsonHelper.parseListMap(tc.getApiEndpoints());
            for (Map<String, Object> ep : eps) {
                String method = String.valueOf(ep.getOrDefault("method", ""));
                String path = String.valueOf(ep.getOrDefault("path", ""));
                coveredEndpoints.add(method + " " + path);
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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
