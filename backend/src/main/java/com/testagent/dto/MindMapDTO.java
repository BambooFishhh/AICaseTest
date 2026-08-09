package com.testagent.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class MindMapDTO {

    private String id;

    private String projectId;

    private String title;

    private String filePath;

    private Map<String, Object> statistics;

    private String status;

    private LocalDateTime createdAt;
}
