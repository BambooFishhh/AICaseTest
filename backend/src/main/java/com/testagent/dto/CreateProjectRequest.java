package com.testagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class CreateProjectRequest {

    @NotBlank
    private String name;

    private String sourceType = "local_path";

    // v3.0: sourcePath 改为可选（纯 PRD 驱动时可为空）
    private String sourcePath;

    // v4.3: 所属项目组（可空）
    private String groupId;

    // 执行前置 Cookie：创建项目时配置，执行时直接注入浏览器，跳过登录界面
    private List<Map<String, Object>> executionCookies;
}
