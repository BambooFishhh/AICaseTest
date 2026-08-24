package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.entity.Project;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.service.CoverageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v7.2(R6/R9): 仪表盘统计单测。
 * R6: 假 apiRate/avgApiRate 字段必须删除（从未真实赋值，恒 0 误导调用方）。
 * R9: 平均状态机覆盖率按转换总数加权（小项目不再与大项目同权重）。
 */
class StatsControllerOverviewTest {

    @Test
    @SuppressWarnings("unchecked")
    void weightedAverageAndNoFakeApiRateFields() {
        StatsController controller = new StatsController();
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        TestCaseRepository testCaseRepo = mock(TestCaseRepository.class);
        ExecutionRecordRepository execRepo = mock(ExecutionRecordRepository.class);
        CoverageService coverageService = mock(CoverageService.class);
        ReflectionTestUtils.setField(controller, "projectRepository", projectRepo);
        ReflectionTestUtils.setField(controller, "testCaseRepository", testCaseRepo);
        ReflectionTestUtils.setField(controller, "executionRecordRepository", execRepo);
        ReflectionTestUtils.setField(controller, "coverageService", coverageService);

        Project big = project("p1", "大项目");
        Project small = project("p2", "小项目");
        when(projectRepo.findAll()).thenReturn(List.of(big, small));
        when(testCaseRepo.findByProjectId("p1")).thenReturn(List.of());
        when(testCaseRepo.findByProjectId("p2")).thenReturn(List.of());
        when(execRepo.findAll()).thenReturn(List.of());

        // 大项目：10 条转换覆盖 5 条（50%）；小项目：2 条转换覆盖 0 条（0%）
        when(coverageService.getCoverageMatrix("p1")).thenReturn(coverage(10, 5));
        when(coverageService.getCoverageMatrix("p2")).thenReturn(coverage(2, 0));

        ApiResponse<Map<String, Object>> response = controller.overview();
        Map<String, Object> result = response.getData();

        // R9: 加权平均 = (0.5×10 + 0×2) / 12 ≈ 41.67 → 42（旧简单平均是 25）
        assertEquals(42, ((Number) result.get("avgStateRate")).intValue());

        // R6: 假字段已删除
        assertFalse(result.containsKey("avgApiRate"));
        List<Map<String, Object>> projectCoverage =
                (List<Map<String, Object>>) result.get("projectCoverage");
        assertEquals(2, projectCoverage.size());
        for (Map<String, Object> pc : projectCoverage) {
            assertFalse(pc.containsKey("apiRate"));
            assertTrue(pc.containsKey("stateRate"));
        }
        assertEquals(50, ((Number) projectCoverage.get(0).get("stateRate")).intValue());
        assertEquals(0, ((Number) projectCoverage.get(1).get("stateRate")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroTransitionProjectsExcludedFromWeightedAverage() {
        StatsController controller = new StatsController();
        ProjectRepository projectRepo = mock(ProjectRepository.class);
        TestCaseRepository testCaseRepo = mock(TestCaseRepository.class);
        ExecutionRecordRepository execRepo = mock(ExecutionRecordRepository.class);
        CoverageService coverageService = mock(CoverageService.class);
        ReflectionTestUtils.setField(controller, "projectRepository", projectRepo);
        ReflectionTestUtils.setField(controller, "testCaseRepository", testCaseRepo);
        ReflectionTestUtils.setField(controller, "executionRecordRepository", execRepo);
        ReflectionTestUtils.setField(controller, "coverageService", coverageService);

        when(projectRepo.findAll()).thenReturn(List.of(project("p1", "无转换项目")));
        when(testCaseRepo.findByProjectId("p1")).thenReturn(List.of());
        when(execRepo.findAll()).thenReturn(List.of());
        // totalTransitions = 0：rate 无度量意义，不参与加权，避免 0/0
        when(coverageService.getCoverageMatrix("p1")).thenReturn(coverage(0, 0));

        Map<String, Object> result = controller.overview().getData();
        assertEquals(0, ((Number) result.get("avgStateRate")).intValue());
    }

    private Project project(String id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private Map<String, Object> coverage(int total, int covered) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTransitions", total);
        summary.put("coveredTransitions", covered);
        summary.put("rate", total == 0 ? 0.0 : (double) covered / total);
        Map<String, Object> result = new LinkedHashMap<>();
        // v8.3: 覆盖矩阵带 scoped 标记（单一本期口径）
        result.put("scoped", true);
        result.put("summary", summary);
        return result;
    }
}
