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

    // v6.1 (Agentic RAG): 逐组件语义摘要（交互事件/API/状态/路由 + 按需源码片段 + 业务分）
    private List<Map<String, Object>> componentSummaries;

    // v7.6(G20层3): 前端用户反馈文案——{type: error|success|warning|info, text, file}，
    // ElMessage.error("...") 等调用字面量；与后端 errorMessages 合成对照表供生成侧使用
    private List<Map<String, Object>> userFeedbackTexts;

    private int fileCount;

    private String status;

    // v7.4(C1): 分析过程可观测告警（文件读取失败/rules 块不配对/多 rules 块合并/LLM 失败），随 JSON 落库 code_analysis
    private List<String> warnings;

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
                .componentSummaries(List.of())
                .userFeedbackTexts(List.of())
                .fileCount(0)
                .warnings(List.of())
                .build();
    }
}
