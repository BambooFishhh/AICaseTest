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

    // v4.0: 归属用户
    private String userId;

    // v4.3: 所属项目组
    private String groupId;

    // v4.3: 当前用户访问级别 OWNER/OPERATOR/VIEWER/NONE
    private String accessLevel;

    private Map<String, Object> techStack;

    private String status;

    private Map<String, Object> settings;

    // v1.10: 透传 PRD 字段
    private String prdContent;

    private String prdSourceType;

    private String prdSourceRef;

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
                .userId(entity.getUserId())
                .groupId(entity.getGroupId())
                .techStack(JsonHelper.parseMap(entity.getTechStack()))
                .status(entity.getStatus())
                .settings(JsonHelper.parseMap(entity.getSettings()))
                .prdContent(entity.getPrdContent())
                .prdSourceType(entity.getPrdSourceType())
                .prdSourceRef(entity.getPrdSourceRef())
                .errorMessage(entity.getErrorMessage())
                .progress(entity.getProgress())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
