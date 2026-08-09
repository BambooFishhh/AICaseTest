package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.CreateProjectRequest;
import com.testagent.dto.GenerateRequest;
import com.testagent.dto.ProjectDTO;
import com.testagent.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
}
