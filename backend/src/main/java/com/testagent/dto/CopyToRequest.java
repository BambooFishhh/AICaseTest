package com.testagent.dto;

import lombok.Data;

import java.util.List;

// v1.7: 跨项目复制用例请求体
@Data
public class CopyToRequest {
    private List<String> ids;
    private String targetProjectId;
}
