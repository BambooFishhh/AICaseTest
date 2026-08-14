package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.HealthDTO;
import com.testagent.runtime.RedisRuntimeStore;
import com.testagent.runtime.RuntimeStore;
import com.testagent.service.MilvusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import javax.sql.DataSource;
import java.sql.Connection;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class HealthController {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RuntimeStore runtimeStore;

    @Autowired
    private MilvusService milvusService;

    @GetMapping("/health")
    public ApiResponse<HealthDTO> health() {
        String dataSourceStatus = "UP";
        try (Connection conn = dataSource.getConnection()) {
            if (!conn.isValid(1)) {
                dataSourceStatus = "DOWN";
            }
        } catch (Exception e) {
            dataSourceStatus = "DOWN";
        }
        HealthDTO dto = HealthDTO.builder()
                .status("UP")
                .timestamp(LocalDateTime.now())
                .version("5.5.0")
                .dataSource(dataSourceStatus)
                .redis(runtimeStore instanceof RedisRuntimeStore ? "redis" : "memory")
                .milvus(milvusService.isEnabled() ? "enabled" : "disabled")
                .build();
        return ApiResponse.success(dto);
    }
}
