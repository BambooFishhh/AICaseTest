package com.testagent.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class HealthDTO {

    private String status;

    private LocalDateTime timestamp;

    private String version;

    // v5.5: 组件健康状态
    private String dataSource;

    private String redis;

    private String milvus;
}
