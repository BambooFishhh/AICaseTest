package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.agent.TestGeneratorAgent;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.EndpointInfo;
import com.testagent.common.BusinessException;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.TestCaseDTO;
import com.testagent.dto.TestCaseListResponse;
import com.testagent.dto.UpdateTestCaseRequest;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TestCaseRepository testCaseRepository;

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

            List<TestCase> testCases = testGeneratorAgent.generate(stateMachines, backendResult);

            testCaseRepository.deleteAll(testCaseRepository.findByProjectId(projectId));

            for (TestCase tc : testCases) {
                tc.setProjectId(projectId);
                testCaseRepository.save(tc);
            }

            updateProjectStatus(projectId, "completed");
            log.info("Test case generation completed for project {}: {} cases",
                    projectId, testCases.size());

        } catch (Exception e) {
            log.error("Test case generation failed for project {}", projectId, e);
            updateProjectStatus(projectId, "failed");
        }
    }

    public TestCaseListResponse listTestCases(String projectId, int page, int pageSize,
                                               String type, String module, String keyword) {
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);

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

    @Transactional
    public TestCaseDTO updateTestCase(String projectId, String testcaseId, UpdateTestCaseRequest req) {
        TestCase tc = findTestCase(projectId, testcaseId);

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
