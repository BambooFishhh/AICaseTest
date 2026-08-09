package com.testagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.agent.TestGeneratorAgent;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.common.BusinessException;
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
import java.util.stream.Collectors;

@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

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
    private ProjectRepository projectRepository;

    @Async("generationExecutor")
    public void runGenerate(String projectId, GenerateRequest req) {
        try {
            updateProjectStatus(projectId, "generating");
            // v1.6: 实时进度反馈，updateProgress 自带事务立即提交，前端轮询可见
            projectRepository.updateProgress(projectId, "正在解析状态机...");

            List<StateMachine> stateMachines = stateMachineRepository.findByProjectId(projectId);

            BackendResult backendResult = BackendResult.skipped();
            Optional<CodeAnalysis> analysisOpt = codeAnalysisRepository.findByProjectId(projectId);
            if (analysisOpt.isPresent()) {
                String backendResultJson = analysisOpt.get().getBackendResult();
                if (backendResultJson != null && !backendResultJson.isBlank()
                        && !backendResultJson.equals("{}")) {
                    try {
                        backendResult = objectMapper.readValue(backendResultJson, BackendResult.class);
                    } catch (Exception e) {
                        log.warn("Failed to parse backend result JSON for project {}", projectId, e);
                    }
                }
            }

            // v1.6: 传入进度回调，分模块生成时实时更新进度
            List<TestCase> testCases = testGeneratorAgent.generate(stateMachines, backendResult,
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

    public TestCaseListResponse listTestCases(String projectId, int page, int pageSize,
                                               String type, String module, String keyword,
                                               String reviewStatus) {
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);

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
        TestCase tc = findTestCase(projectId, testcaseId);
        return TestCaseDTO.from(tc);
    }

    @Transactional
    public void deleteTestCase(String projectId, String testcaseId) {
        TestCase tc = findTestCase(projectId, testcaseId);
        testCaseRepository.delete(tc);
    }

    @Transactional
    public int batchDeleteTestCases(String projectId, java.util.List<String> ids) {
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

    @Transactional
    public Map<String, Object> copyToProject(String sourceProjectId, List<String> ids, String targetProjectId) {
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

    @Transactional
    public TestCaseDTO updateTestCase(String projectId, String testcaseId, UpdateTestCaseRequest req) {
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
        findTestCase(projectId, testcaseId);
        return testCaseVersionRepository
                .findByTestCaseIdOrderByVersionNoDesc(testcaseId)
                .stream()
                .map(TestCaseVersionDTO::listFrom)
                .collect(Collectors.toList());
    }

    public TestCaseVersionDTO getVersion(String projectId, String testcaseId, String versionId) {
        findTestCase(projectId, testcaseId);
        TestCaseVersion v = testCaseVersionRepository
                .findByIdAndTestCaseId(versionId, testcaseId)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + versionId));
        return TestCaseVersionDTO.detailFrom(v);
    }

    @Transactional
    public TestCaseDTO rollbackToVersion(String projectId, String testcaseId, String versionId) {
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
        Optional<CodeAnalysis> analysisOpt = codeAnalysisRepository.findByProjectId(projectId);
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
