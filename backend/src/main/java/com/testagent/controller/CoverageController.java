package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.service.CoverageService;
import com.testagent.service.ProjectAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/coverage")
@CrossOrigin
public class CoverageController {

    @Autowired
    private CoverageService coverageService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @GetMapping("/matrix")
    public ApiResponse<Object> getCoverageMatrix(@PathVariable String projectId) {
        projectAccessService.assertViewAccess(projectId);
        return ApiResponse.success(coverageService.getCoverageMatrix(projectId));
    }

    // v7.15(3b): 未覆盖接口清单——缺口可操作化
    @GetMapping("/uncovered-endpoints")
    public ApiResponse<Object> getUncoveredEndpoints(@PathVariable String projectId) {
        projectAccessService.assertViewAccess(projectId);
        return ApiResponse.success(coverageService.uncoveredEndpoints(projectId));
    }
}
