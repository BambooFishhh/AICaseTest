package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.entity.ReconciliationReport;
import com.testagent.repository.ReconciliationReportRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * v8.6.1(9.3): 向量对账报告查询（ADMIN）。命中 SecurityConfig 既有 /api/admin/** 门禁。
 */
@RestController
@RequestMapping("/api/admin/vector")
public class AdminVectorController {

    @Autowired
    private ReconciliationReportRepository reportRepository;

    @GetMapping("/reconciliation")
    public ApiResponse<List<ReconciliationReport>> latestReports() {
        return ApiResponse.success(reportRepository.findTop20ByOrderByCreatedAtDesc());
    }
}
