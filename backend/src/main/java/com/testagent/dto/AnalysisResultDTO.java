package com.testagent.dto;

import com.testagent.entity.CodeAnalysis;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class AnalysisResultDTO {

    private String id;

    private String projectId;

    private String status;

    private String errorMessage;

    private Map<String, Object> frontendResult;

    private Map<String, Object> backendResult;

    private LocalDateTime createdAt;

    public static AnalysisResultDTO from(CodeAnalysis entity) {
        if (entity == null) {
            return null;
        }
        return AnalysisResultDTO.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .status(entity.getStatus())
                .errorMessage(entity.getErrorMessage())
                .frontendResult(JsonHelper.parseMap(entity.getFrontendResult()))
                .backendResult(JsonHelper.parseMap(entity.getBackendResult()))
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
