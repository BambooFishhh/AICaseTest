package com.testagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.common.BusinessException;
import com.testagent.common.GenerationCancelledException;
import com.testagent.dto.EvidenceConflict;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.JsonHelper;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.EvidenceConflictRecord;
import com.testagent.entity.Project;
import com.testagent.entity.ScopeItem;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.runtime.CancellationSignal;
import com.testagent.service.ScopeSlicingService;
import com.testagent.service.SemanticService;
import com.testagent.service.TelemetryService;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.EvidenceConflictRecordRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.ScopeItemRepository;
import com.testagent.repository.StateMachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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

    // v13.17: CODE_ONLY 冲突的代码状态采样上限——代码状态机含大量内部状态，全量列出会淹没 prompt
    private static final int CODE_ONLY_SAMPLE_LIMIT = 10;

    @Value("${app.rag.context-topk:6}")
    private int ragContextTopK;

    @Value("${app.rag.failure-topk:3}")
    private int ragFailureTopK;

    // v13.15(A/B): RAG 生成侧开关——供 eval 做"有/无 RAG"对照；默认 true 不改既有行为
    @Value("${app.rag.enabled:true}")
    private boolean ragEnabled;

    // v7.10(G9): 删除 app.rag.max-queries 配置——分类别配额（6+3+2+1）取代总量截断

    @Autowired
    private ProjectRepository projectRepository;

    // v13.17: 需求状态来源——本期范围项的 changeKind（ADDED/MODIFIED/AFFECTED）决定权威归属
    @Autowired
    private ScopeItemRepository scopeItemRepository;

    // v13.17: 冲突持久化——让冲突从"仅存在于 prompt"变为可见、可裁决。
    // 已裁决记录同时充当裁决规则（键：projectId + conflictId），不再另设规则存储，
    // 避免"裁决已落库但规则未写"的双写不一致。
    @Autowired
    private EvidenceConflictRecordRepository evidenceConflictRecordRepository;

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

    // v8.2: 本期范围切片
    @Autowired
    private ScopeSlicingService scopeSlicingService;

    @Autowired
    private TelemetryService telemetryService;

    // v3.2: 生成上下文容器，供 generate 与 generateStreaming 共用
    // v3.4: 新增 params 字段（项目级生成参数）
    // v8.2: 新增 slice 字段（已确认本期范围，可为 EMPTY）
    private record GenContext(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                              BackendResult backendResult, FrontendResult frontendResult,
                              GenerationParams params, ScopeSlicingService.ScopeSlice slice) {}

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
                    ctx.backendResult(), ctx.frontendResult(), progressCallback, ctx.params(), report,
                    ctx.slice());
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
            GenContext ctx = loadGenerationContext(projectId, progressCallback, cancelled);
            telemetryService.beginPhaseIfActive("generation");
            List<TestCase> result = testGeneratorAgent.generateStreaming(ctx.prdResult(), ctx.stateMachines(),
                    ctx.backendResult(), ctx.frontendResult(), progressCallback, caseCallback, cancelled,
                    ctx.params(), report, ctx.slice());
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
        return loadGenerationContext(projectId, progressCallback, null);
    }

    private GenContext loadGenerationContext(String projectId, TestGeneratorAgent.ProgressCallback progressCallback,
                                             CancellationSignal cancelled) {
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
        checkCancelled(cancelled);
        boolean prdPhase = telemetryService.beginPhaseIfActive("prd");
        prdResult = prdAgent.analyze(prdDocs, contextDocs, supplementary);
        if (prdPhase) {
            telemetryService.endPhase();
        }
        checkCancelled(cancelled);
        if (prdResult == null || prdResult.isEmpty()) {
            throw new BusinessException(50015, "PRD 解析失败：未能从需求文档中提取有效需求",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        prdResult.setOtherContextInfo(supplementary);
        prdResult.setContextDocs(contextDocs);
        prdResult.setPrdDocs(prdDocs);
        // v5.4: 生成前 RAG 上下文检索（Milvus 未启用时返回空，不影响原流程）
        // v13.15(A/B): ragEnabled 关闭时跳过整段 RAG 检索（含失败经验/前端组件），
        //              生成上下文仅剩 PRD/代码，用于 eval 对照"有/无 RAG"的端到端效果
        String ragText = ragTextBuilder.toString();
        if (ragEnabled && !ragText.isBlank()) {
            // v7.10(G19): 删除热路径 ensureRequirementContexts 调用——索引维护只在保存侧
            // （updatePrd/uploadPrdPdf/fetchPrdUrl/updateProjectContext 四条路径已全部触发重建），
            // 读路径藏写操作属架构卫生问题；存量项目检索为空时走既有优雅降级
            // v6.4: 模块/需求/上下文文档/补充需求分段查询，RRF 融合，且只召回需求类切片
            // v7.10(G9): 分类别配额（requirements 6 + modules 3 + contextDocs 2 + supplementary 1），
            // 需求优先，取代旧的顺序拼接 + 总量截断
            checkCancelled(cancelled);
            List<String> ragQueries = buildRagQueries(prdResult);
            List<String> ragContexts = semanticService.retrieveContexts(
                    projectId, ragQueries, ragContextTopK, RAG_CONTEXT_MODULES);
            prdResult.setRagContexts(ragContexts);
            if (!ragContexts.isEmpty()) {
                log.info("RAG retrieved {} contexts for project {}", ragContexts.size(), projectId);
            }
            checkCancelled(cancelled);
            // v6.4: 历史失败经验闭环——生成前检索相似失败并注入 prompt
            // v7.10(G18): 失败专用查询——需求形查询打动作形语料向量天然弱，
            // 取前 6 条（需求优先）+ 操作/页面类关键词后缀，embedding 调用 12→7
            List<String> ragFailures = semanticService.retrieveFailures(
                    projectId, buildFailureQueries(ragQueries), ragFailureTopK);
            prdResult.setRagFailures(ragFailures);
            if (!ragFailures.isEmpty()) {
                log.info("RAG retrieved {} failures for project {}", ragFailures.size(), projectId);
            }
            checkCancelled(cancelled);
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

        // v3.4: 解析生成参数（提前解析，供下方按 sourceMode 决定是否加载代码侧）
        GenerationParams params = parseGenerationParams(project.getSettings());
        boolean prdOnly = "prd-only".equalsIgnoreCase(params.getSourceMode());

        // 2. 代码侧（后端 + 前端）
        // v13.16(D): sourceMode=prd-only 时跳过代码加载——纯 PRD 生成，不注入状态机/接口/前端。
        if (progressCallback != null) {
            progressCallback.update(prdOnly ? "纯 PRD 模式：跳过代码上下文" : "正在加载代码分析结果...");
        }
        List<StateMachine> stateMachines = prdOnly
                ? java.util.List.of()
                : stateMachineRepository.findByProjectId(projectId);
        BackendResult backendResult = prdOnly ? BackendResult.skipped() : loadBackendResult(projectId);
        FrontendResult frontendResult = prdOnly ? null : loadFrontendResult(projectId);
        if (frontendResult != null) {
            log.info("Frontend result loaded for project {}: forms={}, selectors={}, states={}, flows={}",
                    projectId,
                    frontendResult.getForms() == null ? 0 : frontendResult.getForms().size(),
                    frontendResult.getDomSelectors() == null ? 0 : frontendResult.getDomSelectors().size(),
                    frontendResult.getComponentStates() == null ? 0 : frontendResult.getComponentStates().size(),
                    frontendResult.getPageFlows() == null ? 0 : frontendResult.getPageFlows().size());
        }

        // v8.2: 加载已确认本期范围切片（无确认范围返回 EMPTY，纯 PRD 项目不受影响）
        // v13.17: 提前到对账之前——范围项的 changeKind 是权威判定的"需求状态"输入
        ScopeSlicingService.ScopeSlice slice = scopeSlicingService.loadForGeneration(projectId);
        if (!slice.isEmpty()) {
            log.info("[Scope] generation scoped to {}: endpoints {}, SMs {}",
                    slice.definitionId(), slice.targetEndpointsDetail().size(),
                    slice.sprintTransitionsBySmId().size());
            if (progressCallback != null) {
                progressCallback.update("已加载本期范围「" + slice.name() + "」...");
            }
        }

        // v13.17: 需求状态（NEW 新增 / MODIFIED 变更中 / STABLE 存量）——权威判定的第一维输入
        String requirementState = resolveRequirementState(slice);

        // v7.10(C2): 证据链对账——PRD 与代码两条证据链无新鲜度/一致性校验时静默分叉
        // ① 新鲜度：需求资料（project.updatedAt）晚于代码分析/状态机生成 → SSE 提示 + prompt 标注
        applyEvidenceStaleness(project, stateMachines, prdResult, progressCallback);
        // ② 一致性：状态流冲突 → 按（需求状态 × 冲突方向 × 新鲜度）查判定矩阵得出权威归属。
        //    v13.17 起不再无条件"以代码为准"：新增需求以 PRD 为准（代码在其之后实现，可能跑偏），
        //    变更中需求与存量下的 PRD_ONLY 冲突转人工裁决。
        applyStateFlowConsistency(prdResult, stateMachines, requirementState,
                prdResult.isEvidenceStale(), projectId);

        return new GenContext(prdResult, stateMachines, backendResult, frontendResult, params, slice);
    }

    private void checkCancelled(CancellationSignal cancelled) {
        if (cancelled != null && cancelled.isCancelled()) {
            throw new GenerationCancelledException("用例生成已取消");
        }
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

    // v13.17: 权威判定的"需求状态"输入——本期范围项 changeKind 的项目级聚合：
    //   存在 MODIFIED 项 → MODIFIED（变更中，实现是否跟上无法自动判定，权威取最保守：一律人工）
    //   否则存在 ADDED 项 → NEW（本期新增，代码在 PRD 之后实现，可能未充分理解业务意图 → 以 PRD 为准）
    //   其余（含无本期范围数据）→ STABLE
    // 说明：changeKind 粒度是接口/状态机/页面，与"PRD 状态流"并非同一粒度，故此处做项目级聚合
    // 而非跨粒度猜测映射——宁可保守多问，不做脆弱的元素级联想。
    String resolveRequirementState(ScopeSlicingService.ScopeSlice slice) {
        if (slice == null || slice.isEmpty() || scopeItemRepository == null) {
            return EvidenceAuthorityResolver.STATE_STABLE;
        }
        List<ScopeItem> items = scopeItemRepository.findByDefinitionIdOrderByItemTypeAscIdAsc(slice.definitionId());
        if (items == null || items.isEmpty()) {
            return EvidenceAuthorityResolver.STATE_STABLE;
        }
        boolean hasAdded = false;
        for (ScopeItem item : items) {
            if (ScopeItem.KIND_MODIFIED.equals(item.getChangeKind())) {
                return EvidenceAuthorityResolver.STATE_MODIFIED;
            }
            if (ScopeItem.KIND_ADDED.equals(item.getChangeKind())) {
                hasAdded = true;
            }
        }
        return hasAdded ? EvidenceAuthorityResolver.STATE_NEW : EvidenceAuthorityResolver.STATE_STABLE;
    }

    // v7.10(C2) → v13.17: PRD 状态流与代码状态机一致性对账。
    // 旧行为：整条流程零命中 → 一律标注"以代码为准，需人工确认"（权威是全局常量，未区分需求状态）。
    // 新行为：产出结构化 EvidenceConflict，权威由判定矩阵按（需求状态 × 冲突方向 × 新鲜度）给出；
    //        authority=code 的冲突视为可自动采信，不进清单（存量项目代码状态远多于 PRD 描述，
    //        全量上报会淹没真正需关注的冲突），仅 authority=prd/human 的冲突进入 prompt 与裁决队列。
    // 触发阈值仍为"整条流程零命中"：部分命中场景下字符串包含匹配误报率高（PRD"待支付" vs
    //        代码 PENDING_PAYMENT），放开阈值会引入大量噪音；元素级差异改由 prdSide/codeSide 明细承载。
    // 无代码状态机时不判（证据缺失 ≠ 冲突）。
    void applyStateFlowConsistency(PrdAnalysisResult prdResult, List<StateMachine> stateMachines,
                                   String requirementState, boolean stale, String projectId) {
        if (prdResult == null || prdResult.getStateFlows() == null || prdResult.getStateFlows().isEmpty()) {
            return;
        }
        if (stateMachines == null || stateMachines.isEmpty()) {
            return;
        }
        Set<String> codeStates = collectCodeStates(stateMachines);
        if (codeStates.isEmpty()) {
            return;
        }

        // v13.17: 已裁决记录充当裁决规则——同一冲突（projectId + conflictId）此前若已被人工裁决，
        // 本轮直接套用，不再重复提请，避免同一分歧反复打扰人
        Map<String, String> resolvedVerdicts = loadResolvedVerdicts(projectId);

        List<EvidenceConflict> all = new ArrayList<>();
        Set<String> covered = new HashSet<>();

        // ① PRD_ONLY：某条状态流的全部状态在代码状态机中零命中 → PRD 侧元素未实现
        for (Map<String, Object> flow : prdResult.getStateFlows()) {
            List<String> flowStates = readFlowStates(flow);
            if (flowStates.isEmpty()) {
                continue;
            }
            boolean anyHit = false;
            for (String state : flowStates) {
                if (codeStates.contains(normalizeState(state))) {
                    anyHit = true;
                    covered.add(normalizeState(state));
                }
            }
            if (anyHit) {
                continue;
            }
            String flowName = flow.get("name") == null ? "未命名" : String.valueOf(flow.get("name"));
            all.add(buildConflict(EvidenceConflict.DIM_STATE_FLOW, flowName,
                    EvidenceAuthorityResolver.DIR_PRD_ONLY, flowStates, List.of(),
                    requirementState, stale, resolvedVerdicts));
        }

        // ② CODE_ONLY：代码状态机中存在、但全部 PRD 状态流都未描述的状态（实现溢出 / 文档滞后）
        List<String> sortedCodeStates = new ArrayList<>(codeStates);
        sortedCodeStates.sort(null);
        List<String> uncovered = new ArrayList<>();
        for (String codeState : sortedCodeStates) {
            if (!covered.contains(codeState)) {
                uncovered.add(codeState);
                if (uncovered.size() >= CODE_ONLY_SAMPLE_LIMIT) {
                    break;
                }
            }
        }
        if (!uncovered.isEmpty()) {
            all.add(buildConflict(EvidenceConflict.DIM_STATE_FLOW,
                    "代码状态机中未被 PRD 描述的状态",
                    EvidenceAuthorityResolver.DIR_CODE_ONLY, List.of(), uncovered,
                    requirementState, stale, resolvedVerdicts));
        }

        // v13.17: 全量落库（含已裁决项）——冲突由此从"仅存在于 prompt"变为可查询、可裁决
        persistConflicts(projectId, all);

        // v13.19: 白名单——只有 prd / human 需要作为生成依据注入 prompt。
        // code(历史值) / skip(无需求依据不生成) / deferred(暂缓) / deprecated(已废弃) 均不注入：
        // 它们不该成为生成依据，全量上报既稀释真正需要关注的分歧，也可能诱导模型
        // 为没有 PRD 描述的元素造用例（违背"用例只来源于 PRD"）。
        List<EvidenceConflict> forPrompt = new ArrayList<>();
        for (EvidenceConflict conflict : all) {
            String authority = conflict.getAuthority();
            if (EvidenceAuthorityResolver.AUTH_PRD.equals(authority)
                    || EvidenceAuthorityResolver.AUTH_HUMAN.equals(authority)) {
                forPrompt.add(conflict);
            }
        }
        if (!forPrompt.isEmpty()) {
            prdResult.setEvidenceConflicts(forPrompt);
            // 兼容旧字段：prompt 注入与既有消费方仍读取字符串摘要
            List<String> summaries = new ArrayList<>();
            for (EvidenceConflict conflict : forPrompt) {
                summaries.add(conflict.getReason());
            }
            prdResult.setEvidenceInconsistencies(summaries);
            log.warn("[C2] {} evidence conflict(s) kept for prompt (requirementState={}, stale={}, total={})",
                    forPrompt.size(), requirementState, stale, all.size());
        }
    }

    /**
     * v13.17: 读取本项目的已裁决记录作为规则表（conflictKey → verdict）。
     * 仓储不可用（单测直接 new 出 Agent）时返回空表，不影响对账主流程。
     */
    Map<String, String> loadResolvedVerdicts(String projectId) {
        Map<String, String> rules = new HashMap<>();
        if (evidenceConflictRecordRepository == null || projectId == null) {
            return rules;
        }
        try {
            for (EvidenceConflictRecord record
                    : evidenceConflictRecordRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
                if (record.isResolved()) {
                    rules.put(record.getConflictKey(), record.getVerdict());
                }
            }
        } catch (Exception e) {
            log.warn("[C2] load evidence verdict rules failed, fallback to matrix: {}", e.getMessage());
        }
        return rules;
    }

    /**
     * v13.17: 冲突落库（同 projectId + conflictId 走 upsert）。
     * 事实字段每轮刷新；**裁决字段只在新建时初始化，已有裁决一律保留**，避免人工裁决被新一轮生成冲掉。
     * 落库失败仅告警不抛出——注入 prompt 的主路径不应被持久化问题阻断。
     */
    void persistConflicts(String projectId, List<EvidenceConflict> conflicts) {
        if (evidenceConflictRecordRepository == null || projectId == null
                || conflicts == null || conflicts.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (EvidenceConflict conflict : conflicts) {
            try {
                String id = EvidenceConflictRecord.buildId(projectId, conflict.getConflictId());
                EvidenceConflictRecord record = evidenceConflictRecordRepository.findById(id).orElse(null);
                if (record == null) {
                    record = new EvidenceConflictRecord();
                    record.setId(id);
                    record.setProjectId(projectId);
                    record.setConflictKey(conflict.getConflictId());
                    record.setVerdict(EvidenceConflictRecord.VERDICT_PENDING);
                    record.setCreatedAt(now);
                }
                record.setDimension(conflict.getDimension());
                record.setAnchor(conflict.getAnchor());
                record.setDirection(conflict.getDirection());
                record.setPrdSide(toJsonArray(conflict.getPrdSide()));
                record.setCodeSide(toJsonArray(conflict.getCodeSide()));
                record.setRequirementState(conflict.getRequirementState());
                record.setStale(conflict.isStale());
                record.setAuthority(conflict.getAuthority());
                record.setManualRequired(conflict.isManualRequired());
                record.setReason(conflict.getReason());
                record.setUpdatedAt(now);
                evidenceConflictRecordRepository.save(record);
            } catch (Exception e) {
                log.warn("[C2] persist evidence conflict failed: {}", e.getMessage());
            }
        }
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 代码侧状态池：状态机 states 的 code 与 name 归一化小写后合并（沿用旧匹配口径） */
    private Set<String> collectCodeStates(List<StateMachine> stateMachines) {
        Set<String> codeStates = new HashSet<>();
        for (StateMachine sm : stateMachines) {
            for (Map<String, Object> s : JsonHelper.parseListMap(sm.getStates())) {
                Object code = s.get("code");
                Object name = s.get("name");
                if (code != null && !String.valueOf(code).isBlank()) {
                    codeStates.add(normalizeState(String.valueOf(code)));
                }
                if (name != null && !String.valueOf(name).isBlank()) {
                    codeStates.add(normalizeState(String.valueOf(name)));
                }
            }
        }
        return codeStates;
    }

    private String normalizeState(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    /**
     * 构造冲突项并查判定矩阵；若该冲突此前已被人工裁决（resolvedVerdicts 命中），套用裁决结果。
     * 注意：不再对 authority=code 的冲突返回 null——所有冲突都要落库留痕，
     * "不进 prompt" 的过滤发生在收集侧（见 applyStateFlowConsistency 的 forPrompt）。
     */
    private EvidenceConflict buildConflict(String dimension, String anchor, String direction,
                                           List<String> prdSide, List<String> codeSide,
                                           String requirementState, boolean stale,
                                           Map<String, String> resolvedVerdicts) {
        EvidenceAuthorityResolver.Verdict verdict =
                EvidenceAuthorityResolver.resolve(requirementState, direction, stale);
        EvidenceConflict conflict = new EvidenceConflict();
        conflict.setConflictId(EvidenceConflict.buildId(dimension, anchor));
        conflict.setDimension(dimension);
        conflict.setAnchor(anchor);
        conflict.setDirection(direction);
        conflict.setPrdSide(prdSide);
        conflict.setCodeSide(codeSide);
        conflict.setRequirementState(requirementState);
        conflict.setStale(stale);
        conflict.setAuthority(verdict.authority());
        conflict.setManualRequired(verdict.manualRequired());
        conflict.setReason(describeConflict(dimension, anchor, direction, prdSide, codeSide, verdict));

        String resolved = resolvedVerdicts == null ? null : resolvedVerdicts.get(conflict.getConflictId());
        if (resolved != null) {
            applyResolvedVerdict(conflict, resolved);
        }
        return conflict;
    }

    /**
     * v13.17: 套用历史裁决——同一冲突已被人裁决过，本轮不再重复提请人工。
     * deprecated 表示该 PRD 侧需求已废弃：既不生成对应用例，也不再打扰人，仅保留记录可查。
     */
    private void applyResolvedVerdict(EvidenceConflict conflict, String verdict) {
        String note = "；已按历史裁决自动套用（" + verdict + "）";
        if (EvidenceConflictRecord.VERDICT_PRD.equals(verdict)) {
            conflict.setAuthority(EvidenceAuthorityResolver.AUTH_PRD);
            conflict.setManualRequired(false);
            conflict.setReason(conflict.getReason() + note);
        } else if (EvidenceConflictRecord.VERDICT_CODE.equals(verdict)) {
            conflict.setAuthority(EvidenceAuthorityResolver.AUTH_CODE);
            conflict.setManualRequired(false);
            conflict.setReason(conflict.getReason() + note);
        } else if (EvidenceConflictRecord.VERDICT_DEPRECATED.equals(verdict)) {
            conflict.setAuthority(EvidenceConflict.AUTHORITY_DEPRECATED);
            conflict.setManualRequired(false);
            conflict.setReason(conflict.getReason() + note + "，需求已废弃，不再生成对应用例");
        }
    }

    /** 人读冲突说明：先客观陈述两侧差异，再附判定矩阵给出的权威归属理由 */
    private String describeConflict(String dimension, String anchor, String direction,
                                    List<String> prdSide, List<String> codeSide,
                                    EvidenceAuthorityResolver.Verdict verdict) {
        StringBuilder sb = new StringBuilder();
        if (EvidenceConflict.DIM_STATE_FLOW.equals(dimension)) {
            if (EvidenceAuthorityResolver.DIR_CODE_ONLY.equals(direction)) {
                sb.append("代码状态机中存在 PRD 未描述的状态（代码: ")
                        .append(String.join("/", codeSide)).append("）");
            } else {
                sb.append("PRD 状态流「").append(anchor)
                        .append("」在代码状态机中无对应状态（PRD: ")
                        .append(String.join("/", prdSide)).append("）");
            }
        } else {
            sb.append(anchor);
        }
        return sb.append("；").append(verdict.reason()).toString();
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
            if (params.getSourceMode() == null || params.getSourceMode().isBlank()) params.setSourceMode("code+prd");
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
