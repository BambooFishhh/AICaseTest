package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.TestCaseDTO;
import com.testagent.dto.TestCaseListResponse;
import com.testagent.dto.UpdateTestCaseRequest;
import com.testagent.service.TestCaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/testcases")
@CrossOrigin
public class TestCaseController {

    @Autowired
    private TestCaseService testCaseService;

    @GetMapping
    public ApiResponse<TestCaseListResponse> listTestCases(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(
                testCaseService.listTestCases(projectId, page, pageSize, type, module, keyword));
    }

    @GetMapping("/{testcaseId}")
    public ApiResponse<TestCaseDTO> getTestCase(
            @PathVariable String projectId,
            @PathVariable String testcaseId) {
        return ApiResponse.success(testCaseService.getTestCase(projectId, testcaseId));
    }

    @PutMapping("/{testcaseId}")
    public ApiResponse<TestCaseDTO> updateTestCase(
            @PathVariable String projectId,
            @PathVariable String testcaseId,
            @RequestBody UpdateTestCaseRequest req) {
        return ApiResponse.success(testCaseService.updateTestCase(projectId, testcaseId, req));
    }

    @DeleteMapping("/{testcaseId}")
    public ApiResponse<Void> deleteTestCase(
            @PathVariable String projectId,
            @PathVariable String testcaseId) {
        testCaseService.deleteTestCase(projectId, testcaseId);
        return ApiResponse.success(null);
    }
}
