package com.testagent.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateTestCaseRequest {

    private String title;

    private String module;

    private String type;

    private String priority;

    private List<String> preconditions;

    private List<String> steps;

    private List<String> expectedResults;
}
