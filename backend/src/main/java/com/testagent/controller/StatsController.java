package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.Project;
import com.testagent.entity.TestCase;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.service.CoverageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.17: 全局统计（仪表盘数据）。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ExecutionRecordRepository executionRecordRepository;

    @Autowired
    private CoverageService coverageService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        List<Project> projects = projectRepository.findAll();

        long projectCount = projects.size();
        long testCaseCount = 0;
        Map<String, Long> typeCounts = new LinkedHashMap<>();
        typeCounts.put("positive", 0L);
        typeCounts.put("negative", 0L);
        typeCounts.put("boundary", 0L);
        typeCounts.put("data", 0L);

        List<Map<String, Object>> projectCoverage = new ArrayList<>();
        // v7.2(R9): 平均覆盖率按转换总数加权——2 条转换的小项目不再与 200 条的大项目同权重
        double weightedRateSum = 0;
        long weightSum = 0;

        for (Project p : projects) {
            List<TestCase> cases = testCaseRepository.findByProjectId(p.getId());
            testCaseCount += cases.size();
            for (TestCase tc : cases) {
                String type = tc.getType() == null ? "positive" : tc.getType();
                typeCounts.merge(type, 1L, Long::sum);
            }
            Map<String, Object> cov = null;
            try {
                cov = coverageService.getCoverageMatrix(p.getId());
            } catch (Exception ignored) {
                // 无覆盖率数据
            }
            // v8.3: 单一本期口径——未确认范围的项目 rate=null（前端显示"—"），不计入加权平均
            Double stateRate = null;
            if (cov != null && Boolean.TRUE.equals(cov.get("scoped"))) {
                Map<String, Object> summary = (Map<String, Object>) cov.get("summary");
                if (summary != null) {
                    Object rateObj = summary.get("rate");
                    if (rateObj instanceof Number) stateRate = ((Number) rateObj).doubleValue();
                    Object totalObj = summary.get("totalTransitions");
                    if (stateRate != null && totalObj instanceof Number) {
                        long totalTransitions = ((Number) totalObj).longValue();
                        if (totalTransitions > 0) {
                            weightedRateSum += stateRate * totalTransitions;
                            weightSum += totalTransitions;
                        }
                    }
                }
            }
            Map<String, Object> pc = new LinkedHashMap<>();
            pc.put("id", p.getId());
            pc.put("name", p.getName());
            pc.put("stateRate", stateRate == null ? null : Math.round(stateRate * 100));
            projectCoverage.add(pc);
        }

        List<ExecutionRecord> executions = executionRecordRepository.findAll();
        long execCount = executions.size();
        long passed = executions.stream().filter(e -> "passed".equals(e.getStatus())).count();
        double passRate = execCount == 0 ? 0 : (double) passed / execCount * 100;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectCount", projectCount);
        result.put("testCaseCount", testCaseCount);
        result.put("executionCount", execCount);
        result.put("passRate", Math.round(passRate * 10) / 10.0);
        result.put("typeCounts", typeCounts);
        result.put("projectCoverage", projectCoverage);
        result.put("avgStateRate", weightSum == 0 ? 0 : Math.round(weightedRateSum / weightSum * 100));
        // v7.2(R6): 删除从未真实赋值的 apiRate/avgApiRate 假字段（真实接口覆盖的分母
        // ——checklist endpoints——仅存在于生成时上下文，未持久化，不应呈现口径不全的统计）
        return ApiResponse.success(result);
    }
}
