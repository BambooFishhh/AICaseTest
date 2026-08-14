package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.runtime.CancellationSignal;
import com.testagent.service.SemanticService;
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
 * v1.11: 新增前端上下文加载（FrontendResult）。
 * 显式协调 PrdAgent + 代码侧分析（状态机/后端结果/前端结果） → TestGeneratorAgent。
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

    @Autowired
    private SemanticService semanticService;

    // v3.2: 生成上下文容器，供 generate 与 generateStreaming 共用
    // v3.4: 新增 params 字段（项目级生成参数）
    private record GenContext(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                              BackendResult backendResult, FrontendResult frontendResult,
                              GenerationParams params) {}

    /**
     * 编排生成测试用例。
     * 1. 读项目 PRD；若有 PRD，PrdAgent 解析为 PrdAnalysisResult
     * 2. 读代码侧（状态机 + 后端分析结果 + 前端分析结果）
     * 3. 调 TestGeneratorAgent：PRD 为主、代码为辅（含前端上下文）
     * PRD 为空时 TestGeneratorAgent 退化为代码驱动（向后兼容 v1.9）。
     * v3.4: 透传 GenerationParams（从 Project.settings 解析），供 TestGeneratorAgent 动态拼接 prompt + 调整 temperature。
     */
    public List<TestCase> generate(String projectId, TestGeneratorAgent.ProgressCallback progressCallback) {
        GenContext ctx = loadGenerationContext(projectId, progressCallback);
        return testGeneratorAgent.generate(ctx.prdResult(), ctx.stateMachines(), ctx.backendResult(),
                ctx.frontendResult(), progressCallback, ctx.params());
    }

    /**
     * v3.2: 流式编排生成。与 generate 行为一致，额外通过 caseCb 在每条用例解析完成时回调（用于 SSE 推送）。
     * v3.3: 新增 cancelled 参数，透传给 TestGeneratorAgent 用于取消检查。
     * v3.4: 透传 GenerationParams。
     */
    public List<TestCase> generateStreaming(String projectId,
                                            TestGeneratorAgent.ProgressCallback progressCallback,
                                            TestGeneratorAgent.CaseCallback caseCallback,
                                            CancellationSignal cancelled) {
        GenContext ctx = loadGenerationContext(projectId, progressCallback);
        return testGeneratorAgent.generateStreaming(ctx.prdResult(), ctx.stateMachines(), ctx.backendResult(),
                ctx.frontendResult(), progressCallback, caseCallback, cancelled, ctx.params());
    }

    // v3.2: 抽取生成上下文加载（PRD 解析 + 代码/前端结果加载），供 generate 与 generateStreaming 复用
    // v3.4: 解析 Project.settings 得到 GenerationParams，空/失败降级默认值
    private GenContext loadGenerationContext(String projectId, TestGeneratorAgent.ProgressCallback progressCallback) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException("项目不存在: " + projectId));

        // 1. PRD 解析
        PrdAnalysisResult prdResult = new PrdAnalysisResult();
        if (project.getPrdContent() != null && !project.getPrdContent().isBlank()) {
            if (progressCallback != null) {
                progressCallback.update("正在解析 PRD...");
            }
            prdResult = prdAgent.analyze(project.getPrdContent());
            // v5.4: 生成前 RAG 上下文检索（Milvus 未启用时返回空，不影响原流程）
            List<String> ragContexts = semanticService.retrieveContexts(
                    projectId, project.getPrdContent(), 5);
            prdResult.setRagContexts(ragContexts);
            if (!ragContexts.isEmpty()) {
                log.info("RAG retrieved {} contexts for project {}", ragContexts.size(), projectId);
            }
            log.info("PRD analyzed for project {}: modules={}, requirements={}",
                    projectId,
                    prdResult.getModules() == null ? 0 : prdResult.getModules().size(),
                    prdResult.getRequirements() == null ? 0 : prdResult.getRequirements().size());
        } else {
            log.info("No PRD for project {}, fallback to code-driven generation", projectId);
        }

        // 2. 代码侧（后端 + 前端）
        if (progressCallback != null) {
            progressCallback.update("正在加载代码分析结果...");
        }
        List<StateMachine> stateMachines = stateMachineRepository.findByProjectId(projectId);
        BackendResult backendResult = loadBackendResult(projectId);
        FrontendResult frontendResult = loadFrontendResult(projectId);
        if (frontendResult != null) {
            log.info("Frontend result loaded for project {}: forms={}, selectors={}, states={}, flows={}",
                    projectId,
                    frontendResult.getForms() == null ? 0 : frontendResult.getForms().size(),
                    frontendResult.getDomSelectors() == null ? 0 : frontendResult.getDomSelectors().size(),
                    frontendResult.getComponentStates() == null ? 0 : frontendResult.getComponentStates().size(),
                    frontendResult.getPageFlows() == null ? 0 : frontendResult.getPageFlows().size());
        }

        // v3.4: 解析生成参数
        GenerationParams params = parseGenerationParams(project.getSettings());
        return new GenContext(prdResult, stateMachines, backendResult, frontendResult, params);
    }

    // v3.4: 从 Project.settings JSON 解析生成参数，失败/空降级默认值
    private GenerationParams parseGenerationParams(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank() || "{}".equals(settingsJson)) {
            return GenerationParams.defaults();
        }
        try {
            JsonNode settings = objectMapper.readTree(settingsJson);
            JsonNode gpNode = settings.path("generationParams");
            if (gpNode.isMissingNode() || gpNode.isNull()) {
                return GenerationParams.defaults();
            }
            GenerationParams params = objectMapper.treeToValue(gpNode, GenerationParams.class);
            if (params.getCaseDensity() == null) params.setCaseDensity("medium");
            if (params.getTemperature() == null) params.setTemperature(0.4);
            if (params.getFocusTypes() == null) params.setFocusTypes(List.of());
            return params;
        } catch (Exception e) {
            log.warn("Failed to parse generation params, using defaults", e);
            return GenerationParams.defaults();
        }
    }

    private BackendResult loadBackendResult(String projectId) {
        BackendResult backendResult = BackendResult.skipped();
        Optional<CodeAnalysis> analysisOpt = codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId);
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

    // v1.11: 加载前端分析结果
    private FrontendResult loadFrontendResult(String projectId) {
        Optional<CodeAnalysis> analysisOpt = codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId);
        if (analysisOpt.isPresent()) {
            String json = analysisOpt.get().getFrontendResult();
            if (json != null && !json.isBlank() && !json.equals("{}")) {
                try {
                    return objectMapper.readValue(json, FrontendResult.class);
                } catch (Exception e) {
                    log.warn("Failed to parse frontend result for project {}", projectId, e);
                }
            }
        }
        return null;
    }
}
