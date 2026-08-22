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
 * v7.2(R8): 覆盖率矩阵预解析等价性单测。
 * 改动点：每条用例的 executionHints/stateMachineRef 只解析一次（循环外），
 * 双重循环内只做集合查找。本测试验证判定语义与旧实现等价：
 * coverageRefs 命中（不要求已执行）+ stateMachineRef 兜底（要求已执行）。
 */
class CoverageServicePreparseTest {

    @Test
    @SuppressWarnings("unchecked")
    void coverageJudgementSemanticsPreserved() {
        CoverageService service = new CoverageService();
        StateMachineRepository smRepo = mock(StateMachineRepository.class);
        TestCaseRepository tcRepo = mock(TestCaseRepository.class);
        ReflectionTestUtils.setField(service, "stateMachineRepository", smRepo);
        ReflectionTestUtils.setField(service, "testCaseRepository", tcRepo);

        StateMachine sm = new StateMachine();
        sm.setId("sm-1");
        sm.setProjectId("p1");
        sm.setName("订单状态机");
        sm.setTransitions("[{\"from\":\"A\",\"to\":\"B\"},{\"from\":\"B\",\"to\":\"C\"}]");
        when(smRepo.findByProjectId("p1")).thenReturn(List.of(sm));

        // c1: coverageRefs 计划覆盖 A->B（未执行也算——计划口径，语义保持）
        TestCase c1 = new TestCase();
        c1.setId("c1");
        c1.setProjectId("p1");
        c1.setExecutionHints("{\"coverageRefs\":{\"transitionIds\":[\"A->B\"]}}");
        c1.setExecutionStatus("not_executed");

        // c2: 已执行（passed）+ stateMachineRef 兜底命中 B->C
        TestCase c2 = new TestCase();
        c2.setId("c2");
        c2.setProjectId("p1");
        c2.setExecutionHints("{}");
        c2.setExecutionStatus("passed");
        c2.setStateMachineRef("{\"transitions\":[{\"from\":\"B\",\"to\":\"C\"}]}");

        // c3: 未执行 + 只有 stateMachineRef → 兜底路径要求 isExecuted，不算覆盖
        TestCase c3 = new TestCase();
        c3.setId("c3");
        c3.setProjectId("p1");
        c3.setExecutionHints("{}");
        c3.setExecutionStatus("not_executed");
        c3.setStateMachineRef("{\"transitions\":[{\"from\":\"A\",\"to\":\"B\"}]}");

        when(tcRepo.findByProjectId("p1")).thenReturn(List.of(c1, c2, c3));

        Map<String, Object> result = service.getCoverageMatrix("p1");

        List<Map<String, Object>> stateMachines = (List<Map<String, Object>>) result.get("stateMachines");
        List<Map<String, Object>> transitions = (List<Map<String, Object>>) stateMachines.get(0).get("transitions");

        Map<String, Object> ab = transitions.get(0);
        assertTrue((Boolean) ab.get("covered"));
        assertEquals(List.of("c1"), ab.get("testCaseIds"));   // c3 未执行不进兜底

        Map<String, Object> bc = transitions.get(1);
        assertTrue((Boolean) bc.get("covered"));
        assertEquals(List.of("c2"), bc.get("testCaseIds"));

        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(2, ((Number) summary.get("totalTransitions")).intValue());
        assertEquals(2, ((Number) summary.get("coveredTransitions")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void noCoverageDataYieldsZeroRate() {
        CoverageService service = new CoverageService();
        StateMachineRepository smRepo = mock(StateMachineRepository.class);
        TestCaseRepository tcRepo = mock(TestCaseRepository.class);
        ReflectionTestUtils.setField(service, "stateMachineRepository", smRepo);
        ReflectionTestUtils.setField(service, "testCaseRepository", tcRepo);

        StateMachine sm = new StateMachine();
        sm.setId("sm-1");
        sm.setProjectId("p1");
        sm.setName("空状态机");
        sm.setTransitions("[{\"from\":\"X\",\"to\":\"Y\"}]");
        when(smRepo.findByProjectId("p1")).thenReturn(List.of(sm));
        when(tcRepo.findByProjectId("p1")).thenReturn(List.of());

        Map<String, Object> result = service.getCoverageMatrix("p1");
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, ((Number) summary.get("totalTransitions")).intValue());
        assertEquals(0, ((Number) summary.get("coveredTransitions")).intValue());
        assertFalse(((List<?>) ((List<Map<String, Object>>) result.get("stateMachines"))
                .get(0).get("transitions")).isEmpty());
    }
}
