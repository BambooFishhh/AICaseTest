package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.CreateProjectRequest;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.ProjectDTO;
import com.testagent.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@CrossOrigin
public class ProjectController {

    @Autowired
    private ProjectService projectService;

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

    @PostMapping("/{projectId}/generate")
    public ResponseEntity<ApiResponse<Void>> triggerGenerate(
            @PathVariable String projectId,
            @RequestBody GenerateRequest req) {
        projectService.triggerGenerate(projectId, req);
        return ResponseEntity.accepted().body(ApiResponse.accepted("测试用例生成已启动"));
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
