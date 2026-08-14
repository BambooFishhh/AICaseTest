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
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private SemanticService semanticService;

    // v5.3: 分析结果缓存（分析完成后失效）
    @Cacheable(value = "analysis", key = "#projectId")
    public CodeAnalysis getAnalysis(String projectId) {
        return codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId).orElse(null);
    }

    // v5.3: 状态机结果缓存（重新分析后失效）
    @Cacheable(value = "stateMachines", key = "#projectId")
    public List<StateMachine> getStateMachines(String projectId) {
        return stateMachineRepository.findByProjectId(projectId);
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void update(String message);
    }

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
        runAnalysisWithProgress(projectId, sourcePath, null);
    }

    /**
     * v4.4: 分析主流程（支持进度回调）。
     */
    public void runAnalysisWithProgress(String projectId, String sourcePath, ProgressCallback progressCb) {
        // v3.9fix: 删除旧的分析记录，避免多次分析导致 NonUniqueResult
        codeAnalysisRepository.findAllByProjectId(projectId).forEach(codeAnalysisRepository::delete);

        CodeAnalysis analysis = new CodeAnalysis(
                UUID.randomUUID().toString().substring(0, 8), projectId);
        analysis.setStatus("running");
        codeAnalysisRepository.save(analysis);

        try {
            updateProjectStatus(projectId, "analyzing");
            report(progressCb, "正在扫描项目结构...");

            ScanResult scanResult = projectScanner.scan(sourcePath);
            report(progressCb, "项目结构扫描完成，正在解析后端代码...");

            FrontendResult frontendResult = null;
            if (scanResult.getFrontendDir() != null && !scanResult.getFrontendDir().isBlank()) {
                report(progressCb, "正在解析前端代码...");
                frontendResult = vueAnalyzer.analyze(scanResult.getFrontendDir());
            }

            BackendResult backendResult = null;
            if (scanResult.getBackendDir() != null && !scanResult.getBackendDir().isBlank()) {
                backendResult = springAnalyzer.analyze(scanResult.getBackendDir());
            }
            report(progressCb, "代码解析完成，正在提取状态机...");

            analysis.setFrontendResult(frontendResult != null
                    ? objectMapper.writeValueAsString(frontendResult) : "{}");
            analysis.setBackendResult(backendResult != null
                    ? objectMapper.writeValueAsString(backendResult) : "{}");
            analysis.setStatus("completed");
            codeAnalysisRepository.save(analysis);
            evictAnalysisCaches(projectId);

            // v5.4: 分析结果写入语义上下文（供生成前 RAG 检索）
            try {
                if (backendResult != null) {
                    semanticService.replaceContext(projectId, "backend",
                            objectMapper.writeValueAsString(backendResult));
                }
                if (frontendResult != null) {
                    semanticService.replaceContext(projectId, "frontend",
                            objectMapper.writeValueAsString(frontendResult));
                }
            } catch (Exception e) {
                log.warn("Failed to index analysis contexts: {}", e.getMessage());
            }

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
            report(progressCb, "分析完成");
            log.info("Analysis completed for project {}", projectId);

        } catch (Exception e) {
            log.error("Analysis failed for project {}", projectId, e);
            analysis.setStatus("failed");
            analysis.setErrorMessage(e.getMessage() != null ? e.getMessage() : "Unknown error");
            codeAnalysisRepository.save(analysis);
            evictAnalysisCaches(projectId);
            updateProjectStatus(projectId, "failed");
        }
    }

    private void evictAnalysisCaches(String projectId) {
        try {
            if (cacheManager.getCache("analysis") != null) {
                cacheManager.getCache("analysis").evict(projectId);
            }
            if (cacheManager.getCache("stateMachines") != null) {
                cacheManager.getCache("stateMachines").evict(projectId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict analysis cache for {}: {}", projectId, e.getMessage());
        }
    }

    /**
     * v4.4: SSE 流式分析——progress/complete/error 事件。
     */
    @Async("analysisExecutor")
    public void runAnalysisStream(String projectId, SseEmitter emitter) {
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onCompletion(() -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));
        emitter.onError(e -> clientGone.set(true));

        try {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalStateException("项目不存在: " + projectId));
            runAnalysisWithProgress(projectId, project.getSourcePath(), msg -> {
                if (!clientGone.get()) {
                    try {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("message", msg);
                        emitter.send(SseEmitter.event().name("progress")
                                .data(data, MediaType.APPLICATION_JSON));
                    } catch (Exception ignored) {
                        clientGone.set(true);
                    }
                }
            });
            if (!clientGone.get()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("message", "分析完成");
                emitter.send(SseEmitter.event().name("complete").data(data, MediaType.APPLICATION_JSON));
            }
            emitter.complete();
        } catch (Exception e) {
            log.error("Stream analysis failed for project {}", projectId, e);
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("message", e.getMessage() != null ? e.getMessage() : "分析失败");
                emitter.send(SseEmitter.event().name("error").data(data, MediaType.APPLICATION_JSON));
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
            emitter.complete();
        }
    }

    private void report(ProgressCallback progressCb, String message) {
        if (progressCb != null) {
            try {
                progressCb.update(message);
            } catch (Exception ignored) {
                // 回调失败不影响分析
            }
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
