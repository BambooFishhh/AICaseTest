package com.testagent.analyzer.result;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class BackendResult {

    private Map<String, Object> techStack;

    private List<EndpointInfo> endpoints;

    private List<EnumInfo> enums;

    private List<EntityInfo> entities;

    private List<BusinessRule> businessRules;

    private int fileCount;

    private String status;

    public static BackendResult skipped() {
        return BackendResult.builder()
                .status("skipped")
                .techStack(Map.of())
                .endpoints(List.of())
                .enums(List.of())
                .entities(List.of())
                .businessRules(List.of())
                .fileCount(0)
                .build();
    }
}
