package com.testagent.analyzer.result;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class FrontendResult {

    private Map<String, Object> techStack;

    private List<Map<String, Object>> routes;

    private List<Map<String, Object>> apiCalls;

    private int fileCount;

    private String status;

    public static FrontendResult skipped() {
        return FrontendResult.builder()
                .status("skipped")
                .techStack(Map.of())
                .routes(List.of())
                .apiCalls(List.of())
                .fileCount(0)
                .build();
    }
}
