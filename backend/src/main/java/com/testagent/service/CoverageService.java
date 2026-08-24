package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.ScopeItem;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ScopeItemRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private StateMachineRepository stateMachineRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;

    // v8.3: 覆盖率单一口径=已确认本期范围（全量口径彻底移除）
    @Autowired
    private ScopeSlicingService scopeSlicingService;

    @Autowired
    private ScopeItemRepository scopeItemRepository;

    /**
     * v8.3: 覆盖矩阵——单一"本期范围"口径。
     * 分母：范围内状态机的本期目标转换 + 范围内目标接口；历史转换仅展示（inScope=false）不参与统计。
     * 无已确认范围时返回引导态（scoped=false），不再输出全量数字。
     */
    public Map<String, Object> getCoverageMatrix(String projectId) {
        ScopeSlicingService.ScopeSlice slice = scopeSlicingService.loadForGeneration(projectId);
        if (slice.isEmpty()) {
            return unscopedResult();
        }
        List<TestCase> testCases = testCaseRepository.findByProjectId(projectId);

        // v7.2(R8): 每条用例只解析一次 JSON
        Map<String, Set<String>> refTransitionsByCase = new LinkedHashMap<>();
        Map<String, Set<String>> smRefTransitionsByCase = new LinkedHashMap<>();
        for (TestCase tc : testCases) {
            refTransitionsByCase.put(tc.getId(), parseCoverageRefTransitions(tc));
            if (isExecuted(tc) && tc.getStateMachineRef() != null) {
                smRefTransitionsByCase.put(tc.getId(), parseSmRefTransitionIds(tc));
            }
        }

        List<Map<String, Object>> smList = new ArrayList<>();
        int totalTransitions = 0;
        int coveredTransitions = 0;
        int plannedCoveredTransitions = 0;
        int executedCoveredTransitions = 0;

        List<StateMachine> machines = stateMachineRepository.findByProjectId(projectId);
        for (StateMachine sm : machines) {
            List<Map<String, Object>> sprintTransitions = slice.sprintTransitionsBySmId().get(sm.getId());
            if (sprintTransitions == null) {
                continue;   // v8.3: 范围外状态机整体不进矩阵
            }
            Set<String> sprintKeys = ScopeSlicingService.sprintTransitionKeys(sprintTransitions);

            Map<String, Object> smMap = new LinkedHashMap<>();
            smMap.put("id", sm.getId());
            smMap.put("name", sm.getName());

            List<Map<String, Object>> transitions = parseTransitions(sm);
            for (Map<String, Object> tran : transitions) {
                String rawKey = rawTransitionKey(tran);
                boolean inScope = sprintKeys.contains(
                        ScopeSlicingService.normalizeStateCode(str(tran.get("from"))) + "->"
                                + ScopeSlicingService.normalizeStateCode(str(tran.get("to"))));
                tran.put("inScope", inScope);

                if (!inScope) {
                    // 历史上下文转换：只展示，不进分子分母
                    tran.put("covered", false);
                    tran.put("testCaseIds", new ArrayList<>());
                    continue;
                }

                List<String> plannedIds = new ArrayList<>();
                List<String> executedIds = new ArrayList<>();
                List<String> legacyCoveringIds = new ArrayList<>();
                for (TestCase tc : testCases) {
                    boolean planned = refTransitionsByCase.get(tc.getId()).contains(rawKey);
                    boolean smRefFallback =
                            smRefTransitionsByCase.getOrDefault(tc.getId(), Set.of()).contains(rawKey);
                    boolean executed = (planned && isExecuted(tc)) || smRefFallback;
                    if (planned) {
                        plannedIds.add(tc.getId());
                    }
                    if (executed) {
                        executedIds.add(tc.getId());
                    }
                    if (planned || smRefFallback) {
                        legacyCoveringIds.add(tc.getId());
                    }
                }

                tran.put("covered", !legacyCoveringIds.isEmpty());
                tran.put("testCaseIds", legacyCoveringIds);
                tran.put("planned", !plannedIds.isEmpty());
                tran.put("plannedCaseIds", plannedIds);
                tran.put("executed", !executedIds.isEmpty());
                tran.put("executedCaseIds", executedIds);
                totalTransitions++;
                if (!legacyCoveringIds.isEmpty()) coveredTransitions++;
                if (!plannedIds.isEmpty()) plannedCoveredTransitions++;
                if (!executedIds.isEmpty()) executedCoveredTransitions++;
            }

            smMap.put("transitions", transitions);
            smList.add(smMap);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTransitions", totalTransitions);
        summary.put("coveredTransitions", coveredTransitions);
        summary.put("rate", totalTransitions == 0 ? 0.0 : (double) coveredTransitions / totalTransitions);
        summary.put("plannedCoveredTransitions", plannedCoveredTransitions);
        summary.put("executedCoveredTransitions", executedCoveredTransitions);
        summary.put("plannedRate", totalTransitions == 0 ? 0.0 : (double) plannedCoveredTransitions / totalTransitions);
        summary.put("executedRate", totalTransitions == 0 ? 0.0 : (double) executedCoveredTransitions / totalTransitions);

        // 接口覆盖：分母=本期目标接口（详情来自切片，含 description 兜底逻辑在切片侧已完成筛选）
        Set<String> totalEndpoints = new HashSet<>();
        for (Map<String, Object> ep : slice.targetEndpointsDetail()) {
            totalEndpoints.add(normalizeEndpointKey(ep.get("method") + " " + ep.get("path")));
        }
        Set<String> coveredEndpoints = new HashSet<>();
        for (TestCase tc : testCases) {
            coveredEndpoints.addAll(parseCoverageRefEndpoints(tc));
            if (!isExecuted(tc)) {
                continue;
            }
            for (Map<String, Object> ep : com.testagent.dto.JsonHelper.parseListMap(tc.getApiEndpoints())) {
                String method = String.valueOf(ep.getOrDefault("method", ""));
                String path = String.valueOf(ep.getOrDefault("path", ""));
                coveredEndpoints.add(normalizeEndpointKey(method + " " + path));
            }
        }
        int apiCovered = 0;
        for (String id : totalEndpoints) {
            if (coveredEndpoints.contains(id)) {
                apiCovered++;
            }
        }
        Map<String, Object> apiCov = new LinkedHashMap<>();
        apiCov.put("totalEndpoints", totalEndpoints.size());
        apiCov.put("coveredEndpoints", apiCovered);
        apiCov.put("uncoveredEndpoints", totalEndpoints.size() - apiCovered);
        apiCov.put("rate", totalEndpoints.isEmpty() ? 0.0 : (double) apiCovered / totalEndpoints.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scoped", true);
        Map<String, Object> scopeMeta = new LinkedHashMap<>();
        scopeMeta.put("definitionId", slice.definitionId());
        scopeMeta.put("name", slice.name());
        scopeMeta.put("baselineRef", slice.baselineRef());
        result.put("scope", scopeMeta);
        result.put("stateMachines", smList);
        result.put("summary", summary);
        result.put("apiEndpoint", apiCov);
        result.put("affectedItems", affectedItems(slice.definitionId()));
        return result;
    }

    /**
     * v7.15(3b)/v8.3: 未覆盖接口清单——分母收敛为本期目标接口。
     */
    public Map<String, Object> uncoveredEndpoints(String projectId) {
        ScopeSlicingService.ScopeSlice slice = scopeSlicingService.loadForGeneration(projectId);
        if (slice.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scoped", false);
            r.put("message", "请先创建并确认本期范围");
            return r;
        }

        List<Map<String, Object>> total = new ArrayList<>(slice.targetEndpointsDetail());

        Set<String> covered = new HashSet<>();
        List<TestCase> cases = testCaseRepository.findByProjectId(projectId);
        for (TestCase tc : cases) {
            covered.addAll(parseCoverageRefEndpoints(tc));
            if (!isExecuted(tc)) {
                continue;
            }
            for (Map<String, Object> ep : com.testagent.dto.JsonHelper.parseListMap(tc.getApiEndpoints())) {
                String method = String.valueOf(ep.getOrDefault("method", ""));
                String path = String.valueOf(ep.getOrDefault("path", ""));
                covered.add(normalizeEndpointKey(method + " " + path));
            }
        }

        List<Map<String, Object>> uncovered = new ArrayList<>();
        int matched = 0;
        for (Map<String, Object> ep : total) {
            String key = normalizeEndpointKey(ep.get("method") + " " + ep.get("path"));
            if (covered.contains(key)) {
                matched++;
            } else {
                uncovered.add(ep);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scoped", true);
        result.put("total", total.size());
        result.put("covered", matched);
        result.put("uncoveredCount", uncovered.size());
        result.put("uncovered", uncovered);
        return result;
    }

    // ==================== 内部工具 ====================

    private Map<String, Object> unscopedResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scoped", false);
        r.put("message", "请先创建并确认本期范围（项目详情 → 本期范围）");
        return r;
    }

    private List<Map<String, Object>> affectedItems(String definitionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScopeItem item : scopeItemRepository.findByDefinitionIdOrderByItemTypeAscIdAsc(definitionId)) {
            if (!ScopeItem.KIND_AFFECTED.equals(item.getChangeKind())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemType", item.getItemType());
            row.put("itemRef", item.getItemRef());
            row.put("origin", item.getOrigin());
            row.put("note", item.getNote());
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> parseTransitions(StateMachine sm) {
        try {
            String json = sm.getTransitions();
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, List.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse transitions for SM {}", sm.getId(), e);
        }
        return new ArrayList<>();
    }

    private String rawTransitionKey(Map<String, Object> tran) {
        String from = tran.get("from") != null ? tran.get("from").toString() : "";
        String to = tran.get("to") != null ? tran.get("to").toString() : "";
        return from + "->" + to;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    // v5.11: 读取 executionHints.coverageRefs.transitionIds 作为“计划覆盖”
    private Set<String> parseCoverageRefTransitions(TestCase tc) {
        Set<String> result = new HashSet<>();
        if (tc.getExecutionHints() == null || tc.getExecutionHints().isBlank()) {
            return result;
        }
        try {
            Map<String, Object> hints = objectMapper.readValue(tc.getExecutionHints(), Map.class);
            Object refs = hints.get("coverageRefs");
            if (refs instanceof Map) {
                Object ids = ((Map<?, ?>) refs).get("transitionIds");
                if (ids instanceof List) {
                    for (Object id : (List<?>) ids) {
                        if (id != null) {
                            result.add(String.valueOf(id));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse coverageRefs for test case {}", tc.getId());
        }
        return result;
    }

    private boolean isExecuted(TestCase tc) {
        String status = tc.getExecutionStatus();
        return "passed".equals(status) || "failed".equals(status);
    }

    private Set<String> parseCoverageRefEndpoints(TestCase tc) {
        Set<String> result = new HashSet<>();
        if (tc.getExecutionHints() == null || tc.getExecutionHints().isBlank()) {
            return result;
        }
        try {
            Map<String, Object> hints = objectMapper.readValue(tc.getExecutionHints(), Map.class);
            Object refs = hints.get("coverageRefs");
            if (refs instanceof Map) {
                Object ids = ((Map<?, ?>) refs).get("endpointIds");
                if (ids instanceof List) {
                    for (Object id : (List<?>) ids) {
                        if (id != null) {
                            result.add(normalizeEndpointKey(String.valueOf(id)));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse coverageRefs endpoints for case {}", tc.getId());
        }
        return result;
    }

    private String normalizeEndpointKey(String id) {
        if (id == null) {
            return "";
        }
        id = id.trim().replaceAll("\\s+", " ");
        int space = id.indexOf(' ');
        if (space > 0) {
            return id.substring(0, space).toUpperCase() + id.substring(space);
        }
        return id;
    }

    // v7.2(R8): 从 stateMachineRef.transitions 提取 "from->to" 集合（原内联兜底逻辑提取为独立方法）
    private Set<String> parseSmRefTransitionIds(TestCase tc) {
        Set<String> result = new HashSet<>();
        try {
            Map<String, Object> ref = objectMapper.readValue(tc.getStateMachineRef(), Map.class);
            Object tcTransitions = ref.get("transitions");
            if (tcTransitions instanceof List) {
                for (Object item : (List<?>) tcTransitions) {
                    if (item instanceof Map) {
                        Map<?, ?> tran = (Map<?, ?>) item;
                        String from = tran.get("from") != null ? tran.get("from").toString() : "";
                        String to = tran.get("to") != null ? tran.get("to").toString() : "";
                        result.add(from + "->" + to);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse stateMachineRef for test case {}", tc.getId());
        }
        return result;
    }
}
