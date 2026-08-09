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

    private Integer qualityScore;

    private String source;

    private Double confidence;

    private LocalDateTime createdAt;

    public static TestCaseDTO from(TestCase entity) {
        if (entity == null) {
            return null;
        }
        return TestCaseDTO.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
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
                .qualityScore(entity.getQualityScore())
                .source(entity.getSource())
                .confidence(entity.getConfidence())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
