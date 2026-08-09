package com.testagent.dto;

import com.testagent.entity.Project;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ProjectDTO {

    private String id;

    private String name;

    private String sourceType;

    private String sourcePath;

    private Map<String, Object> techStack;

    private String status;

    private Map<String, Object> settings;

    // v1.6: 透传错误详情与生成进度
    private String errorMessage;

    private String progress;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static ProjectDTO from(Project entity) {
        if (entity == null) {
            return null;
        }
        return ProjectDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .sourceType(entity.getSourceType())
                .sourcePath(entity.getSourcePath())
                .techStack(JsonHelper.parseMap(entity.getTechStack()))
                .status(entity.getStatus())
                .settings(JsonHelper.parseMap(entity.getSettings()))
                .errorMessage(entity.getErrorMessage())
                .progress(entity.getProgress())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
