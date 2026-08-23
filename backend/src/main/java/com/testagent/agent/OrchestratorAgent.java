package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.common.BusinessException;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.runtime.CancellationSignal;
import com.testagent.service.SemanticService;
import com.testagent.service.TelemetryService;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * v1.10: 用例生成编排 Agent。
 * v1.11: 新增前端上下文加载（FrontendResult）。
 * 显式协调 PrdAgent + 代码侧分析（状态机/后端结果/前端结果） → TestGeneratorAgent。
 */
@Component
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    // v6.4: 生成侧 RAG 只召回需求类切片，避免自我检索整段代码分析 JSON
    private static final List<String> RAG_CONTEXT_MODULES = List.of("prd", "context", "supplementary");

    @Value("${app.rag.context-topk:6}")
    private int ragContextTopK;

    @Value("${app.rag.failure-topk:3}")
    private int ragFailureTopK;

    // v7.10(G9): 删除 app.rag.max-queries 配置——分类别配额（6+3+2+1）取代总量截断

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

    @Autowired
    private TelemetryService telemetryService;

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
        return generate(projectId, progressCallback, null);
    }

    // v7.1(G2/G5): 报告重载——透传 GenerationReport 供服务层采集丢弃/降级信息
    public List<TestCase> generate(String projectId, TestGeneratorAgent.ProgressCallback progressCallback,
                                   TestGeneratorAgent.GenerationReport report) {
        TelemetryService.TelemetryContext telemetry = telemetryService.start("generation", projectId);
        boolean ok = false;
        try {
            GenContext ctx = loadGenerationContext(projectId, progressCallback);
            telemetryService.beginPhaseIfActive("generation");
            List<TestCase> result = testGeneratorAgent.generate(ctx.prdResult(), ctx.stateMachines(),
                    ctx.backendResult(), ctx.frontendResult(), progressCallback, ctx.params(), report);
            telemetryService.endPhase();
            ok = true;
            return result;
        } finally {
            telemetryService.finish(ok);
        }
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
        return generateStreaming(projectId, progressCallback, caseCallback, cancelled, null);
    }

    // v7.1(G2/G5): 报告重载——透传 GenerationReport 供服务层采集丢弃/降级信息
    public List<TestCase> generateStreaming(String projectId,
                                            TestGeneratorAgent.ProgressCallback progressCallback,
                                            TestGeneratorAgent.CaseCallback caseCallback,
                                            CancellationSignal cancelled,
                                            TestGeneratorAgent.GenerationReport report) {
        TelemetryService.TelemetryContext telemetry = telemetryService.start("generation", projectId);
        boolean ok = false;
        try {
            GenContext ctx = loadGenerationContext(projectId, progressCallback);
            telemetryService.beginPhaseIfActive("generation");
            List<TestCase> result = testGeneratorAgent.generateStreaming(ctx.prdResult(), ctx.stateMachines(),
                    ctx.backendResult(), ctx.frontendResult(), progressCallback, caseCallback, cancelled,
                    ctx.params(), report);
            telemetryService.endPhase();
            ok = true;
            return result;
        } finally {
            telemetryService.finish(ok);
        }
    }

    // v3.2: 抽取生成上下文加载（PRD 解析 + 代码/前端结果加载），供 generate 与 generateStreaming 复用
    // v3.4: 解析 Project.settings 得到 GenerationParams，空/失败降级默认值
    private GenContext loadGenerationContext(String projectId, TestGeneratorAgent.ProgressCallback progressCallback) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalStateException("项目不存在: " + projectId));

        // 1. 需求资料解析：PRD 文档 / 上下文文档 / 补充需求，全部交给 PrdAgent 并区分来源
        PrdAnalysisResult prdResult = new PrdAnalysisResult();
        List<Map<String, Object>> reqDocs = new ArrayList<>();
        String supplementary = "";
        try {
            JsonNode settings = objectMapper.readTree(
                    project.getSettings() != null ? project.getSettings() : "{}");
            JsonNode reqNode = settings.path("reqDocs");
            if (reqNode.isArray() && reqNode.size() > 0) {
                reqDocs = objectMapper.convertValue(reqNode,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            } else {
                JsonNode docsNode = settings.path("contextDocs");
                if (docsNode.isArray()) {
                    reqDocs = objectMapper.convertValue(docsNode,
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                }
                if (project.getPrdContent() != null && !project.getPrdContent().isBlank()) {
                    Map<String, Object> prdDoc = new LinkedHashMap<>();
                    prdDoc.put("id", "prd-legacy");
                    prdDoc.put("title", "主 PRD");
                    prdDoc.put("content", project.getPrdContent());
                    prdDoc.put("sourceType", project.getPrdSourceType() == null ? "text" : project.getPrdSourceType());
                    prdDoc.put("sourceRef", project.getPrdSourceRef() == null ? "" : project.getPrdSourceRef());
                    prdDoc.put("docType", "prd");
                    reqDocs.add(0, prdDoc);
                }
            }
            supplementary = settings.path("otherContextInfo").asText("");
            if (supplementary.isBlank()) {
                supplementary = settings.path("supplementaryRequirements").asText("");
            }
            if (supplementary.isBlank()) {
                supplementary = settings.path("extraPrompt").asText("");
            }
        } catch (Exception e) {
            // v7.1(G15): settings 有实质内容但解析失败时不再静默降级为
            // "请先添加 PRD 文档"——那是误导排查方向的错误归因
            if (project.getSettings() != null && !project.getSettings().isBlank()
                    && !"{}".equals(project.getSettings().trim())) {
                throw new BusinessException(50015, "项目配置解析失败：无法读取需求文档配置（"
                        + e.getMessage() + "），请检查项目设置或重新保存需求资料",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            log.warn("Failed to load project requirement docs for {}: {}", projectId, e.getMessage());
        }

        List<Map<String, Object>> prdDocs = new ArrayList<>();
        List<Map<String, Object>> contextDocs = new ArrayList<>();
        StringBuilder ragTextBuilder = new StringBuilder();
        for (Map<String, Object> doc : reqDocs) {
            Object contentObj = doc == null ? null : doc.get("content");
            if (!(contentObj instanceof String content) || content.isBlank()) {
                continue;
            }
            if ("prd".equals(doc.get("docType"))) {
                prdDocs.add(doc);
            } else {
                contextDocs.add(doc);
            }
            if (ragTextBuilder.length() > 0) {
                ragTextBuilder.append("\n\n");
            }
            ragTextBuilder.append(content);
        }
        if (!supplementary.isBlank()) {
            ragTextBuilder.append("\n\n").append(supplementary);
        }

        // v5.13: 生成必须基于 PRD，代码只作为辅助上下文
        if (prdDocs.isEmpty()) {
            throw BusinessException.invalidParam("请先添加 PRD 文档");
        }
        if (progressCallback != null) {
            progressCallback.update("正在解析需求资料（PRD/上下文文档/补充需求）...");
        }
        boolean prdPhase = telemetryService.beginPhaseIfActive("prd");
        prdResult = prdAgent.analyze(prdDocs, contextDocs, supplementary);
        if (prdPhase) {
            telemetryService.endPhase();
        }
        if (prdResult == null || prdResult.isEmpty()) {
            throw new BusinessException(50015, "PRD 解析失败：未能从需求文档中提取有效需求",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        prdResult.setOtherContextInfo(supplementary);
        prdResult.setContextDocs(contextDocs);
        prdResult.setPrdDocs(prdDocs);
        // v5.4: 生成前 RAG 上下文检索（Milvus 未启用时返回空，不影响原流程）
        String ragText = ragTextBuilder.toString();
        if (!ragText.isBlank()) {
            // v7.10(G19): 删除热路径 ensureRequirementContexts 调用——索引维护只在保存侧
            // （updatePrd/uploadPrdPdf/fetchPrdUrl/updateProjectContext 四条路径已全部触发重建），
            // 读路径藏写操作属架构卫生问题；存量项目检索为空时走既有优雅降级
            // v6.4: 模块/需求/上下文文档/补充需求分段查询，RRF 融合，且只召回需求类切片
            // v7.10(G9): 分类别配额（requirements 6 + modules 3 + contextDocs 2 + supplementary 1），
            // 需求优先，取代旧的顺序拼接 + 总量截断
            List<String> ragQueries = buildRagQueries(prdResult);
            List<String> ragContexts = semanticService.retrieveContexts(
                    projectId, ragQueries, ragContextTopK, RAG_CONTEXT_MODULES);
            prdResult.setRagContexts(ragContexts);
            if (!ragContexts.isEmpty()) {
                log.info("RAG retrieved {} contexts for project {}", ragContexts.size(), projectId);
            }
            // v6.4: 历史失败经验闭环——生成前检索相似失败并注入 prompt
            // v7.10(G18): 失败专用查询——需求形查询打动作形语料向量天然弱，
            // 取前 6 条（需求优先）+ 操作/页面类关键词后缀，embedding 调用 12→7
            List<String> ragFailures = semanticService.retrieveFailures(
                    projectId, buildFailureQueries(ragQueries), ragFailureTopK);
            prdResult.setRagFailures(ragFailures);
            if (!ragFailures.isEmpty()) {
                log.info("RAG retrieved {} failures for project {}", ragFailures.size(), projectId);
            }
            // v6.1 (前端 Agentic RAG): 用需求文本定位相关组件摘要，供端到端生成融合 UI 语义
            List<Map<String, Object>> frontendComponents =
                    semanticService.retrieveComponents(projectId, ragQueries, 6);
            prdResult.setFrontendComponents(frontendComponents);
            if (!frontendComponents.isEmpty()) {
                log.info("Frontend RAG hit {} components for project {}", frontendComponents.size(), projectId);
            }
        }
        log.info("Requirement docs analyzed for project {}: prdDocs={}, contextDocs={}, modules={}, requirements={}",
                projectId, prdDocs.size(), contextDocs.size(),
                prdResult.getModules() == null ? 0 : prdResult.getModules().size(),
                prdResult.getRequirements() == null ? 0 : prdResult.getRequirements().size());

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

        // v7.10(C2): 证据链对账——PRD 与代码两条证据链无新鲜度/一致性校验时静默分叉
        // ① 新鲜度：需求资料（project.updatedAt）晚于代码分析/状态机生成 → SSE 提示 + prompt 标注
        applyEvidenceStaleness(project, stateMachines, prdResult, progressCallback);
        // ② 一致性：PRD 状态流的状态在所有代码状态机中零命中 → prompt 显式标注"以代码为准，需人工确认"
        applyStateFlowConsistency(prdResult, stateMachines);

        return new GenContext(prdResult, stateMachines, backendResult, frontendResult, params);
    }

    // v7.10(C2): 证据链新鲜度对账——需求资料（project.updatedAt）晚于代码侧最新产物
    // （最新 CodeAnalysis.createdAt / 最新 StateMachine.createdAt）时标记 stale。
    // project.updatedAt 是项目任意编辑时间，存在误报可能——提示语义为"建议"非"阻断"，可接受。
    // 任一侧无时间戳：证据缺失不判 stale（不误报）。
    void applyEvidenceStaleness(Project project, List<StateMachine> stateMachines,
                                PrdAnalysisResult prdResult, TestGeneratorAgent.ProgressCallback progressCallback) {
        LocalDateTime codeSideLatest = null;
        Optional<CodeAnalysis> analysisOpt =
                codeAnalysisRepository.findFirstByProjectIdOrderByCreatedAtDesc(project.getId());
        if (analysisOpt.isPresent() && analysisOpt.get().getCreatedAt() != null) {
            codeSideLatest = analysisOpt.get().getCreatedAt();
        }
        if (stateMachines != null) {
            for (StateMachine sm : stateMachines) {
                if (sm.getCreatedAt() != null
                        && (codeSideLatest == null || sm.getCreatedAt().isAfter(codeSideLatest))) {
                    codeSideLatest = sm.getCreatedAt();
                }
            }
        }
        if (codeSideLatest == null || project.getUpdatedAt() == null) {
            return;
        }
        if (project.getUpdatedAt().isAfter(codeSideLatest)) {
            prdResult.setEvidenceStale(true);
            log.info("[C2] requirement docs updated at {} after code-side latest {} — code context may be stale",
                    project.getUpdatedAt(), codeSideLatest);
            if (progressCallback != null) {
                progressCallback.update("提示：需求资料在代码分析后有更新，代码上下文可能过期，建议重新分析");
            }
        }
    }

    // v7.10(C2): PRD 状态流与代码状态机一致性对账——某 PRD 状态流的全部状态在所有代码
    // 状态机中零命中（code/name 归一化小写包含匹配）→ 记冲突项，生成 prompt 显式标注
    // "以代码为准，需人工确认"。无代码状态机时不判（证据缺失 ≠ 冲突）。
    void applyStateFlowConsistency(PrdAnalysisResult prdResult, List<StateMachine> stateMachines) {
        if (prdResult == null || prdResult.getStateFlows() == null || prdResult.getStateFlows().isEmpty()) {
            return;
        }
        if (stateMachines == null || stateMachines.isEmpty()) {
            return;
        }
        Set<String> codeStates = new HashSet<>();
        for (StateMachine sm : stateMachines) {
            for (Map<String, Object> s : JsonHelper.parseListMap(sm.getStates())) {
                Object code = s.get("code");
                Object name = s.get("name");
                if (code != null && !String.valueOf(code).isBlank()) {
                    codeStates.add(String.valueOf(code).trim().toLowerCase());
                }
                if (name != null && !String.valueOf(name).isBlank()) {
                    codeStates.add(String.valueOf(name).trim().toLowerCase());
                }
            }
        }
        if (codeStates.isEmpty()) {
            return;
        }
        List<String> conflicts = new ArrayList<>();
        for (Map<String, Object> flow : prdResult.getStateFlows()) {
            List<String> flowStates = readFlowStates(flow);
            if (flowStates.isEmpty()) {
                continue;
            }
            boolean anyHit = false;
            for (String state : flowStates) {
                if (codeStates.contains(state.toLowerCase())) {
                    anyHit = true;
                    break;
                }
            }
            if (!anyHit) {
                String flowName = flow.get("name") == null ? "未命名" : String.valueOf(flow.get("name"));
                conflicts.add("PRD 状态流「" + flowName + "」在代码状态机中无对应状态（PRD: "
                        + String.join("/", flowStates) + "），以代码为准，需人工确认");
            }
        }
        if (!conflicts.isEmpty()) {
            prdResult.setEvidenceInconsistencies(conflicts);
            log.warn("[C2] {} PRD state flow(s) have no matching code states", conflicts.size());
        }
    }

    /** PRD 状态流的 states 列表（元素可能为字符串或 {name/code} 对象），统一转字符串 */
    private List<String> readFlowStates(Map<String, Object> flow) {
        List<String> states = new ArrayList<>();
        Object raw = flow == null ? null : flow.get("states");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String s = null;
                if (item instanceof String str) {
                    s = str;
                } else if (item instanceof Map<?, ?> m) {
                    Object name = m.get("name") != null ? m.get("name") : m.get("code");
                    s = name == null ? null : String.valueOf(name);
                }
                if (s != null && !s.isBlank()) {
                    states.add(s.trim());
                }
            }
        }
        return states;
    }

    // v6.4: 构建检索查询段：各模块 + 各需求 + 上下文文档片段 + 补充需求，不再用整段 PRD 自我检索。
    // v7.10(G9): 分类别配额取代顺序拼接 + 总量截断——旧实现模块多时需求查询被挤出，
    // 与"以需求为纲"的生成策略冲突。新配额：requirements 6 + modules 3 + contextDocs 2 + supplementary 1。
    List<String> buildRagQueries(PrdAnalysisResult prdResult) {
        List<String> requirementQ = new ArrayList<>();
        List<String> moduleQ = new ArrayList<>();
        List<String> contextDocQ = new ArrayList<>();
        List<String> supplementaryQ = new ArrayList<>();
        if (prdResult != null) {
            if (prdResult.getRequirements() != null) {
                for (Map<String, Object> r : prdResult.getRequirements()) {
                    String q = joinFields(r, List.of("title", "description"));
                    if (!q.isBlank()) {
                        requirementQ.add(q);
                    }
                }
            }
            if (prdResult.getModules() != null) {
                for (Map<String, Object> m : prdResult.getModules()) {
                    String q = joinFields(m, List.of("name", "description"));
                    if (!q.isBlank()) {
                        moduleQ.add(q);
                    }
                }
            }
            if (prdResult.getContextDocs() != null) {
                for (Map<String, Object> doc : prdResult.getContextDocs()) {
                    Object contentObj = doc == null ? null : doc.get("content");
                    if (contentObj instanceof String content && !content.isBlank()) {
                        String title = doc.get("title") == null ? "" : String.valueOf(doc.get("title"));
                        String q = (title.isBlank() ? "" : title + "：") + truncate(content, 600);
                        if (!q.isBlank()) {
                            contextDocQ.add(q);
                        }
                    }
                }
            }
            String supplementary = prdResult.getOtherContextInfo();
            if (supplementary != null && !supplementary.isBlank()) {
                supplementaryQ.add(truncate(supplementary, 600));
            }
        }
        List<String> queries = new ArrayList<>();
        queries.addAll(capList(requirementQ, 6));     // 需求优先（对齐"以需求为纲"）
        queries.addAll(capList(moduleQ, 3));
        queries.addAll(capList(contextDocQ, 2));
        queries.addAll(capList(supplementaryQ, 1));
        return queries;
    }

    // v7.10(G18): 失败经验专用查询——需求形查询打动作形语料（action -> error）向量天然弱，
    // 取前 6 条需求查询（buildRagQueries 已按需求优先排序）+ 操作/页面类关键词后缀兜一路动作形召回
    List<String> buildFailureQueries(List<String> ragQueries) {
        List<String> queries = new ArrayList<>(capList(ragQueries, 6));
        queries.add("页面 操作 点击 输入 提交 断言");
        return queries;
    }

    private List<String> capList(List<String> list, int limit) {
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String joinFields(Map<String, Object> map, List<String> fields) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            Object v = map.get(f);
            if (v instanceof String s && !s.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("：");
                }
                sb.append(s);
            }
        }
        return sb.toString().trim();
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
