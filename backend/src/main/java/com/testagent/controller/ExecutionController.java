package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.service.ExecutionService;
import com.testagent.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
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

    @Autowired
    private ReportService reportService;

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

    /** v4.2: 取消批次执行 */
    @PostMapping("/batches/{batchId}/cancel")
    public ApiResponse<Map<String, Object>> cancelBatch(@PathVariable String batchId) {
        executionService.getBatchStatus(batchId); // v4.0: 越权校验
        return ApiResponse.success(executionService.cancelBatch(batchId));
    }

    /** v2.4: 下载单次执行报告（自包含 HTML） */
    @GetMapping("/executions/{executionId}/report")
    public ResponseEntity<String> downloadReport(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "false") boolean download) {
        executionService.getExecution(executionId); // v4.0: 越权校验
        String html = reportService.generateExecutionReport(executionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                // v3.13: 默认 inline 预览；download=1 时附件下载
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (download ? "attachment" : "inline") + "; filename=\"execution_report.html\"")
                .body(html);
    }

    /** v2.4: 下载批次执行报告（自包含 HTML） */
    @GetMapping("/batches/{batchId}/report")
    public ResponseEntity<String> downloadBatchReport(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "false") boolean download) {
        executionService.getBatchStatus(batchId); // v4.0: 越权校验
        String html = reportService.generateBatchReport(batchId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        (download ? "attachment" : "inline") + "; filename=\"batch_report.html\"")
                .body(html);
    }

    /** v2.8: 下载执行录屏视频（WebM） */
    @GetMapping("/executions/{executionId}/video")
    public ResponseEntity<Resource> downloadVideo(@PathVariable String executionId) {
        ExecutionRecord record = executionService.getExecution(executionId);
        if (record == null || record.getRecordingVideoPath() == null) {
            return ResponseEntity.notFound().build();
        }
        File videoFile = new File(record.getRecordingVideoPath());
        if (!videoFile.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(videoFile);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "video/webm")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"recording.webm\"")
                .body(resource);
    }
}
