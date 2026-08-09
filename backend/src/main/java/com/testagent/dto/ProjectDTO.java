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
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
