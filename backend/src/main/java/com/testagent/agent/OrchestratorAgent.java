package com.testagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * v1.10: 用例生成编排 Agent。
 * 显式协调 PrdAgent + 代码侧分析（状态机/后端结果） → TestGeneratorAgent。
 * 替代原 TestCaseService.runGenerate 里的隐式调用链。
 */
@Component
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;

    @Autowired
    private PrdAgent prdAgent;

    @Autowired
    private TestGeneratorAgent testGeneratorAgent;

    /**
     * 编排生成测试用例。
     * 1. 读项目 PRD；若有 PRD，PrdAgent 解析为 PrdAnalysisResult
     * 2. 读代码侧（状态机 + 后端分析结果）
     * 3. 调 TestGeneratorAgent：PRD 为主、代码为辅
     * PRD 为空时 TestGeneratorAgent 退化为代码驱动（向后兼容 v1.9）。
     */
    public List<TestCase> generate(String projectId, TestGeneratorAgent.ProgressCallback progressCallback) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException("项目不存在: " + projectId));

        // 1. PRD 解析
        PrdAnalysisResult prdResult = new PrdAnalysisResult();
        if (project.getPrdContent() != null && !project.getPrdContent().isBlank()) {
            if (progressCallback != null) {
                progressCallback.update("正在解析 PRD...");
            }
            prdResult = prdAgent.analyze(project.getPrdContent());
            log.info("PRD analyzed for project {}: modules={}, requirements={}",
                    projectId,
                    prdResult.getModules() == null ? 0 : prdResult.getModules().size(),
                    prdResult.getRequirements() == null ? 0 : prdResult.getRequirements().size());
        } else {
            log.info("No PRD for project {}, fallback to code-driven generation", projectId);
        }

        // 2. 代码侧
        if (progressCallback != null) {
            progressCallback.update("正在加载代码分析结果...");
        }
        List<StateMachine> stateMachines = stateMachineRepository.findByProjectId(projectId);
        BackendResult backendResult = loadBackendResult(projectId);

        // 3. 生成（PRD 为主、代码为辅）
        return testGeneratorAgent.generate(prdResult, stateMachines, backendResult, progressCallback);
    }

    private BackendResult loadBackendResult(String projectId) {
        BackendResult backendResult = BackendResult.skipped();
        Optional<CodeAnalysis> analysisOpt = codeAnalysisRepository.findByProjectId(projectId);
        if (analysisOpt.isPresent()) {
            String json = analysisOpt.get().getBackendResult();
            if (json != null && !json.isBlank() && !json.equals("{}")) {
                try {
                    backendResult = objectMapper.readValue(json, BackendResult.class);
                } catch (Exception e) {
                    log.warn("Failed to parse backend result for project {}", projectId, e);
                }
            }
        }
        return backendResult;
    }
}
