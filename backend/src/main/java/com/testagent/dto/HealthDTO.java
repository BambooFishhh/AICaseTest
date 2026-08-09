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
}
