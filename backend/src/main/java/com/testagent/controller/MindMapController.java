package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.MindMapDTO;
import com.testagent.dto.MindMapPreviewNode;
import com.testagent.service.MindMapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/mindmap")
@CrossOrigin
public class MindMapController {

    @Autowired
    private MindMapService mindMapService;

    @PostMapping("/generate")
    public ApiResponse<MindMapDTO> generateMindMap(
            @PathVariable String projectId,
            @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> testcaseIds = body != null ? (List<String>) body.get("testcaseIds") : null;
        return ApiResponse.success(mindMapService.generateMindMap(projectId, testcaseIds));
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> downloadMindMap(@PathVariable String projectId) throws IOException {
        return mindMapService.downloadMindMap(projectId);
    }

    @GetMapping("/preview")
    public ApiResponse<MindMapPreviewNode> previewMindMap(@PathVariable String projectId) {
        return ApiResponse.success(mindMapService.previewMindMap(projectId));
    }
}
