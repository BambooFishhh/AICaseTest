package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.service.CoverageService;
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

    @GetMapping("/matrix")
    public ApiResponse<Object> getCoverageMatrix(@PathVariable String projectId) {
        return ApiResponse.success(coverageService.getCoverageMatrix(projectId));
    }
}
