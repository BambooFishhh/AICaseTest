package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.ProjectScanner;
import com.testagent.analyzer.SpringAnalyzer;
import com.testagent.analyzer.VueAnalyzer;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.analyzer.result.ScanResult;
import com.testagent.agent.StateMachineAgent;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.StateMachine;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @Autowired
    private ProjectScanner projectScanner;

    @Autowired
    private VueAnalyzer vueAnalyzer;

    @Autowired
    private SpringAnalyzer springAnalyzer;

    @Autowired
    private StateMachineAgent stateMachineAgent;

    /**
     * v3.9fix: 启动时清理重复的 CodeAnalysis 记录（每个项目只保留最新一条）。
     */
    @PostConstruct
    public void cleanupDuplicateAnalysis() {
        List<String> projectIds = projectRepository.findAll().stream()
                .map(Project::getId)
                .collect(Collectors.toList());
        int cleaned = 0;
        for (String pid : projectIds) {
            List<CodeAnalysis> all = codeAnalysisRepository.findAllByProjectId(pid);
            if (all.size() > 1) {
                // 保留最新一条（createdAt 最大），删除其余
                all.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
                for (int i = 1; i < all.size(); i++) {
                    codeAnalysisRepository.delete(all.get(i));
                    cleaned++;
                }
            }
        }
        if (cleaned > 0) {
            log.info("Cleanup: removed {} duplicate CodeAnalysis records", cleaned);
        }
    }

    @Async("analysisExecutor")
    public void runAnalysis(String projectId, String sourcePath) {
        // v3.9fix: 删除旧的分析记录，避免多次分析导致 NonUniqueResult
        codeAnalysisRepository.findAllByProjectId(projectId).forEach(codeAnalysisRepository::delete);

        CodeAnalysis analysis = new CodeAnalysis(
                UUID.randomUUID().toString().substring(0, 8), projectId);
        analysis.setStatus("running");
        codeAnalysisRepository.save(analysis);

        try {
            updateProjectStatus(projectId, "analyzing");

            ScanResult scanResult = projectScanner.scan(sourcePath);

            FrontendResult frontendResult = null;
            if (scanResult.getFrontendDir() != null && !scanResult.getFrontendDir().isBlank()) {
                frontendResult = vueAnalyzer.analyze(scanResult.getFrontendDir());
            }

            BackendResult backendResult = null;
            if (scanResult.getBackendDir() != null && !scanResult.getBackendDir().isBlank()) {
                backendResult = springAnalyzer.analyze(scanResult.getBackendDir());
            }

            analysis.setFrontendResult(frontendResult != null
                    ? objectMapper.writeValueAsString(frontendResult) : "{}");
            analysis.setBackendResult(backendResult != null
                    ? objectMapper.writeValueAsString(backendResult) : "{}");
            analysis.setStatus("completed");
            codeAnalysisRepository.save(analysis);

            if (scanResult.getTechStack() != null && !scanResult.getTechStack().isEmpty()) {
                updateProjectTechStack(projectId, objectMapper.writeValueAsString(scanResult.getTechStack()));
            }

            if (backendResult != null) {
                stateMachineRepository.deleteAll(stateMachineRepository.findByProjectId(projectId));
                List<StateMachine> stateMachines = stateMachineAgent.extract(backendResult);
                for (StateMachine sm : stateMachines) {
                    sm.setId(UUID.randomUUID().toString().substring(0, 8));
                    sm.setProjectId(projectId);
                    stateMachineRepository.save(sm);
                }
            }

            updateProjectStatus(projectId, "analyzed");
            log.info("Analysis completed for project {}", projectId);

        } catch (Exception e) {
            log.error("Analysis failed for project {}", projectId, e);
            analysis.setStatus("failed");
            analysis.setErrorMessage(e.getMessage() != null ? e.getMessage() : "Unknown error");
            codeAnalysisRepository.save(analysis);
            updateProjectStatus(projectId, "failed");
        }
    }

    private void updateProjectStatus(String projectId, String status) {
        projectRepository.findById(projectId).ifPresent(project -> {
            project.setStatus(status);
            projectRepository.save(project);
        });
    }

    private void updateProjectTechStack(String projectId, String techStack) {
        projectRepository.findById(projectId).ifPresent(project -> {
            project.setTechStack(techStack);
            projectRepository.save(project);
        });
    }
}
