package com.testagent.dto;

import com.testagent.entity.TestCaseVersion;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

// v1.9: 用例版本 DTO
@Data
@Builder
public class TestCaseVersionDTO {

    private String id;
    private String testCaseId;
    private Integer versionNo;
    private String action;
    private LocalDateTime createdAt;

    // 仅详情接口返回（列表接口不返回以减小传输）
    private Map<String, Object> snapshot;

    public static TestCaseVersionDTO listFrom(TestCaseVersion v) {
        return TestCaseVersionDTO.builder()
                .id(v.getId())
                .testCaseId(v.getTestCaseId())
                .versionNo(v.getVersionNo())
                .action(v.getAction())
                .createdAt(v.getCreatedAt())
                .build();
    }

    public static TestCaseVersionDTO detailFrom(TestCaseVersion v) {
        return TestCaseVersionDTO.builder()
                .id(v.getId())
                .testCaseId(v.getTestCaseId())
                .versionNo(v.getVersionNo())
                .action(v.getAction())
                .createdAt(v.getCreatedAt())
                .snapshot(JsonHelper.parseMap(v.getSnapshot()))
                .build();
    }
}
