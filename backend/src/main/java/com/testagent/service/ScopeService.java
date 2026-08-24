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
 */
@Service
public class ScopeService {

    private static final Logger log = LoggerFactory.getLogger(ScopeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

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
        return refs;
    }

    // ==================== 创建草稿（识别流水线） ====================

    @Transactional
    public Map<String, Object> createDraft(String projectId, String name, String baselineRef) {
        projectAccessService.assertOperateAccess(projectId);
        if (name == null || name.isBlank()) {
            throw BusinessException.invalidParam("范围名称不能为空");
        }
        if (baselineRef == null || baselineRef.isBlank()) {
            throw BusinessException.invalidParam("基线引用不能为空");
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
        Project project = requireProject(projectId);
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

        ScopeDefinition def = new ScopeDefinition();
        def.setId(newId());
        def.setProjectId(projectId);
        def.setName(name.trim());
        def.setBaselineRef(baselineRef.trim());
        def.setStatus(ScopeDefinition.STATUS_DRAFT);
        definitionRepository.save(def);

        if (!gitRepo) {
            log.info("[Scope] 草稿 {} 创建（非 Git 仓库，跳过自动识别，等待手动添加条目）", def.getId());
            Map<String, Object> r = toMap(def);
            r.put("autoIdentified", false);
            return r;
        }

        int created = runIdentification(def, project, analysis);
        log.info("[Scope] 草稿 {} 创建完成: {} 条范围项", def.getId(), created);
        Map<String, Object> r = toMap(def);
        r.put("autoIdentified", true);
        return r;
    }

    /** 识别流水线：diff → 接口映射 → 状态机影响面 → LLM 补充。返回新建条目数 */
    private int runIdentification(ScopeDefinition def, Project project, CodeAnalysis analysis) {
        List<Map<String, String>> changed = gitDiffService.diffFiles(
                project.getSourcePath(), def.getBaselineRef(), def.getHeadRef());
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

        itemRepository.saveAll(items);
        return items.size();
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
        assertDraft(def);
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
        assertDraft(def);
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
        try {
            Map<String, Object> map = JsonHelper.parseMap(analysis.getBackendResult());
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            log.warn("[Scope] 解析 backendResult 失败: {}", e.getMessage());
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
                || ScopeItem.TYPE_STATE_MACHINE.equals(itemType);
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
            throw BusinessException.invalidState("已确认的范围为只读状态，不允许修改");
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
