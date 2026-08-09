package com.testagent.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class TestCaseListResponse {

    private long total;

    private int page;

    private int pageSize;

    private List<TestCaseDTO> testCases;

    private Map<String, Object> coverage;
}
