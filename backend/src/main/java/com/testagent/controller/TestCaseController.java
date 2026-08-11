package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.BatchDeleteRequest;
import com.testagent.dto.CopyToRequest;
import com.testagent.dto.CreateTestCaseRequest;
import com.testagent.dto.ReviewRequest;
import com.testagent.dto.TestCaseDTO;
import com.testagent.dto.TestCaseVersionDTO;
import com.testagent.dto.TestCaseListResponse;
import com.testagent.dto.UpdateTestCaseRequest;
import com.testagent.service.TestCaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/testcases")
@CrossOrigin
public class TestCaseController {

    @Autowired
    private TestCaseService testCaseService;

    @PostMapping
    public ApiResponse<TestCaseDTO> createTestCase(
            @PathVariable String projectId,
            @RequestBody CreateTestCaseRequest req) {
        return ApiResponse.success(testCaseService.createTestCase(projectId, req));
    }

    @GetMapping
    public ApiResponse<TestCaseListResponse> listTestCases(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String reviewStatus) {
        return ApiResponse.success(testCaseService.listTestCases(
                projectId, page, pageSize, type, module, keyword, reviewStatus));
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

    @DeleteMapping("/batch")
    public ApiResponse<Integer> batchDeleteTestCases(
            @PathVariable String projectId,
            @RequestBody BatchDeleteRequest req) {
        int deleted = testCaseService.batchDeleteTestCases(projectId, req.getIds());
        return ApiResponse.success(deleted);
    }

    // v1.7: 导出用例（JSON / CSV），支持导出全部或选中
    @GetMapping("/export")
    public ResponseEntity<Resource> exportTestCases(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "json") String format,
            @RequestParam(required = false) List<String> ids) throws IOException {
        return testCaseService.exportTestCases(projectId, format, ids);
    }

    // v1.7: 导入 JSON 用例文件
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> importTestCases(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(testCaseService.importTestCases(projectId, file));
    }

    // v3.9: 导入 XMind 文件
    @PostMapping(value = "/import-xmind", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> importXmind(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(testCaseService.importFromXmind(projectId, file));
    }

    // v1.7: 复制选中用例到其他项目
    @PostMapping("/copy-to")
    public ApiResponse<Map<String, Object>> copyToProject(
            @PathVariable String projectId,
            @RequestBody CopyToRequest req) {
        return ApiResponse.success(
                testCaseService.copyToProject(projectId, req.getIds(), req.getTargetProjectId()));
    }

    // v1.8: 批量改评审状态
    @PostMapping("/review")
    public ApiResponse<Map<String, Object>> reviewTestCases(
            @PathVariable String projectId,
            @RequestBody ReviewRequest req) {
        return ApiResponse.success(testCaseService.batchUpdateReviewStatus(
                projectId, req.getIds(), req.getStatus(), req.getReviewer()));
    }

    // v1.9: 用例版本列表
    @GetMapping("/{testcaseId}/versions")
    public ApiResponse<List<TestCaseVersionDTO>> listVersions(
            @PathVariable String projectId,
            @PathVariable String testcaseId) {
        return ApiResponse.success(testCaseService.listVersions(projectId, testcaseId));
    }

    // v1.9: 用例版本详情（含快照）
    @GetMapping("/{testcaseId}/versions/{versionId}")
    public ApiResponse<TestCaseVersionDTO> getVersion(
            @PathVariable String projectId,
            @PathVariable String testcaseId,
            @PathVariable String versionId) {
        return ApiResponse.success(testCaseService.getVersion(projectId, testcaseId, versionId));
    }

    // v1.9: 回滚到指定版本
    @PostMapping("/{testcaseId}/versions/{versionId}/rollback")
    public ApiResponse<TestCaseDTO> rollbackToVersion(
            @PathVariable String projectId,
            @PathVariable String testcaseId,
            @PathVariable String versionId) {
        return ApiResponse.success(
                testCaseService.rollbackToVersion(projectId, testcaseId, versionId));
    }
}
