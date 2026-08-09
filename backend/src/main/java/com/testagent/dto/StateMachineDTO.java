package com.testagent.dto;

import com.testagent.entity.StateMachine;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class StateMachineDTO {

    private String id;

    private String projectId;

    private String name;

    private String description;

    private List<Map<String, Object>> states;

    private List<Map<String, Object>> transitions;

    private List<Map<String, Object>> forbiddenTransitions;

    private Double confidence;

    private List<String> sources;

    private LocalDateTime createdAt;

    public static StateMachineDTO from(StateMachine entity) {
        if (entity == null) {
            return null;
        }
        return StateMachineDTO.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .name(entity.getName())
                .description(entity.getDescription())
                .states(JsonHelper.parseListMap(entity.getStates()))
                .transitions(JsonHelper.parseListMap(entity.getTransitions()))
                .forbiddenTransitions(JsonHelper.parseListMap(entity.getForbiddenTransitions()))
                .confidence(entity.getConfidence())
                .sources(JsonHelper.parseListString(entity.getSources()))
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
