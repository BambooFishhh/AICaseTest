package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.agent.ScopeMappingAgent;
import com.testagent.common.BusinessException;
import com.testagent.dto.JsonHelper;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.ScopeDefinition;
import com.testagent.entity.ScopeItem;
import com.testagent.entity.StateMachine;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.ScopeDefinitionRepository;
import com.testagent.repository.ScopeItemRepository;
import com.testagent.repository.StateMachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * v8.1: 范围识别流水线——Git diff 文件集 → 接口/状态机映射 → LLM 补充 → 人工确认。
 * v9.0: 分析完成后自动识别并锁定（autoSyncAfterAnalysis，重新分析 = 刷新范围）；
 * 已确认范围放开条目增删，锁定语义收敛为「重算/重复确认受限，条目可人工调整」。
 */
@Service
public class ScopeService {

    private static final Logger log = LoggerFactory.getLogger(ScopeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** v8.9.8: 视为前端变更文件的扩展名（JS/TS 太噪不逐文件入范围） */
    private static final List<String> FRONTEND_FILE_EXTS =
            List.of(".vue", ".jsx", ".tsx", ".svelte", ".wxml", ".axml", ".html");
    /** PAGE 条目上限——防组件库批量重构撑爆范围清单 */
    private static final int PAGE_ITEMS_CAP = 30;

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private ProjectAccessService projectAccessService;
    @Autowired
    private GitDiffService gitDiffService;
    @Autowired
    private ScopeDefinitionRepository definitionRepository;
    @Autowired
    private ScopeItemRepository itemRepository;
    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;
    @Autowired
    private StateMachineRepository stateMachineRepository;
    @Autowired
    private ScopeMappingAgent scopeMappingAgent;

    /** v9.0: 分析后自动创建的范围名（全自动流程下用户不命名） */
    private static final String AUTO_SCOPE_NAME = "本期范围";

    // ==================== 查询 ====================

    public List<Map<String, Object>> listDefinitions(String projectId) {
        projectAccessService.assertViewAccess(projectId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScopeDefinition def : definitionRepository.findByProjectIdOrderByCreatedAtDesc(projectId)) {
            Map<String, Object> row = toMap(def);
            row.put("itemCount", itemRepository.countByDefinitionId(def.getId()));
            result.add(row);
        }
        return result;
    }

    public List<Map<String, Object>> listItems(String projectId, String definitionId) {
        projectAccessService.assertViewAccess(projectId);
        ScopeDefinition def = requireDefinitionOfProject(projectId, definitionId);
        return itemRepository.findByDefinitionIdOrderByItemTypeAscIdAsc(def.getId())
                .stream().map(this::toMap).collect(Collectors.toList());
    }

    public Map<String, Object> listGitRefs(String projectId) {
        projectAccessService.assertViewAccess(projectId);
        Project project = requireProject(projectId);
        if (!gitDiffService.isGitRepo(project.getSourcePath())) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("git", false);
            return r;
        }
        Map<String, Object> refs = gitDiffService.listRefs(project.getSourcePath());
        refs.put("git", true);
        // v8.9.8: 供前端预填基线默认值（留空创建时后端同样回退此值）
        refs.put("defaultBaseline", gitDiffService.detectDefaultBaseline(project.getSourcePath()));
        return refs;
    }

    // ==================== 创建草稿（识别流水线） ====================

    @Transactional
    public Map<String, Object> createDraft(String projectId, String name, String baselineRef) {
        projectAccessService.assertOperateAccess(projectId);
        if (name == null || name.isBlank()) {
            throw BusinessException.invalidParam("范围名称不能为空");
        }
        // v8.9.8: 基线留空时自动回退仓库默认主干（origin/HEAD → master → main），
        // 常规迭代基线即主干，免手选；探测不到才要求显式输入（异常仓库不静默掩盖）
        String baseline = baselineRef == null ? "" : baselineRef.trim();
        Project project = requireProject(projectId);
        if (baseline.isBlank()) {
            if (project.getSourcePath() == null || project.getSourcePath().isBlank()
                    || !gitDiffService.isGitRepo(project.getSourcePath())) {
                throw BusinessException.invalidParam("非 Git 仓库无法自动探测基线，请手动填写基线引用");
            }
            String detected = gitDiffService.detectDefaultBaseline(project.getSourcePath());
            if (detected == null) {
                throw BusinessException.invalidParam(
                        "未探测到默认基线（无 origin/HEAD 且无 master/main 分支），请手动填写基线引用");
            }
            baseline = detected;
            log.info("[Scope] 基线留空，自动回退默认主干: {}", baseline);
        }
        // v8.3fix: 单例约束——一个项目同一时间只允许一个本期范围（草稿或已确认），
        // 多个并存会让覆盖率分母与生成目标集产生歧义（此前 latestConfirmed 静默取最新）
        List<ScopeDefinition> existingDefs =
                definitionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        if (!existingDefs.isEmpty()) {
            ScopeDefinition cur = existingDefs.get(0);
            String stateLabel = ScopeDefinition.STATUS_CONFIRMED.equals(cur.getStatus())
                    ? "已确认" : "草稿";
            throw BusinessException.invalidState("该项目已存在本期范围「" + cur.getName()
                    + "」（" + stateLabel + "）。刷新识别请用「重算」；开启新迭代请先删除旧范围");
        }
        if (project.getSourcePath() == null || project.getSourcePath().isBlank()) {
            throw BusinessException.invalidParam("项目未配置源码路径，无法自动识别");
        }
        CodeAnalysis analysis = latestAnalysis(projectId);
        boolean gitRepo = gitDiffService.isGitRepo(project.getSourcePath());
        // v8.2: 非 Git 仓库允许创建空草稿（手动添加条目），不再直接拒绝——
        // 纯 PRD/文件夹上传项目也需要范围模型参与生成前置校验
        if (!gitRepo && analysis == null) {
            throw BusinessException.invalidState("请先完成代码分析，再创建本期范围");
        }

        // v8.9.8: 时效性护栏——分析结果旧于 HEAD 提交时，映射表与当前代码不一致会系统性漏识别，
        // 不阻断（存量分析仍有参考价值）但向前端透出提示，引导重分析后重算（recompute）
        boolean analysisStale = isAnalysisStale(projectId, project);
        if (analysisStale) {
            log.warn("[Scope] 代码分析结果旧于 HEAD 提交，识别可能漏项——建议重新分析后点「重算」");
        }

        ScopeDefinition def = new ScopeDefinition();
        def.setId(newId());
        def.setProjectId(projectId);
        def.setName(name.trim());
        def.setBaselineRef(baseline);
        def.setStatus(ScopeDefinition.STATUS_DRAFT);
        definitionRepository.save(def);

        if (!gitRepo) {
            log.info("[Scope] 草稿 {} 创建（非 Git 仓库，跳过自动识别，等待手动添加条目）", def.getId());
            Map<String, Object> r = toMap(def);
            r.put("autoIdentified", false);
            r.put("analysisStale", analysisStale);
            return r;
        }

        int created = runIdentification(def, project, analysis);
        log.info("[Scope] 草稿 {} 创建完成: {} 条范围项", def.getId(), created);
        Map<String, Object> r = toMap(def);
        r.put("autoIdentified", true);
        r.put("analysisStale", analysisStale);
        return r;
    }

    /**
     * v9.0: 分析完成后自动识别本期范围——基线自动回退默认主干（origin/HEAD → master → main），
     * 识别出条目即确认锁定，配合前端隐藏确认入口的全自动流程（重新分析 = 刷新范围）。
     * 系统内部调用：不走 assertOperateAccess（@Async 分析线程无安全上下文，走公开方法必 40303），
     * 一切失败由调用方兜底（AnalysisService 只告警），不影响分析完成状态。
     */
    @Transactional
    public void autoSyncAfterAnalysis(String projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getSourcePath() == null || project.getSourcePath().isBlank()
                || !gitDiffService.isGitRepo(project.getSourcePath())) {
            return;   // 无源码/非 Git 仓库：维持手动兜底路径
        }
        CodeAnalysis analysis = latestAnalysis(projectId);
        if (analysis == null) {
            log.warn("[Scope] 自动识别跳过 {}: 缺少代码分析结果", projectId);
            return;
        }
        // 重建语义：删除旧范围（含已确认）后重新识别——重新分析即刷新范围
        List<ScopeDefinition> existing =
                definitionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        for (ScopeDefinition old : existing) {
            itemRepository.deleteByDefinitionId(old.getId());
            definitionRepository.delete(old);
        }
        String baseline = gitDiffService.detectDefaultBaseline(project.getSourcePath());
        if (baseline == null || baseline.isBlank()) {
            log.warn("[Scope] 自动识别跳过 {}: 未探测到默认基线（无 origin/HEAD 且无 master/main），请手动创建范围",
                    projectId);
            return;
        }
        ScopeDefinition def = new ScopeDefinition();
        def.setId(newId());
        def.setProjectId(projectId);
        def.setName(AUTO_SCOPE_NAME);
        def.setBaselineRef(baseline);
        def.setStatus(ScopeDefinition.STATUS_DRAFT);
        definitionRepository.save(def);
        try {
            runIdentification(def, project, analysis);
        } catch (BusinessException e) {
            // 典型：基线与 HEAD 无差异——不建范围，引导走手动兜底
            log.info("[Scope] 自动识别未产出范围 {}: {}", projectId, e.getMessage());
            definitionRepository.delete(def);
            return;
        }
        long count = itemRepository.countByDefinitionId(def.getId());
        if (count > 0) {
            def.setStatus(ScopeDefinition.STATUS_CONFIRMED);
            definitionRepository.save(def);
            log.info("[Scope] 自动识别完成 {}: 范围 {} 已锁定（{} 条，基线 {}）",
                    projectId, def.getId(), count, baseline);
        } else {
            log.warn("[Scope] 自动识别 0 条 {}: 保留草稿待手动补充（查看 → 本期范围）", projectId);
        }
    }

    /** 识别流水线：diff → 接口映射 → 状态机影响面 → LLM 补充。返回新建条目数 */
    private int runIdentification(ScopeDefinition def, Project project, CodeAnalysis analysis) {
        List<Map<String, String>> changed = gitDiffService.diffFiles(
                project.getSourcePath(), def.getBaselineRef(), def.getHeadRef());
        if (changed.isEmpty()) {
            // v8.9.8: 基线与 HEAD 无差异——典型场景是当前 HEAD 就在基线主干上（未拉迭代分支），
            // 静默建空范围会让用户误以为本期无变更，直接报错引导
            throw BusinessException.invalidState("基线「" + def.getBaselineRef()
                    + "」与当前代码无差异：请确认已切换到迭代分支/代码已更新，或手动指定更早的基线");
        }
        try {
            def.setChangedFiles(objectMapper.writeValueAsString(changed));
        } catch (Exception ignored) {
            def.setChangedFiles("[]");
        }
        definitionRepository.save(def);

        Set<String> modifiedPaths = new LinkedHashSet<>();
        Set<String> addedPaths = new LinkedHashSet<>();
        for (Map<String, String> cf : changed) {
            String status = cf.getOrDefault("status", "M");
            String path = cf.getOrDefault("path", "");
            if ("D".equals(status) || path.isEmpty()) {
                continue;  // 删除的文件不会出现在 HEAD 分析结果中
            }
            if ("A".equals(status)) {
                addedPaths.add(path);
            } else if (!addedPaths.contains(path)) {
                modifiedPaths.add(path);
            }
        }

        List<ScopeItem> items = new ArrayList<>();
        Map<String, Object> backendResult = parseBackendResult(analysis);

        // [1] 文件→接口映射
        List<Map<String, Object>> endpoints = castList(backendResult.get("endpoints"));
        for (Map<String, Object> ep : endpoints) {
            String file = str(ep.get("file"));
            String method = str(ep.get("method")).toUpperCase();
            String path = str(ep.get("path"));
            if (method.isBlank() || path.isBlank() || file.isBlank()) {
                continue;
            }
            String kind = matchKind(file, addedPaths, modifiedPaths);
            if (kind == null) {
                continue;
            }
            items.add(buildItem(def.getId(), ScopeItem.TYPE_ENDPOINT,
                    method + " " + path, kind, ScopeItem.ORIGIN_AUTO_DIFF, "命中文件: " + file));
        }

        // [2] 状态机影响面（证据 from/to 匹配 + 证据文件 ∈ 变更集）
        List<StateMachine> machines = stateMachineRepository.findByProjectId(project.getId());
        List<Map<String, Object>> evidence = castList(backendResult.get("stateTransitions"));
        for (StateMachine sm : machines) {
            Set<String> hitDesc = smAffectedFiles(sm, evidence, addedPaths, modifiedPaths);
            if (!hitDesc.isEmpty()) {
                items.add(buildItem(def.getId(), ScopeItem.TYPE_STATE_MACHINE, sm.getId(),
                        ScopeItem.KIND_AFFECTED, ScopeItem.ORIGIN_AUTO_DIFF,
                        joinLimit(hitDesc, 3)));
            }
        }

        // [3] LLM 补充映射
        Set<String> existingRefs = existingEndpointKeys(items);
        List<Map<String, Object>> suggestions =
                scopeMappingAgent.map(project.getPrdContent(), endpoints);
        for (Map<String, Object> s : suggestions) {
            String key = str(s.get("method")).toUpperCase() + " " + str(s.get("path"));
            if (existingRefs.contains(key)) {
                continue;
            }
            existingRefs.add(key);
            items.add(buildItem(def.getId(), ScopeItem.TYPE_ENDPOINT, key,
                    ScopeItem.KIND_AFFECTED, ScopeItem.ORIGIN_LLM_MAPPED,
                    "LLM 映射: " + str(s.get("reason"))));
        }

        // [4] v8.9.8: 前端变更映射——diff 本就含前端文件，此前只消费 backendResult 导致
        // 纯前端迭代识别为 0 条；现按路由文件命中出 PAGE 条目，未命中路由的前端文件聚合为一条兜底项
        Map<String, Object> frontendResult = parseFrontendResult(analysis);
        items.addAll(mapFrontendChanges(def.getId(), frontendResult, addedPaths, modifiedPaths));

        itemRepository.saveAll(items);
        return items.size();
    }

    /** 前端变更 → PAGE 条目：路由文件命中逐路由出项，其余前端文件聚合一条（避免逐文件噪声） */
    private List<ScopeItem> mapFrontendChanges(String definitionId, Map<String, Object> frontendResult,
                                               Set<String> addedPaths, Set<String> modifiedPaths) {
        List<ScopeItem> out = new ArrayList<>();
        Set<String> matchedFiles = new LinkedHashSet<>();
        Set<String> addedRoutePaths = new LinkedHashSet<>();
        for (Map<String, Object> route : castList(frontendResult.get("routes"))) {
            String file = str(route.get("file"));
            String kind = matchKind(file, addedPaths, modifiedPaths);
            if (kind == null) {
                continue;
            }
            matchedFiles.add(file);
            String routePath = str(route.get("path"));
            if (routePath.isEmpty() || !addedRoutePaths.add(routePath)) {
                continue;   // 同一路由可能被多个文件定义，去重后只出一条（kind 取首个命中）
            }
            String name = str(route.get("name"));
            out.add(buildItem(definitionId, ScopeItem.TYPE_PAGE, routePath,
                    kind, ScopeItem.ORIGIN_AUTO_DIFF,
                    "命中路由文件: " + file + (name.isEmpty() ? "" : "（" + name + "）")));
            if (out.size() >= PAGE_ITEMS_CAP) {
                return out;
            }
        }
        // 未命中任何路由的前端变更文件（页面组件/样式等）：聚合一条，保证纯前端迭代范围非空可确认；
        // 组件级细节由生成侧 RAG/componentSummaries 承接，范围模型只到页面粒度
        int unmatched = 0;
        for (String p : addedPaths) {
            if (isFrontendFile(p, matchedFiles)) {
                unmatched++;
            }
        }
        for (String p : modifiedPaths) {
            if (isFrontendFile(p, matchedFiles)) {
                unmatched++;
            }
        }
        if (unmatched > 0 && out.size() < PAGE_ITEMS_CAP) {
            out.add(buildItem(definitionId, ScopeItem.TYPE_PAGE, "frontend-files",
                    ScopeItem.KIND_MODIFIED, ScopeItem.ORIGIN_AUTO_DIFF,
                    unmatched + " 个前端变更文件未匹配到路由定义"));
        }
        return out;
    }

    private boolean isFrontendFile(String path, Set<String> matchedFiles) {
        String lower = path.toLowerCase();
        for (String ext : FRONTEND_FILE_EXTS) {
            if (lower.endsWith(ext)) {
                for (String mf : matchedFiles) {
                    if (pathMatch(mf, path)) {
                        return false;   // 已被路由命中消费，不重复计入兜底项
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** 分析结果时效性：分析创建时间 < HEAD 提交时间 → 映射表可能旧于代码 */
    private boolean isAnalysisStale(String projectId, Project project) {
        CodeAnalysis analysis = latestAnalysis(projectId);
        if (analysis == null || analysis.getCreatedAt() == null) {
            return false;   // 无分析时映射本就为空，谈不上过期（另有前置提示）
        }
        Long headEpoch = gitDiffService.headCommitEpoch(project.getSourcePath());
        if (headEpoch == null) {
            return false;
        }
        long analysisEpoch = analysis.getCreatedAt()
                .atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        return analysisEpoch < headEpoch;
    }

    /** 归一化后缀双向匹配（兼容 sourcePath 指向仓库子目录） */
    private String matchKind(String file, Set<String> addedPaths, Set<String> modifiedPaths) {
        for (String p : addedPaths) {
            if (pathMatch(file, p)) {
                return ScopeItem.KIND_ADDED;
            }
        }
        for (String p : modifiedPaths) {
            if (pathMatch(file, p)) {
                return ScopeItem.KIND_MODIFIED;
            }
        }
        return null;
    }

    static boolean pathMatch(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        String x = a.trim().replace('\\', '/');
        String y = b.trim().replace('\\', '/');
        return x.equals(y)
                || x.endsWith("/" + y)
                || y.endsWith("/" + x);
    }

    /**
     * 复用 StateMachineAgent.applyEvidence 的归一化匹配思路：
     * SM 的 transition(from,to) 与证据 {field,from,to,method,file} 匹配，
     * 命中的证据文件在变更集内 → 返回描述片段集合。
     */
    private Set<String> smAffectedFiles(StateMachine sm, List<Map<String, Object>> evidence,
                                        Set<String> addedPaths, Set<String> modifiedPaths) {
        Set<String> hits = new LinkedHashSet<>();
        if (evidence == null || evidence.isEmpty()) {
            return hits;
        }
        for (Map<String, Object> t : JsonHelper.parseListMap(sm.getTransitions())) {
            String from = normalizeStateCode(str(t.get("from")));
            String to = normalizeStateCode(str(t.get("to")));
            if (to.isEmpty()) {
                continue;
            }
            for (Map<String, Object> ev : evidence) {
                String evTo = normalizeStateCode(str(ev.get("to")));
                if (evTo.isEmpty() || !evTo.equals(to)) {
                    continue;
                }
                String evFrom = normalizeStateCode(str(ev.get("from")));
                if (!evFrom.equals("*") && !evFrom.equals(from)) {
                    continue;
                }
                String evFile = str(ev.get("file"));
                String kind = matchKind(evFile, addedPaths, modifiedPaths);
                if (kind != null) {
                    hits.add(to + " ← " + evFile + "（" + kind + "）");
                    break;
                }
            }
        }
        return hits;
    }

    private String normalizeStateCode(String raw) {
        String s = raw == null ? "" : raw.trim();
        int idx = s.lastIndexOf('.');
        if (idx >= 0 && idx < s.length() - 1) {
            s = s.substring(idx + 1);
        }
        return s.toLowerCase();
    }

    // ==================== 条目操作 ====================

    @Transactional
    public Map<String, Object> addItem(String projectId, String definitionId,
                                       String itemType, String itemRef, String changeKind, String note) {
        projectAccessService.assertOperateAccess(projectId);
        ScopeDefinition def = requireDefinitionOfProject(projectId, definitionId);
        // v9.0: 已确认范围亦允许条目增删——自动锁定后人工剔除噪声条目/补充遗漏（覆盖率分母随之变化）
        validateTypeAndKind(itemType, changeKind);
        if (itemRef == null || itemRef.isBlank()) {
            throw BusinessException.invalidParam("条目引用不能为空");
        }
        ScopeItem item = buildItem(def.getId(), itemType, itemRef.trim(),
                changeKind, ScopeItem.ORIGIN_MANUAL, note == null ? "手动添加" : note.trim());
        itemRepository.save(item);
        return toMap(item);
    }

    @Transactional
    public void removeItem(String projectId, String definitionId, String itemId) {
        projectAccessService.assertOperateAccess(projectId);
        ScopeDefinition def = requireDefinitionOfProject(projectId, definitionId);
        // v9.0: 与 addItem 一致，已确认范围允许删除条目（前端删除时二次确认提示分母变化）
        itemRepository.findById(itemId).ifPresent(item -> {
            if (item.getDefinitionId().equals(def.getId())) {
                itemRepository.delete(item);
            }
        });
    }

    @Transactional
    public Map<String, Object> recompute(String projectId, String definitionId) {
        projectAccessService.assertOperateAccess(projectId);
        ScopeDefinition def = requireDefinitionOfProject(projectId, definitionId);
        assertDraft(def);
        Project project = requireProject(projectId);
        CodeAnalysis analysis = latestAnalysis(projectId);
        if (analysis == null) {
            throw BusinessException.invalidState("缺少代码分析结果，无法重算范围");
        }
        // MANUAL 保留，其余重建
        List<ScopeItem> manual = itemRepository.findByDefinitionIdOrderByItemTypeAscIdAsc(def.getId())
                .stream().filter(i -> ScopeItem.ORIGIN_MANUAL.equals(i.getOrigin())).toList();
        itemRepository.deleteByDefinitionId(def.getId());
        itemRepository.saveAll(manual);
        int created = runIdentification(def, project, analysis);
        log.info("[Scope] 重算 {}: 新增 {} 条（MANUAL 保留 {} 条）", def.getId(), created, manual.size());
        return toMap(requireDefinition(definitionId));
    }

    @Transactional
    public void confirm(String projectId, String definitionId) {
        projectAccessService.assertOperateAccess(projectId);
        ScopeDefinition def = requireDefinitionOfProject(projectId, definitionId);
        assertDraft(def);
        if (itemRepository.countByDefinitionId(def.getId()) == 0) {
            throw BusinessException.invalidParam("范围为空，无法确认；请先识别或手动添加条目");
        }
        def.setStatus(ScopeDefinition.STATUS_CONFIRMED);
        definitionRepository.save(def);
    }

    @Transactional
    public void deleteDefinition(String projectId, String definitionId) {
        projectAccessService.assertOperateAccess(projectId);
        ScopeDefinition def = requireDefinitionOfProject(projectId, definitionId);
        itemRepository.deleteByDefinitionId(def.getId());
        definitionRepository.delete(def);
    }

    // ==================== 内部工具 ====================

    private Project requireProject(String projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
    }

    private CodeAnalysis latestAnalysis(String projectId) {
        List<CodeAnalysis> all = codeAnalysisRepository.findAllByProjectId(projectId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private Map<String, Object> parseBackendResult(CodeAnalysis analysis) {
        // v8.9.8: 显式判空——此前靠 try 块捕 NPE 兜底，隐晦且日志误导（“解析失败”实为无分析）
        if (analysis == null || analysis.getBackendResult() == null) {
            return Map.of();
        }
        try {
            Map<String, Object> map = JsonHelper.parseMap(analysis.getBackendResult());
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            log.warn("[Scope] 解析 backendResult 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> parseFrontendResult(CodeAnalysis analysis) {
        if (analysis == null || analysis.getFrontendResult() == null) {
            return Map.of();
        }
        try {
            Map<String, Object> map = JsonHelper.parseMap(analysis.getFrontendResult());
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            log.warn("[Scope] 解析 frontendResult 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private Set<String> existingEndpointKeys(List<ScopeItem> items) {
        Set<String> keys = new HashSet<>();
        for (ScopeItem item : items) {
            if (ScopeItem.TYPE_ENDPOINT.equals(item.getItemType())) {
                keys.add(item.getItemRef());
            }
        }
        return keys;
    }

    private ScopeItem buildItem(String definitionId, String type, String ref,
                                String kind, String origin, String note) {
        ScopeItem item = new ScopeItem();
        item.setId(newId());
        item.setDefinitionId(definitionId);
        item.setItemType(type);
        item.setItemRef(ref);
        item.setChangeKind(kind);
        item.setOrigin(origin);
        item.setNote(note == null ? "" : note);
        item.setCreatedAt(LocalDateTime.now());
        return item;
    }

    private void validateTypeAndKind(String itemType, String changeKind) {
        boolean validType = ScopeItem.TYPE_ENDPOINT.equals(itemType)
                || ScopeItem.TYPE_STATE_MACHINE.equals(itemType)
                || ScopeItem.TYPE_PAGE.equals(itemType);
        boolean validKind = ScopeItem.KIND_ADDED.equals(changeKind)
                || ScopeItem.KIND_MODIFIED.equals(changeKind)
                || ScopeItem.KIND_AFFECTED.equals(changeKind);
        if (!validType) {
            throw BusinessException.invalidParam("条目类型不合法: " + itemType);
        }
        if (!validKind) {
            throw BusinessException.invalidParam("变更类型不合法: " + changeKind);
        }
    }

    private void assertDraft(ScopeDefinition def) {
        if (!ScopeDefinition.STATUS_DRAFT.equals(def.getStatus())) {
            throw BusinessException.invalidState("已确认范围仅支持条目增删，不允许重算或重复确认");
        }
    }

    private ScopeDefinition requireDefinition(String id) {
        return definitionRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("范围定义不存在: " + id));
    }

    private ScopeDefinition requireDefinitionOfProject(String projectId, String definitionId) {
        ScopeDefinition def = requireDefinition(definitionId);
        if (!def.getProjectId().equals(projectId)) {
            throw BusinessException.notFound("范围定义不存在: " + definitionId);
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String joinLimit(Set<String> values, int limit) {
        List<String> list = new ArrayList<>(values);
        String joined = String.join("; ", list.subList(0, Math.min(limit, list.size())));
        return list.size() > limit ? joined + " 等 " + list.size() + " 组命中" : joined;
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private Map<String, Object> toMap(ScopeDefinition def) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", def.getId());
        r.put("projectId", def.getProjectId());
        r.put("name", def.getName());
        r.put("baselineRef", def.getBaselineRef());
        r.put("headRef", def.getHeadRef());
        r.put("status", def.getStatus());
        return r;
    }

    private Map<String, Object> toMap(ScopeItem item) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", item.getId());
        r.put("definitionId", item.getDefinitionId());
        r.put("itemType", item.getItemType());
        r.put("itemRef", item.getItemRef());
        r.put("changeKind", item.getChangeKind());
        r.put("origin", item.getOrigin());
        r.put("note", item.getNote());
        return r;
    }
}
