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

    @Async("analysisExecutor")
    public void runAnalysis(String projectId, String sourcePath) {
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
