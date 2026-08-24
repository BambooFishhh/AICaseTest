package com.testagent.service;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.ScopeDefinition;
import com.testagent.entity.ScopeItem;
import com.testagent.entity.StateMachine;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ScopeDefinitionRepository;
import com.testagent.repository.ScopeItemRepository;
import com.testagent.repository.StateMachineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v8.2: 状态机切片与 setup 路径推导——把已确认范围转成生成侧可消费的 ScopeSlice。
 * 目标集合 = 范围内接口 + 本期变更转换；历史转换仅作为图边/上下文。
 */
@Service
public class ScopeSlicingService {

    private static final Logger log = LoggerFactory.getLogger(ScopeSlicingService.class);

    public static final String ROLE_SPRINT = "sprint_target";
    public static final String ROLE_HISTORICAL = "historical_context";

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private ScopeDefinitionRepository definitionRepository;
    @Autowired
    private ScopeItemRepository itemRepository;
    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;
    @Autowired
    private StateMachineRepository stateMachineRepository;

    /**
     * 生成侧切片。无已确认范围时返回 EMPTY（调用方按纯 PRD 项目处理）。
     */
    public ScopeSlice loadForGeneration(String projectId) {
        ScopeDefinition def = latestConfirmed(projectId);
        if (def == null) {
            return ScopeSlice.EMPTY;
        }
        List<ScopeItem> items = itemRepository.findByDefinitionIdOrderByItemTypeAscIdAsc(def.getId());

        Set<String> targetEndpointIds = new LinkedHashSet<>();
        Set<String> targetSmIds = new LinkedHashSet<>();
        for (ScopeItem item : items) {
            if (ScopeItem.TYPE_ENDPOINT.equals(item.getItemType())) {
                targetEndpointIds.add(normalizeEndpointId(item.getItemRef()));
            } else if (ScopeItem.TYPE_STATE_MACHINE.equals(item.getItemType())) {
                targetSmIds.add(item.getItemRef());
            }
        }

        CodeAnalysis analysis = latestAnalysis(projectId);
        List<StateMachine> machines = stateMachineRepository.findByProjectId(projectId);
        Map<String, Object> backendResult = parseBackend(analysis);
        List<Map<String, Object>> endpoints = castList(backendResult.get("endpoints"));
        List<Map<String, Object>> evidence = castList(backendResult.get("stateTransitions"));
        Set<String> changedFiles = parseChangedFiles(def);

        // 目标接口完整详情（prompt 注入用）
        List<Map<String, Object>> endpointDetails = new ArrayList<>();
        for (Map<String, Object> ep : endpoints) {
            String id = normalizeEndpointId(str(ep.get("method")).toUpperCase() + " " + str(ep.get("path")));
            if (targetEndpointIds.contains(id)) {
                endpointDetails.add(ep);
            }
        }

        // 状态机二分：sprint / historical
        Map<String, List<Map<String, Object>>> sprintBySm = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> historicalBySm = new LinkedHashMap<>();
        List<Map<String, Object>> setupHints = new ArrayList<>();
        for (StateMachine sm : machines) {
            boolean inScope = targetSmIds.contains(sm.getId());
            if (!inScope) {
                continue;   // 范围外 SM 整体不进生成上下文
            }
            List<Map<String, Object>> transitions = JsonHelper.parseListMap(sm.getTransitions());
            List<Map<String, Object>> sprint = new ArrayList<>();
            List<Map<String, Object>> historical = new ArrayList<>();
            for (Map<String, Object> t : transitions) {
                if (isSprintTransition(t, evidence, changedFiles)) {
                    sprint.add(t);
                } else {
                    historical.add(t);
                }
            }
            sprintBySm.put(sm.getId(), sprint);
            historicalBySm.put(sm.getId(), historical);
            setupHints.addAll(deriveSetupHints(sm, transitions, sprint));
        }

        log.info("[Scope] 切片完成 {}: 目标接口 {}, 状态机 {}（sprint 转换累计 {}）",
                def.getId(), endpointDetails.size(), sprintBySm.size(),
                sprintBySm.values().stream().mapToInt(List::size).sum());

        return new ScopeSlice(def.getId(), def.getName(), def.getBaselineRef(),
                targetEndpointIds, endpointDetails, sprintBySm, historicalBySm, setupHints);
    }

    /**
     * v8.2: 前置校验共用逻辑——代码驱动项目必须有已确认范围。
     */
    public void requireConfirmedScopeIfCodeDriven(Project project) {
        boolean codeDriven = project.getSourcePath() != null && !project.getSourcePath().isBlank()
                && !codeAnalysisRepository.findAllByProjectId(project.getId()).isEmpty();
        if (codeDriven && latestConfirmed(project.getId()) == null) {
            throw com.testagent.common.BusinessException.invalidState(
                    "请先创建并确认本期范围（项目详情 → 本期范围），再触发用例生成");
        }
    }

    // ==================== 内部实现 ====================

    private ScopeDefinition latestConfirmed(String projectId) {
        List<ScopeDefinition> all = definitionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        for (ScopeDefinition def : all) {
            if (ScopeDefinition.STATUS_CONFIRMED.equals(def.getStatus())) {
                return def;
            }
        }
        return null;
    }

    private CodeAnalysis latestAnalysis(String projectId) {
        List<CodeAnalysis> all = codeAnalysisRepository.findAllByProjectId(projectId);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** 证据匹配判定本期转换：命中证据的 file ∈ 变更文件集 */
    private boolean isSprintTransition(Map<String, Object> transition,
                                       List<Map<String, Object>> evidence,
                                       Set<String> changedFiles) {
        if (evidence == null || evidence.isEmpty() || changedFiles.isEmpty()) {
            return false;
        }
        String from = normalizeStateCode(str(transition.get("from")));
        String to = normalizeStateCode(str(transition.get("to")));
        if (to.isEmpty()) {
            return false;
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
            for (String cf : changedFiles) {
                if (pathMatch(evFile, cf)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * BFS 推导"初始态 → 目标转换 from 状态"的最短路径（排除目标转换本身），
     * 输出 trigger 文案骨架供 LLM 物化为 setup 步骤。
     */
    private List<Map<String, Object>> deriveSetupHints(StateMachine sm,
                                                       List<Map<String, Object>> transitions,
                                                       List<Map<String, Object>> sprintTransitions) {
        List<Map<String, Object>> hints = new ArrayList<>();
        if (sprintTransitions.isEmpty() || transitions.size() < 2) {
            return hints;
        }
        Set<String> initialStates = initialStates(sm, transitions);
        Map<String, String> stateNames = stateNameMap(sm);

        for (Map<String, Object> target : sprintTransitions) {
            String fromKey = normalizeStateCode(str(target.get("from")));
            if (fromKey.isEmpty() || initialStates.contains(fromKey)) {
                continue;   // 源状态即初始态，无需准备
            }
            List<Map<String, Object>> path = bfsPath(transitions, target, initialStates, fromKey);
            if (path == null) {
                continue;
            }
            List<String> steps = new ArrayList<>();
            for (Map<String, Object> edge : path) {
                String trigger = str(edge.get("trigger"));
                String toName = stateNames.getOrDefault(
                        normalizeStateCode(str(edge.get("to"))), str(edge.get("to")));
                steps.add(trigger.isBlank()
                        ? "将状态置为「" + toName + "」"
                        : "执行【" + trigger + "】使状态到达「" + toName + "」");
            }
            Map<String, Object> hint = new LinkedHashMap<>();
            hint.put("transition", str(target.get("from")) + "->" + str(target.get("to")));
            hint.put("stateMachine", sm.getName());
            hint.put("steps", steps);
            hints.add(hint);
        }
        return hints;
    }

    /** 最短路径优先；同长时含历史边更多的路径优先（setup 应尽量走既有流程） */
    private List<Map<String, Object>> bfsPath(List<Map<String, Object>> transitions,
                                              Map<String, Object> target,
                                              Set<String> initialStates,
                                              String goal) {
        String excludeFrom = normalizeStateCode(str(target.get("from")));
        String excludeTo = normalizeStateCode(str(target.get("to")));

        Deque<String> queue = new ArrayDeque<>(initialStates);
        Map<String, Edge> prev = new HashMap<>();
        Set<String> visited = new HashSet<>(initialStates);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (Map<String, Object> t : transitions) {
                String from = normalizeStateCode(str(t.get("from")));
                String to = normalizeStateCode(str(t.get("to")));
                if (!cur.equals(from) || to.isEmpty() || visited.contains(to)) {
                    continue;
                }
                // 排除目标转换自身（不能靠目标转换自己到达自己的源状态）
                if (from.equals(excludeFrom) && to.equals(excludeTo)) {
                    continue;
                }
                visited.add(to);
                prev.put(to, new Edge(t, from));
                if (to.equals(goal)) {
                    return buildPath(prev, goal);
                }
                queue.add(to);
            }
        }
        return null;
    }

    private List<Map<String, Object>> buildPath(Map<String, Edge> prev, String goal) {
        List<Map<String, Object>> path = new ArrayList<>();
        String cur = goal;
        while (prev.containsKey(cur)) {
            Edge edge = prev.get(cur);
            path.add(0, edge.transition);
            cur = edge.from;
        }
        return path;
    }

    private record Edge(Map<String, Object> transition, String from) {}

    private Set<String> initialStates(StateMachine sm, List<Map<String, Object>> transitions) {
        Set<String> initials = new HashSet<>();
        for (Map<String, Object> s : JsonHelper.parseListMap(sm.getStates())) {
            boolean flag = Boolean.parseBoolean(str(s.getOrDefault("is_initial", "false")))
                    || Boolean.parseBoolean(str(s.getOrDefault("initial", "false")));
            if (flag) {
                String code = normalizeStateCode(firstNonBlank(s.get("code"), s.get("name"), s.get("value")));
                if (!code.isEmpty()) {
                    initials.add(code);
                }
            }
        }
        if (initials.isEmpty()) {
            // 兜底：未被任何转换的 to 引用的状态视为初始态
            Set<String> targets = new HashSet<>();
            for (Map<String, Object> t : transitions) {
                targets.add(normalizeStateCode(str(t.get("to"))));
            }
            for (Map<String, Object> t : transitions) {
                String from = normalizeStateCode(str(t.get("from")));
                if (!from.isEmpty() && !targets.contains(from)) {
                    initials.add(from);
                }
            }
        }
        return initials;
    }

    private Map<String, String> stateNameMap(StateMachine sm) {
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> s : JsonHelper.parseListMap(sm.getStates())) {
            String code = normalizeStateCode(firstNonBlank(s.get("code"), s.get("name"), s.get("value")));
            String name = firstNonBlank(s.get("name"), s.get("code"), s.get("value"));
            if (!code.isEmpty() && !name.isEmpty()) {
                names.putIfAbsent(code, name);
            }
        }
        return names;
    }

    public static String normalizeStateCode(String raw) {
        String s = raw == null ? "" : raw.trim();
        int idx = s.lastIndexOf('.');
        if (idx >= 0 && idx < s.length() - 1) {
            s = s.substring(idx + 1);
        }
        return s.toLowerCase();
    }

    /** v8.3: 转换集合 → 归一化 "from->to" 键集（切片分类与覆盖矩阵共用的唯一口径） */
    public static Set<String> sprintTransitionKeys(List<Map<String, Object>> transitions) {
        Set<String> keys = new HashSet<>();
        if (transitions != null) {
            for (Map<String, Object> t : transitions) {
                String from = normalizeStateCode(t.get("from") == null ? "" : String.valueOf(t.get("from")).trim());
                String to = normalizeStateCode(t.get("to") == null ? "" : String.valueOf(t.get("to")).trim());
                keys.add(from + "->" + to);
            }
        }
        return keys;
    }

    static String normalizeEndpointId(String ref) {
        if (ref == null) {
            return "";
        }
        return ref.trim().replaceAll("\\s+", " ").toUpperCase();
    }

    static boolean pathMatch(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        String x = a.trim().replace('\\', '/');
        String y = b.trim().replace('\\', '/');
        return x.equals(y) || x.endsWith("/" + y) || y.endsWith("/" + x);
    }

    private Set<String> parseChangedFiles(ScopeDefinition def) {
        Set<String> files = new HashSet<>();
        try {
            var root = objectMapper.readTree(def.getChangedFiles() == null ? "[]" : def.getChangedFiles());
            if (root.isArray()) {
                for (var node : root) {
                    String p = node.path("path").asText("");
                    if (!p.isBlank()) {
                        files.add(p);
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败按空集合处理
        }
        return files;
    }

    private Map<String, Object> parseBackend(CodeAnalysis analysis) {
        if (analysis == null) {
            return Map.of();
        }
        try {
            Map<String, Object> map = JsonHelper.parseMap(analysis.getBackendResult());
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
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

    private String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    // ==================== 切片载体 ====================

    public record ScopeSlice(String definitionId, String name, String baselineRef,
                             Set<String> targetEndpointIds,
                             List<Map<String, Object>> targetEndpointsDetail,
                             Map<String, List<Map<String, Object>>> sprintTransitionsBySmId,
                             Map<String, List<Map<String, Object>>> historicalTransitionsBySmId,
                             List<Map<String, Object>> setupHints) {

        public static final ScopeSlice EMPTY =
                new ScopeSlice(null, null, null, Set.of(), List.of(),
                        Map.of(), Map.of(), List.of());

        public boolean isEmpty() {
            return definitionId == null;
        }
    }
}
