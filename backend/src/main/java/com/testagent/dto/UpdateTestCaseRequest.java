package com.testagent.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class UpdateTestCaseRequest {

    private String title;

    private String module;

    private String type;

    private String priority;

    private List<String> preconditions;

    private List<String> steps;

    private List<String> expectedResults;

    private List<Map<String, Object>> structuredSteps;

    private List<Map<String, Object>> apiEndpoints;

    private Map<String, Object> testData;

    private Map<String, Object> executionHints;

    private String executionStatus;

    // v5.12: AI 采纳后同步人工评审状态（draft/reviewed/approved/rejected）
    private String reviewStatus;
}
