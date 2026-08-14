package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.CreateProjectRequest;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.GenerationParams;
import com.testagent.dto.ProjectDTO;
import com.testagent.service.AnalysisService;
import com.testagent.service.BackupService;
import com.testagent.service.ProjectAccessService;
import com.testagent.service.ProjectService;
import com.testagent.service.TaskQueueService;
import com.testagent.service.TestCaseService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin
public class ProjectController {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    private BackupService backupService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private TaskQueueService taskQueueService;

    @GetMapping
    public ApiResponse<List<ProjectDTO>> listProjects() {
        return ApiResponse.success(projectService.listProjects());
    }

    @PostMapping
    public ApiResponse<ProjectDTO> createProject(@Valid @RequestBody CreateProjectRequest req) {
        return ApiResponse.success(projectService.createProject(req));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectDTO> getProject(@PathVariable String projectId) {
        return ApiResponse.success(projectService.getProject(projectId));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Void> deleteProject(@PathVariable String projectId) {
        projectService.deleteProject(projectId);
        return ApiResponse.success(null, "项目已删除");
    }

    @PostMapping("/{projectId}/analyze")
    public ResponseEntity<ApiResponse<Void>> triggerAnalysis(@PathVariable String projectId) {
        projectService.triggerAnalysis(projectId);
        return ResponseEntity.accepted().body(ApiResponse.accepted("分析已启动"));
    }

    // v4.4: 流式分析（SSE，progress/complete/error）
    @GetMapping("/{projectId}/analyze-stream")
    public SseEmitter analyzeStream(@PathVariable String projectId) {
        projectAccessService.assertOperateAccess(projectId);
        ProjectDTO project = projectService.getProject(projectId);
        String status = project.getStatus();
        if (!"created".equals(status) && !"failed".equals(status)) {
            SseEmitter err = new SseEmitter(0L);
            try {
                err.send(SseEmitter.event().name("error").data(
                        Map.of("message", "当前状态不允许分析: " + status),
                        MediaType.APPLICATION_JSON));
                err.complete();
            } catch (Exception ignored) {
                // 客户端可能已断开
            }
            return err;
        }
        SseEmitter emitter = new SseEmitter(5L * 60 * 1000);
        analysisService.runAnalysisStream(projectId, emitter);
        return emitter;
    }

    @PostMapping("/{projectId}/generate")
    public ResponseEntity<ApiResponse<Void>> triggerGenerate(
            @PathVariable String projectId,
            @RequestBody GenerateRequest req) {
        projectService.triggerGenerate(projectId, req);
        return ResponseEntity.accepted().body(ApiResponse.accepted("测试用例生成已启动"));
    }

    /**
     * v3.2: SSE 流式生成用例。返回 text/event-stream，推送 progress/case/complete/error 事件。
     * 浏览器 EventSource 仅支持 GET，故此端点为 GET。
     * 并发控制：项目状态为 generating 时立即推送 error 事件并关闭，避免重复触发。
     */
    @GetMapping("/{projectId}/testcases/generate-stream")
    public SseEmitter generateStream(@PathVariable String projectId) {
        projectAccessService.assertOperateAccess(projectId);
        ProjectDTO project = projectService.getProject(projectId);
        if ("generating".equals(project.getStatus())) {
            // 已在生成中：推送 error 事件并立即关闭
            SseEmitter err = new SseEmitter(0L);
            try {
                err.send(SseEmitter.event().name("error").data(
                        Map.of("message", "正在生成中，请等待当前任务完成"),
                        MediaType.APPLICATION_JSON));
                err.complete();
            } catch (Exception ignored) {
                // 客户端可能已断开，忽略
            }
            return err;
        }
        SseEmitter emitter = new SseEmitter(5L * 60 * 1000); // 5 分钟超时
        // v5.3: 生成任务进入队列统计
        taskQueueService.enqueue(TaskQueueService.GENERATION_QUEUE, projectId);
        testCaseService.runGenerateStream(projectId, emitter);
        return emitter;
    }

    /**
     * v3.5: 追加生成（SSE）。不删除现有用例，可选 type 过滤。
     * 复用 generating 状态机，与重新生成互斥。
     * complete 事件携带 total/appended/dropped/existingBefore 字段。
     */
    @GetMapping("/{projectId}/testcases/generate-stream-append")
    public SseEmitter generateStreamAppend(@PathVariable String projectId,
                                            @RequestParam(required = false) String type) {
        projectAccessService.assertOperateAccess(projectId);
        ProjectDTO project = projectService.getProject(projectId);
        if ("generating".equals(project.getStatus())) {
            SseEmitter err = new SseEmitter(0L);
            try {
                err.send(SseEmitter.event().name("error").data(
                        Map.of("message", "正在生成中，请等待当前任务完成"),
                        MediaType.APPLICATION_JSON));
                err.complete();
            } catch (Exception ignored) {
            }
            return err;
        }
        SseEmitter emitter = new SseEmitter(5L * 60 * 1000);
        taskQueueService.enqueue(TaskQueueService.GENERATION_QUEUE, projectId);
        testCaseService.runGenerateStreamAppend(projectId, type, emitter);
        return emitter;
    }

    /**
     * v3.3: 取消流式生成。置取消标志，生成线程在下个检查点停止并跳过落库（保留旧用例）。
     * v3.5: 同时适用于追加生成（共用 cancellationFlags 注册表）。
     */
    @PostMapping("/{projectId}/testcases/generate-cancel")
    public ApiResponse<Map<String, Object>> cancelGenerate(@PathVariable String projectId) {
        boolean cancelled = testCaseService.cancelGeneration(projectId);
        return ApiResponse.success(Map.of("cancelled", cancelled));
    }

    // v3.4: 获取生成参数
    @GetMapping("/{projectId}/generation-params")
    public ApiResponse<GenerationParams> getGenerationParams(@PathVariable String projectId) {
        return ApiResponse.success(projectService.getGenerationParams(projectId));
    }

    // v3.4: 更新生成参数
    @PutMapping("/{projectId}/generation-params")
    public ApiResponse<GenerationParams> updateGenerationParams(@PathVariable String projectId,
                                                                @RequestBody GenerationParams params) {
        return ApiResponse.success(projectService.updateGenerationParams(projectId, params));
    }

    // v3.15: 多执行环境
    @GetMapping("/{projectId}/environments")
    public ApiResponse<Map<String, Object>> getExecutionEnvironments(@PathVariable String projectId) {
        return ApiResponse.success(projectService.getExecutionEnvironments(projectId));
    }

    @PutMapping("/{projectId}/environments")
    public ApiResponse<Map<String, Object>> updateExecutionEnvironments(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> payload) {
        return ApiResponse.success(projectService.updateExecutionEnvironments(projectId, payload));
    }

    // v3.16: 项目导出备份（ZIP：project.json + prd.md + testcases.json + coverage.json + executions.json）
    @GetMapping("/{projectId}/export")
    public ResponseEntity<ByteArrayResource> exportProject(@PathVariable String projectId) throws Exception {
        byte[] bytes = backupService.buildProjectBackup(projectId);
        ByteArrayResource resource = new ByteArrayResource(bytes);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"project_" + projectId + "_backup.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .body(resource);
    }

    // v1.10: 查询 PRD
    @GetMapping("/{projectId}/prd")
    public ApiResponse<Map<String, Object>> getPrd(@PathVariable String projectId) {
        return ApiResponse.success(projectService.getPrd(projectId));
    }

    // v1.10: 更新文本 PRD
    @PutMapping("/{projectId}/prd")
    public ApiResponse<ProjectDTO> updatePrd(@PathVariable String projectId,
                                              @RequestBody Map<String, String> req) {
        return ApiResponse.success(projectService.updatePrd(projectId, req.get("prdContent")));
    }

    // v1.10: 上传 PDF
    @PostMapping(value = "/{projectId}/prd/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProjectDTO> uploadPrdPdf(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(projectService.uploadPrdPdf(projectId, file));
    }

    // v1.10: 抓取在线链接
    @PostMapping("/{projectId}/prd/fetch")
    public ApiResponse<ProjectDTO> fetchPrdUrl(@PathVariable String projectId,
                                                @RequestBody Map<String, String> req) {
        return ApiResponse.success(projectService.fetchPrdUrl(projectId, req.get("url")));
    }
}
