package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.agent.TestGeneratorAgent;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.common.BusinessException;
import com.testagent.dto.GenerateRequest;
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
import java.util.List;
import java.util.Optional;
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
                                               String type, String module) {
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

        int total = all.size();
        int fromIndex = Math.max(0, (page - 1) * pageSize);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<TestCase> paged = fromIndex < total
                ? all.subList(fromIndex, toIndex)
                : new ArrayList<>();

        List<TestCaseDTO> items = paged.stream()
                .map(TestCaseDTO::from)
                .collect(Collectors.toList());

        return TestCaseListResponse.builder()
                .total(total)
                .page(page)
                .pageSize(pageSize)
                .testCases(items)
                .build();
    }

    public TestCaseDTO getTestCase(String projectId, String testcaseId) {
        TestCase tc = findTestCase(projectId, testcaseId);
        return TestCaseDTO.from(tc);
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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
