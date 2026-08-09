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
                .source(entity.getSource())
                .confidence(entity.getConfidence())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
