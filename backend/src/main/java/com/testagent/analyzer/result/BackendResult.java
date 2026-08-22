package com.testagent.analyzer.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BackendResult {

    private Map<String, Object> techStack;

    private List<EndpointInfo> endpoints;

    private List<EnumInfo> enums;

    private List<EntityInfo> entities;

    private List<BusinessRule> businessRules;

    // v6.1 (SAINT): 操作依赖图（createOrder -> checkStock/getUserInfo 等）
    private List<OperationDep> dependencyGraph;

    private int fileCount;

    private String status;

    // v7.4(C1): 分析过程可观测告警（解析失败/排除计数/LLM 增强失败），随 JSON 落库 code_analysis
    private List<String> warnings;

    public static BackendResult skipped() {
        return BackendResult.builder()
                .status("skipped")
                .techStack(Map.of())
                .endpoints(List.of())
                .enums(List.of())
                .entities(List.of())
                .businessRules(List.of())
                .dependencyGraph(List.of())
                .fileCount(0)
                .warnings(List.of())
                .build();
    }
}
