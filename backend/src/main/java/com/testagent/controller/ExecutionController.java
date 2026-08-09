package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.service.ExecutionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v2.0: 测试用例执行 API
 * v2.1: 新增 Agent 模式 + 批量执行
 */
@RestController
@RequestMapping("/api")
public class ExecutionController {

    @Autowired
    private ExecutionService executionService;

    /**
     * 触发执行
     * @param mode 执行模式：programmatic（默认）或 agent（v2.1）
     */
    @PostMapping("/projects/{projectId}/testcases/{caseId}/execute")
    public ApiResponse<Map<String, String>> execute(
            @PathVariable String projectId,
            @PathVariable String caseId,
            @RequestParam(defaultValue = "programmatic") String mode,
            @RequestBody(required = false) Map<String, String> body) {
        String targetUrl = body != null ? body.get("targetUrl") : null;
        String executionId = executionService.execute(projectId, caseId, targetUrl, mode, null);
        return ApiResponse.success(Map.of("executionId", executionId));
    }

    /** 查询执行结果 */
    @GetMapping("/executions/{executionId}")
    public ApiResponse<ExecutionRecord> getExecution(@PathVariable String executionId) {
        ExecutionRecord record = executionService.getExecution(executionId);
        return ApiResponse.success(record);
    }

    /** 项目执行历史 */
    @GetMapping("/projects/{projectId}/executions")
    public ApiResponse<List<ExecutionRecord>> getExecutions(@PathVariable String projectId) {
        return ApiResponse.success(executionService.getExecutionsByProject(projectId));
    }

    /** 执行步骤详情 */
    @GetMapping("/executions/{executionId}/steps")
    public ApiResponse<List<ExecutionStep>> getSteps(@PathVariable String executionId) {
        return ApiResponse.success(executionService.getExecutionSteps(executionId));
    }

    /** v2.1: 批量执行 */
    @PostMapping("/projects/{projectId}/testcases/batch-execute")
    public ApiResponse<Map<String, String>> batchExecute(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> caseIds = (List<String>) body.get("caseIds");
        String targetUrl = (String) body.get("targetUrl");
        String batchId = executionService.executeBatch(projectId, caseIds, targetUrl);
        return ApiResponse.success(Map.of("batchId", batchId));
    }

    /** v2.1: 查询批次状态 */
    @GetMapping("/batches/{batchId}")
    public ApiResponse<Map<String, Object>> getBatch(@PathVariable String batchId) {
        return ApiResponse.success(executionService.getBatchStatus(batchId));
    }
}
