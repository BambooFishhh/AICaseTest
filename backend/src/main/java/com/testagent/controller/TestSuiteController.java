package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.service.TestSuiteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v3.15: 测试集/回归集接口。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-suites")
public class TestSuiteController {

    @Autowired
    private TestSuiteService testSuiteService;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        @SuppressWarnings("unchecked")
        List<String> caseIds = (List<String>) body.get("caseIds");
        return ApiResponse.success(testSuiteService.create(projectId, name, caseIds));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable String projectId) {
        return ApiResponse.success(testSuiteService.list(projectId));
    }

    @DeleteMapping("/{suiteId}")
    public ApiResponse<Void> delete(@PathVariable String projectId, @PathVariable String suiteId) {
        testSuiteService.delete(projectId, suiteId);
        return ApiResponse.success(null, "测试集已删除");
    }

    @PostMapping("/{suiteId}/execute")
    public ApiResponse<Map<String, Object>> execute(
            @PathVariable String projectId,
            @PathVariable String suiteId,
            @RequestBody(required = false) Map<String, Object> body) {
        String targetUrl = body != null ? (String) body.get("targetUrl") : null;
        return ApiResponse.success(testSuiteService.run(projectId, suiteId, targetUrl));
    }
}
