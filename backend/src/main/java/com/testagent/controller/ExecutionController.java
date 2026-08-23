package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.service.ExecutionService;
import com.testagent.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
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

    @Value("${app.output-dir:outputs}")
    private String outputDir;

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
    public ApiResponse<Map<String, Object>> getExecutions(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String testCaseId) {
        return ApiResponse.success(
                executionService.getExecutionsByProject(projectId, page, pageSize, testCaseId));
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

    /** v4.3: 复制执行（快照执行，不回写原用例状态，仅需只读权限） */
    @PostMapping("/projects/{projectId}/testcases/copy-execute")
    public ApiResponse<Map<String, Object>> copyExecute(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> caseIds = (List<String>) body.get("caseIds");
        String targetUrl = (String) body.get("targetUrl");
        String mode = (String) body.get("mode");
        return ApiResponse.success(executionService.copyExecute(projectId, caseIds, targetUrl, mode));
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

    /** 单条执行取消 */
    @PostMapping("/executions/{executionId}/cancel")
    public ApiResponse<Map<String, Object>> cancelExecution(@PathVariable String executionId) {
        return ApiResponse.success(executionService.cancelExecution(executionId));
    }

    /**
     * v2.4: 下载单次执行报告（自包含 HTML）。
     * v7.12(R16): 流式写出——报告不再整串驻留内存（百步级 Agent 执行含双截图时
     * 旧实现峰值 2×报告体积可达数百 MB），HTML 分段 + 截图逐张写出即释放。
     */
    @GetMapping("/executions/{executionId}/report")
    public void downloadReport(
            @PathVariable String executionId,
            @RequestParam(defaultValue = "false") boolean download,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        executionService.getExecution(executionId); // v4.0: 越权校验
        response.setContentType("text/html; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        // v3.13: 默认 inline 预览；download=1 时附件下载
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                (download ? "attachment" : "inline") + "; filename=\"execution_report.html\"");
        reportService.generateExecutionReport(executionId, response.getWriter());
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
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + videoFile.getName() + "\"")
                .body(resource);
    }

    /** v6.0: 执行证据文件预览（截图/录屏帧，受执行记录访问权限保护） */
    @GetMapping("/executions/{executionId}/file")
    public ResponseEntity<Resource> getEvidenceFile(
            @PathVariable String executionId,
            @RequestParam String path) {
        executionService.getExecution(executionId);
        File file = resolveEvidenceFile(path);
        if (file == null || !file.isFile()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mediaTypeFor(file).toString())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getName() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(resource);
    }

    private File resolveEvidenceFile(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String normalized = rawPath.replace('\\', '/');
        if (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        Path baseDir = Paths.get(outputDir).toAbsolutePath().normalize();
        String basePrefix = Paths.get(outputDir).toString().replace('\\', '/');
        if (!basePrefix.endsWith("/")) {
            basePrefix += "/";
        }
        String relative = normalized;
        if (normalized.startsWith(basePrefix)) {
            relative = normalized.substring(basePrefix.length());
        }
        Path resolved = baseDir.resolve(relative).normalize();
        if (!resolved.startsWith(baseDir)) {
            return null;
        }
        return resolved.toFile();
    }

    private MediaType mediaTypeFor(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (name.endsWith(".webm")) {
            return new MediaType("video", "webm");
        }
        if (name.endsWith(".mp4")) {
            return new MediaType("video", "mp4");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
