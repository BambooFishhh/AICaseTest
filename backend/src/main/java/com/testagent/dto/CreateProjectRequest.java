package com.testagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateProjectRequest {

    @NotBlank
    private String name;

    private String sourceType = "local_path";

    // v3.0: sourcePath 改为可选（纯 PRD 驱动时可为空）
    private String sourcePath;

    // v4.3: 所属项目组（可空）
    private String groupId;
}
