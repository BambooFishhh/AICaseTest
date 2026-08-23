package com.testagent.service;

import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.8(R7): 计划/执行双栏覆盖率单测——旧实现主路径读 coverageRefs 不要求执行、
 * 兜底路径要求 isExecuted，两路标准不一致且只输出一栏 rate，
 * 用户把"计划覆盖 80%"当"验证过 80%"。
 */
class CoverageServicePlannedExecutedTest {

    @Test
    @SuppressWarnings("unchecked")
    void plannedAndExecutedColumnsSemantics() {
        CoverageService service = serviceWithCases();

        Map<String, Object> result = service.getCoverageMatrix("p1");
        List<Map<String, Object>> transitions = transitionsOf(result);

        // A->B：c1 计划引用（未执行）→ planned=true / executed=false
        Map<String, Object> ab = findTransition(transitions, "A", "B");
        assertTrue((Boolean) ab.get("planned"));
        assertFalse((Boolean) ab.get("executed"), "未执行的计划覆盖不算执行验证");
        assertEquals(List.of("c1"), ab.get("plannedCaseIds"));
        assertEquals(List.of(), ab.get("executedCaseIds"));
        assertTrue((Boolean) ab.get("covered"), "旧口径：refs 命中即 covered");

        // B->C：c2 已执行（passed）+ refs 引用 → planned/executed 双 true
        Map<String, Object> bc = findTransition(transitions, "B", "C");
        assertTrue((Boolean) bc.get("planned"));
        assertTrue((Boolean) bc.get("executed"));
        assertEquals(List.of("c2"), bc.get("plannedCaseIds"));
        assertEquals(List.of("c2"), bc.get("executedCaseIds"));

        // C->D：c3 已执行（failed 也算执行验证）+ refs 引用 → 双 true
        Map<String, Object> cd = findTransition(transitions, "C", "D");
        assertTrue((Boolean) cd.get("planned"));
        assertTrue((Boolean) cd.get("executed"), "failed 也是执行过的证据");

        // D->A：c4 已执行 + 仅 stateMachineRef 兜底（refs 为空）→ planned=false / executed=true
        Map<String, Object> da = findTransition(transitions, "D", "A");
        assertFalse((Boolean) da.get("planned"), "refs 没引用不算计划覆盖");
        assertTrue((Boolean) da.get("executed"), "已执行用例的 smRef 兜底计入执行覆盖");
        assertTrue((Boolean) da.get("covered"), "旧口径：已执行 smRef 兜底仍算 covered");
        assertEquals(List.of("c4"), da.get("executedCaseIds"));

        // X->Y：无人引用 → 双 false
        Map<String, Object> xy = findTransition(transitions, "X", "Y");
        assertFalse((Boolean) xy.get("planned"));
        assertFalse((Boolean) xy.get("executed"));
        assertFalse((Boolean) xy.get("covered"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryDualRates() {
        CoverageService service = serviceWithCases();

        Map<String, Object> summary = (Map<String, Object>) service.getCoverageMatrix("p1").get("summary");

        assertEquals(5, ((Number) summary.get("totalTransitions")).intValue());
        // 计划覆盖：A->B、B->C、C->D（refs 引用）= 3
        assertEquals(3, ((Number) summary.get("plannedCoveredTransitions")).intValue());
        // 执行覆盖：B->C、C->D、D->A = 3（D->A 靠 smRef 兜底）
        assertEquals(3, ((Number) summary.get("executedCoveredTransitions")).intValue());
        // 旧口径（并集）：A->B、B->C、C->D、D->A = 4 —— 与 coveredTransitions/rate 兼容
        assertEquals(4, ((Number) summary.get("coveredTransitions")).intValue());
        assertEquals(0.8, (Double) summary.get("rate"), 0.0001);
        assertEquals(0.6, (Double) summary.get("plannedRate"), 0.0001);
        assertEquals(0.6, (Double) summary.get("executedRate"), 0.0001);
    }

    @Test
    @SuppressWarnings("unchecked")
    void plannedWithoutAnyExecutionShowsGap() {
        // 全部用例未执行：计划覆盖 1/1，执行覆盖 0/1 —— 双栏语义的价值场景
        CoverageService service = new CoverageService();
        StateMachineRepository smRepo = mock(StateMachineRepository.class);
        TestCaseRepository tcRepo = mock(TestCaseRepository.class);
        ReflectionTestUtils.setField(service, "stateMachineRepository", smRepo);
        ReflectionTestUtils.setField(service, "testCaseRepository", tcRepo);

        StateMachine sm = new StateMachine();
        sm.setId("sm-1");
        sm.setProjectId("p1");
        sm.setName("订单");
        sm.setTransitions("[{\"from\":\"A\",\"to\":\"B\"}]");
        when(smRepo.findByProjectId("p1")).thenReturn(List.of(sm));

        TestCase c1 = new TestCase();
        c1.setId("c1");
        c1.setProjectId("p1");
        c1.setExecutionHints("{\"coverageRefs\":{\"transitionIds\":[\"A->B\"]}}");
        c1.setExecutionStatus("not_executed");
        when(tcRepo.findByProjectId("p1")).thenReturn(List.of(c1));

        Map<String, Object> summary = (Map<String, Object>) service.getCoverageMatrix("p1").get("summary");
        assertEquals(1, ((Number) summary.get("plannedCoveredTransitions")).intValue());
        assertEquals(0, ((Number) summary.get("executedCoveredTransitions")).intValue(),
                "从未执行的用例不应产生执行覆盖");
    }

    /** 5 转换 × 4 用例：c1 计划未执行 / c2 passed+refs / c3 failed+refs / c4 passed+仅 smRef */
    private CoverageService serviceWithCases() {
        CoverageService service = new CoverageService();
        StateMachineRepository smRepo = mock(StateMachineRepository.class);
        TestCaseRepository tcRepo = mock(TestCaseRepository.class);
        ReflectionTestUtils.setField(service, "stateMachineRepository", smRepo);
        ReflectionTestUtils.setField(service, "testCaseRepository", tcRepo);

        StateMachine sm = new StateMachine();
        sm.setId("sm-1");
        sm.setProjectId("p1");
        sm.setName("订单状态机");
        sm.setTransitions("[{\"from\":\"A\",\"to\":\"B\"},{\"from\":\"B\",\"to\":\"C\"},"
                + "{\"from\":\"C\",\"to\":\"D\"},{\"from\":\"D\",\"to\":\"A\"},{\"from\":\"X\",\"to\":\"Y\"}]");
        when(smRepo.findByProjectId("p1")).thenReturn(List.of(sm));

        // c1: 计划覆盖 A->B（未执行）
        TestCase c1 = new TestCase();
        c1.setId("c1");
        c1.setProjectId("p1");
        c1.setExecutionHints("{\"coverageRefs\":{\"transitionIds\":[\"A->B\"]}}");
        c1.setExecutionStatus("not_executed");

        // c2: 已执行（passed）+ refs 计划 B->C
        TestCase c2 = new TestCase();
        c2.setId("c2");
        c2.setProjectId("p1");
        c2.setExecutionHints("{\"coverageRefs\":{\"transitionIds\":[\"B->C\"]}}");
        c2.setExecutionStatus("passed");

        // c3: 已执行（failed）+ refs 计划 C->D
        TestCase c3 = new TestCase();
        c3.setId("c3");
        c3.setProjectId("p1");
        c3.setExecutionHints("{\"coverageRefs\":{\"transitionIds\":[\"C->D\"]}}");
        c3.setExecutionStatus("failed");

        // c4: 已执行（passed）+ 无 refs，仅 stateMachineRef 兜底 D->A
        TestCase c4 = new TestCase();
        c4.setId("c4");
        c4.setProjectId("p1");
        c4.setExecutionHints("{}");
        c4.setExecutionStatus("passed");
        c4.setStateMachineRef("{\"transitions\":[{\"from\":\"D\",\"to\":\"A\"}]}");

        when(tcRepo.findByProjectId("p1")).thenReturn(List.of(c1, c2, c3, c4));
        return service;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> transitionsOf(Map<String, Object> result) {
        List<Map<String, Object>> stateMachines = (List<Map<String, Object>>) result.get("stateMachines");
        return (List<Map<String, Object>>) stateMachines.get(0).get("transitions");
    }

    private Map<String, Object> findTransition(List<Map<String, Object>> transitions, String from, String to) {
        return transitions.stream()
                .filter(t -> from.equals(String.valueOf(t.get("from"))) && to.equals(String.valueOf(t.get("to"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("transition not found: " + from + "->" + to));
    }
}
