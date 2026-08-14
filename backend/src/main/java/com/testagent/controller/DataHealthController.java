package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.service.DataHealthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v5.8: 数据健康检查（仅 ADMIN）。
 */
@RestController
@RequestMapping("/api/admin/data")
@CrossOrigin
public class DataHealthController {

    @Autowired
    private DataHealthService dataHealthService;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(dataHealthService.overview());
    }
}
