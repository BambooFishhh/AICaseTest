package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
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

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCoverageMatrix(String projectId) {
        List<StateMachine> stateMachines = stateMachineRepository.findByProjectId(projectId);
        List<TestCase> testCases = testCaseRepository.findByProjectId(projectId);

        // v7.2(R8): 每条用例只解析一次 JSON——旧实现在 transition×testCase 双重内循环里
        // 反复反序列化同一条 executionHints/stateMachineRef（50 SM×20 tran×500 case ≈ 50 万次 parse）
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
        int coveredTransitions = 0;            // 旧口径：refs 计划 ∪ 已执行 smRef 兜底（向后兼容）
        int plannedCoveredTransitions = 0;
        int executedCoveredTransitions = 0;

        for (StateMachine sm : stateMachines) {
            Map<String, Object> smMap = new LinkedHashMap<>();
            smMap.put("id", sm.getId());
            smMap.put("name", sm.getName());

            // 解析状态机的 transitions JSON 数组
            List<Map<String, Object>> transitions = new ArrayList<>();
            try {
                String transJson = sm.getTransitions();
                if (transJson != null && !transJson.isBlank()) {
                    transitions = objectMapper.readValue(transJson, List.class);
                }
            } catch (Exception e) {
                log.warn("Failed to parse transitions for SM {}", sm.getId(), e);
            }

            // v7.8(R7): 双栏口径——
            // 计划覆盖（planned）：任一用例 coverageRefs.transitionIds 引用（不要求执行）；
            // 执行覆盖（executed）：isExecuted 用例的 refs 引用，或其 stateMachineRef 引用；
            // 旧单栏 covered 保持"refs 计划 ∪ 已执行 smRef 兜底"口径不变（向后兼容），
            // 双栏才是诚实视图——用户不再把"计划覆盖 80%"当"验证过 80%"。
            for (Map<String, Object> tran : transitions) {
                String from = tran.get("from") != null ? tran.get("from").toString() : "";
                String to = tran.get("to") != null ? tran.get("to").toString() : "";
                String transitionKey = from + "->" + to;

                List<String> plannedIds = new ArrayList<>();
                List<String> executedIds = new ArrayList<>();
                List<String> legacyCoveringIds = new ArrayList<>();
                for (TestCase tc : testCases) {
                    boolean planned = refTransitionsByCase.get(tc.getId()).contains(transitionKey);
                    boolean smRefFallback = smRefTransitionsByCase.getOrDefault(tc.getId(), Set.of()).contains(transitionKey);
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
        // coveredTransitions/rate 保持旧口径（向后兼容），planned/executed 为 v7.8(R7) 双栏
        summary.put("coveredTransitions", coveredTransitions);
        summary.put("rate", totalTransitions == 0 ? 0.0 : (double) coveredTransitions / totalTransitions);
        summary.put("plannedCoveredTransitions", plannedCoveredTransitions);
        summary.put("executedCoveredTransitions", executedCoveredTransitions);
        summary.put("plannedRate", totalTransitions == 0 ? 0.0 : (double) plannedCoveredTransitions / totalTransitions);
        summary.put("executedRate", totalTransitions == 0 ? 0.0 : (double) executedCoveredTransitions / totalTransitions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stateMachines", smList);
        result.put("summary", summary);
        return result;
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
