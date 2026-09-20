package com.testagent.dto;

import com.testagent.entity.TestCase;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class TestCaseDTO {

    private String id;

    private String projectId;

    // v7.15(2a): 项目内展示序号（id 仍为全局唯一 TC-xxx）
    private Integer projectSeq;

    private String title;

    private String module;

    private String type;

    private String priority;

    private List<String> preconditions;

    private List<String> steps;

    private List<String> expectedResults;

    private Map<String, Object> stateMachineRef;

    private List<Map<String, Object>> structuredSteps;

    private List<Map<String, Object>> apiEndpoints;

    private Map<String, Object> testData;

    private Map<String, Object> executionHints;

    private String executionStatus;

    // v1.8: 评审状态（draft/reviewed/approved/rejected）
    private String reviewStatus;

    private Integer qualityScore;

    private String source;

    private Double confidence;

    // v13.18(证据权威判定): 生成期待裁决溯源——null 表示生成时不存在待裁决冲突
    private String verdict;

    /** 关联的冲突 key 集合（逗号分隔，项目级快照） */
    private String conflictRef;

    /** 关联冲突的维度集合（逗号分隔） */
    private String dimension;

    private LocalDateTime createdAt;

    public static TestCaseDTO from(TestCase entity) {
        if (entity == null) {
            return null;
        }
        return TestCaseDTO.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .projectSeq(entity.getProjectSeq())
                .title(entity.getTitle())
                .module(entity.getModule())
                .type(entity.getType())
                .priority(entity.getPriority())
                .preconditions(JsonHelper.parseListString(entity.getPreconditions()))
                .steps(JsonHelper.parseListString(entity.getSteps()))
                .expectedResults(JsonHelper.parseListString(entity.getExpectedResults()))
                .stateMachineRef(JsonHelper.parseMap(entity.getStateMachineRef()))
                .structuredSteps(JsonHelper.parseListMap(entity.getStructuredSteps()))
                .apiEndpoints(JsonHelper.parseListMap(entity.getApiEndpoints()))
                .testData(JsonHelper.parseMap(entity.getTestData()))
                .executionHints(JsonHelper.parseMap(entity.getExecutionHints()))
                .executionStatus(entity.getExecutionStatus())
                // v1.8: 历史数据 reviewStatus 为 null 时兜底为 draft
                .reviewStatus(entity.getReviewStatus() == null ? "draft" : entity.getReviewStatus())
                .qualityScore(entity.getQualityScore())
                .source(entity.getSource())
                .confidence(entity.getConfidence())
                .verdict(entity.getVerdict())
                .conflictRef(entity.getConflictRef())
                .dimension(entity.getDimension())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
