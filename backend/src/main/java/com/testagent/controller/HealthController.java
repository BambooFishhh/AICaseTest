package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.HealthDTO;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<HealthDTO> health() {
        HealthDTO dto = HealthDTO.builder()
                .status("UP")
                .timestamp(LocalDateTime.now())
                .version("1.0.0")
                .build();
        return ApiResponse.success(dto);
    }
}
