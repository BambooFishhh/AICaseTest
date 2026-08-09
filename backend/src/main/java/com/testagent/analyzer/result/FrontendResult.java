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

    // v1.11: 表单字段与校验规则
    private List<Map<String, Object>> forms;

    // v1.11: 组件交互状态（弹窗/抽屉/分步/标签页）
    private List<Map<String, Object>> componentStates;

    // v1.11: DOM 选择器（id/data-testid/ref/aria-label）
    private List<Map<String, Object>> domSelectors;

    // v1.11: 页面跳转关系
    private List<Map<String, Object>> pageFlows;

    private int fileCount;

    private String status;

    public static FrontendResult skipped() {
        return FrontendResult.builder()
                .status("skipped")
                .techStack(Map.of())
                .routes(List.of())
                .apiCalls(List.of())
                .forms(List.of())
                .componentStates(List.of())
                .domSelectors(List.of())
                .pageFlows(List.of())
                .fileCount(0)
                .build();
    }
}
